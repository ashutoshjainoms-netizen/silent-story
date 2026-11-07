package com.ashu.ghostview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Plays stories natively. It only downloads the media files and never calls
 * Instagram's "seen" endpoint, so the owner can't see that you watched.
 */
class StoryViewerActivity : AppCompatActivity() {

    companion object {
        private const val IMAGE_MS = 5000L

        fun start(ctx: Context, users: List<IgUser>, index: Int) {
            ctx.startActivity(
                Intent(ctx, StoryViewerActivity::class.java)
                    .putStringArrayListExtra("ids", ArrayList(users.map { it.id }))
                    .putStringArrayListExtra("names", ArrayList(users.map { it.username }))
                    .putStringArrayListExtra("pics", ArrayList(users.map { it.pic }))
                    .putExtra("index", index)
            )
        }
    }

    private lateinit var image: ImageView
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var bars: LinearLayout
    private lateinit var avatar: ImageView
    private lateinit var username: TextView
    private lateinit var ago: TextView
    private lateinit var btnMore: View
    private lateinit var message: TextView
    private lateinit var webLoader: WebStoryLoader
    private var muted = false
    private lateinit var player: ExoPlayer
    private lateinit var store: Store

    private var users = listOf<IgUser>()
    private var userIdx = 0
    private var items = listOf<StoryItem>()
    private var itemIdx = 0
    private var elapsed = 0L
    private var paused = false
    private var imageReady = false
    private var loadJob: Job? = null
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 50)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        store = Store(this)
        image = findViewById(R.id.image)
        playerView = findViewById(R.id.player)
        loading = findViewById(R.id.loading)
        bars = findViewById(R.id.bars)
        avatar = findViewById(R.id.avatar)
        username = findViewById(R.id.username)
        ago = findViewById(R.id.ago)
        btnMore = findViewById(R.id.btnMore)
        message = findViewById(R.id.message)
        webLoader = WebStoryLoader(this, findViewById(R.id.root))
        muted = getSharedPreferences("ghost", MODE_PRIVATE).getBoolean("muted", false)

        val ids = intent.getStringArrayListExtra("ids") ?: arrayListOf()
        val names = intent.getStringArrayListExtra("names") ?: arrayListOf()
        val pics = intent.getStringArrayListExtra("pics") ?: arrayListOf()
        users = ids.indices.map { IgUser(ids[it], names.getOrElse(it) { "" }, "", pics.getOrElse(it) { "" }) }
        userIdx = intent.getIntExtra("index", 0).coerceIn(0, (users.size - 1).coerceAtLeast(0))
        if (users.isEmpty()) { finish(); return }

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.volume = if (muted) 0f else 1f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) loading.isVisible = false
                if (state == Player.STATE_ENDED) next()
            }
        })

        val root = findViewById<View>(R.id.root)
        findViewById<View>(R.id.touch).setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = System.currentTimeMillis()
                    downX = ev.rawX; downY = ev.rawY
                    dragging = false
                    setPaused(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    // Start a drag once the finger moves down clearly more than sideways.
                    if (!dragging && dy > v.dp(14) && dy > kotlin.math.abs(dx)) dragging = true
                    if (dragging) root.translationY = dy.coerceAtLeast(0f)
                }
                MotionEvent.ACTION_UP -> {
                    val dy = ev.rawY - downY
                    if (dragging && dy > v.dp(120)) {
                        // Swiped down far enough: slide away and go back to the stories page.
                        root.animate().translationY(root.height.toFloat())
                            .setDuration(150).withEndAction { finish() }.start()
                        return@setOnTouchListener true
                    }
                    if (dragging) root.animate().translationY(0f).setDuration(150).start()
                    setPaused(false)
                    if (!dragging && System.currentTimeMillis() - downAt < 250) {
                        if (ev.x < v.width / 3f) prev() else next()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) root.animate().translationY(0f).setDuration(150).start()
                    dragging = false
                    setPaused(false)
                }
            }
            true
        }
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        avatar.setOnClickListener { UserActivity.start(this, users[userIdx].username) }
        username.setOnClickListener { UserActivity.start(this, users[userIdx].username) }

        findViewById<View>(R.id.btnSave).setOnClickListener {
            current()?.let { Saver.download(this, it.url, it.isVideo, "${users[userIdx].username}_story_${it.pk}") }
        }
        findViewById<View>(R.id.btnShare).setOnClickListener {
            val cur = current() ?: return@setOnClickListener
            setPaused(true)
            lifecycleScope.launch { Saver.share(this@StoryViewerActivity, cur.url, cur.isVideo, "${users[userIdx].username}_story_${cur.pk}") }
        }
        btnMore.setOnClickListener { showOptions() }

        loadUser()
    }

    override fun onResume() {
        super.onResume()
        setPaused(false)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        setPaused(true)
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        if (::webLoader.isInitialized) webLoader.destroy()
    }

    private fun current(): StoryItem? = items.getOrNull(itemIdx)

    private fun setPaused(p: Boolean) {
        paused = p
        if (!::player.isInitialized) return
        if (p) player.pause() else if (current()?.isVideo == true) player.play()
    }

    private fun showOptions() {
        val u = users[userIdx]
        setPaused(true)
        val menu = PopupMenu(this, btnMore)
        menu.menu.add(0, 1, 0, if (store.isFavorite(u.username)) "★ Remove from favorites" else "☆ Add to favorites")
        menu.menu.add(0, 2, 1, if (muted) "🔊 Unmute" else "🔇 Mute")
        menu.menu.add(0, 3, 2, "👤 Open profile")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val added = store.toggleFavorite(u)
                    Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                    getSharedPreferences("ghost", MODE_PRIVATE).edit().putBoolean("muted", muted).apply()
                }
                3 -> UserActivity.start(this, u.username)
            }
            true
        }
        menu.setOnDismissListener { setPaused(false) }
        menu.show()
    }

    private fun loadUser() {
        val u = users.getOrNull(userIdx) ?: return finish()
        username.text = u.username
        avatar.loadCircle(u.pic)
        items = emptyList()
        message.isVisible = false
        bars.removeAllViews()
        player.stop()
        image.setImageDrawable(null)
        loading.isVisible = true

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                var list = try { Ig.stories(u.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
                if (list.isEmpty()) list = webLoader.load(u.username)
                if (list.isEmpty()) {
                    showMessage("Couldn't load stories for @${u.username}.\n\n${webLoader.lastError}\n${Ig.lastNote()}")
                    return@launch
                }
                // Start at the first story you haven't watched yet (like Instagram does).
                val seen = store.seenUpTo(u.id)
                itemIdx = list.indexOfFirst { it.takenAt > seen }.let { if (it < 0) 0 else it }
                items = list
                store.markSeen(u.id, list.maxOf { it.takenAt })
                buildBars()
                showItem()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: "Couldn't load this story.")
            }
        }
    }

    private fun buildBars() {
        bars.removeAllViews()
        repeat(items.size) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            }
            bars.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = bars.dp(2); marginEnd = bars.dp(2)
            })
        }
    }

    private fun bar(i: Int) = bars.getChildAt(i) as? ProgressBar

    private fun showItem() {
        val it = current() ?: return
        message.isVisible = false
        elapsed = 0
        for (i in items.indices) bar(i)?.progress = if (i < itemIdx) 1000 else 0
        ago.text = timeAgo(it.takenAt)
        loading.isVisible = true

        if (it.isVideo) {
            image.isVisible = false
            playerView.isVisible = true
            player.setMediaItem(MediaItem.fromUri(it.url))
            player.prepare()
            if (!paused) player.play()
        } else {
            player.stop()
            playerView.isVisible = false
            image.isVisible = true
            imageReady = false
            image.load(it.url) {
                crossfade(true)
                listener(
                    onSuccess = { _, _ -> imageReady = true; loading.isVisible = false },
                    onError = { _, _ -> imageReady = true; loading.isVisible = false }
                )
            }
        }
    }

    private fun step() {
        if (paused) return
        val cur = current() ?: return
        val frac = if (cur.isVideo) {
            val d = player.duration
            if (d > 0) player.currentPosition.toFloat() / d else 0f
        } else {
            if (imageReady) elapsed += 50
            elapsed.toFloat() / IMAGE_MS
        }
        bar(itemIdx)?.progress = (frac.coerceIn(0f, 1f) * 1000).toInt()
        if (!cur.isVideo && frac >= 1f) next()
    }

    private fun showMessage(text: String) {
        loading.isVisible = false
        image.isVisible = false
        playerView.isVisible = false
        val more = if (userIdx < users.size - 1) "\n\nTap to go to the next person." else "\n\nTap ✕ to close."
        message.text = text + more
        message.isVisible = true
    }

    private fun next() {
        if (items.isEmpty()) { nextUser(); return }
        if (itemIdx < items.size - 1) { itemIdx++; showItem() } else nextUser()
    }

    private fun prev() {
        if (items.isEmpty()) { if (userIdx > 0) { userIdx--; loadUser() }; return }
        when {
            itemIdx > 0 -> { itemIdx--; showItem() }
            userIdx > 0 -> { userIdx--; loadUser() }
            else -> showItem()
        }
    }

    private fun nextUser() {
        if (userIdx < users.size - 1) { userIdx++; loadUser() } else finish()
    }

    private fun timeAgo(takenAt: Long): String {
        val s = nowSec() - takenAt
        return when {
            s < 60 -> "just now"
            s < 120 -> "1 minute ago"
            s < 3600 -> "${s / 60} minutes ago"
            s < 7200 -> "1 hour ago"
            else -> "${s / 3600} hours ago"
        }
    }
}
