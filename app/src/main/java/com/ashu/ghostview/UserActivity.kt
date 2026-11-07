package com.ashu.ghostview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A profile: HD photo, anonymous stories, favorite toggle. */
class UserActivity : AppCompatActivity() {
    companion object {
        fun start(ctx: Context, username: String) =
            ctx.startActivity(Intent(ctx, UserActivity::class.java).putExtra("username", username))
    }

    private var user: IgUser? = null
    private var hdUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user)
        val username = intent.getStringExtra("username") ?: return finish()
        val store = Store(this)

        val avatar = findViewById<ImageView>(R.id.avatar)
        val fullname = findViewById<TextView>(R.id.fullname)
        val status = findViewById<TextView>(R.id.status)
        val btnStories = findViewById<MaterialButton>(R.id.btnStories)
        val btnHd = findViewById<MaterialButton>(R.id.btnHd)
        val btnFav = findViewById<MaterialButton>(R.id.btnFav)
        findViewById<TextView>(R.id.username).text = "@$username"
        findViewById<TextView>(R.id.back).setOnClickListener { finish() }

        fun updateFav() {
            btnFav.text = if (store.isFavorite(username)) "★ In favorites" else "☆ Add to favorites"
        }
        updateFav()

        btnStories.setOnClickListener { user?.let { StoryViewerActivity.start(this, listOf(it), 0) } }
        btnHd.setOnClickListener { hdUrl?.let { Saver.download(this, it, false, "${username}_profile_hd") } }
        btnFav.setOnClickListener { user?.let { store.toggleFavorite(it); updateFav() } }
        findViewById<MaterialButton>(R.id.btnWeb).setOnClickListener {
            startActivity(Intent(this, WebActivity::class.java).putExtra("url", "https://www.instagram.com/$username/"))
        }

        lifecycleScope.launch {
            try {
                val u = Ig.userByName(username)
                user = u
                fullname.text = u.fullName
                avatar.loadCircle(u.pic)
                btnStories.isEnabled = true
                btnFav.isEnabled = true
                val hd = Ig.hdPic(u)
                hdUrl = hd
                avatar.loadCircle(hd)
                btnHd.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = e.message
            }
        }
    }
}
