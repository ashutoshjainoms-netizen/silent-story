package com.ashu.ghostview

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- Home ----------------
class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var status: TextView
    private lateinit var hurryList: RecyclerView
    private lateinit var hurryArrow: TextView
    private val grid = StoryAdapter(false) { list, i -> open(list, i) }
    private val hurry = StoryAdapter(true) { list, i -> open(list, i) }
    private var loaded = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            hurry.tick()
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipe = view.findViewById(R.id.swipe)
        status = view.findViewById(R.id.status)
        hurryList = view.findViewById(R.id.hurryList)
        hurryArrow = view.findViewById(R.id.hurryArrow)

        hurryList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        hurryList.adapter = hurry
        view.findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = grid
        }
        view.findViewById<View>(R.id.hurryHeader).setOnClickListener {
            hurryList.isVisible = !hurryList.isVisible
            hurryArrow.text = if (hurryList.isVisible) "⌃" else "⌄"
        }
        swipe.setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        if (Ig.isLoggedIn() && !loaded) load() else {
            grid.notifyDataSetChanged()   // refresh rings after watching stories
            hurry.notifyDataSetChanged()
        }
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            swipe.isRefreshing = true
            status.isVisible = false
            try {
                val tray = Ig.tray()
                loaded = true
                val store = Store(requireContext())
                // Unwatched first, keeping Instagram's order inside each group.
                val sorted = tray.sortedBy { if (it.latest > store.seenUpTo(it.user.id)) 0 else 1 }
                grid.submit(sorted)
                hurry.submit(tray.filter { it.expiringAt > nowSec() }.sortedBy { it.expiringAt }.take(15))
                if (tray.isEmpty()) {
                    status.text = "No one you follow has a story right now.\nPull down to refresh."
                    status.isVisible = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = (e.message ?: "Something went wrong") + "\nPull down to retry."
                status.isVisible = true
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun open(list: List<TrayEntry>, index: Int) =
        StoryViewerActivity.start(requireContext(), list.map { it.user }, index)
}

// ---------------- Search ----------------
class SearchFragment : Fragment(R.layout.fragment_search) {
    private var job: Job? = null
    private val adapter = UserAdapter { UserActivity.start(requireContext(), it.username) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val input = view.findViewById<EditText>(R.id.query)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val empty = view.findViewById<TextView>(R.id.empty)
        view.findViewById<RecyclerView>(R.id.results).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SearchFragment.adapter
        }
        input.doAfterTextChanged { text ->
            job?.cancel()
            val q = text?.toString()?.trim().orEmpty()
            if (q.length < 2) {
                adapter.submit(emptyList())
                empty.text = "Type at least 2 letters to search"
                empty.isVisible = true
                progress.isVisible = false
                return@doAfterTextChanged
            }
            job = viewLifecycleOwner.lifecycleScope.launch {
                delay(450)
                progress.isVisible = true
                try {
                    val users = Ig.search(q)
                    adapter.submit(users)
                    empty.text = "No accounts found"
                    empty.isVisible = users.isEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    empty.text = e.message
                    empty.isVisible = true
                } finally {
                    progress.isVisible = false
                }
            }
        }
    }
}

// ---------------- Favorites ----------------
class FavoritesFragment : Fragment(R.layout.fragment_favorites) {
    private val adapter = UserAdapter { UserActivity.start(requireContext(), it.username) }
    private var empty: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        empty = view.findViewById(R.id.empty)
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FavoritesFragment.adapter
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onHiddenChanged(hidden: Boolean) { if (!hidden) refresh() }

    private fun refresh() {
        val ctx = context ?: return
        val favs = Store(ctx).favorites()
        adapter.submit(favs)
        empty?.isVisible = favs.isEmpty()
    }
}

// ---------------- Settings ----------------
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val name = view.findViewById<TextView>(R.id.myName)
        val avatar = view.findViewById<ImageView>(R.id.myAvatar)
        viewLifecycleOwner.lifecycleScope.launch {
            val me = Ig.me()
            if (me != null) {
                name.text = me.username
                avatar.loadCircle(me.pic)
            } else {
                name.text = "Not logged in"
            }
        }
        view.findViewById<View>(R.id.rowBrowse).setOnClickListener {
            startActivity(Intent(requireContext(), WebActivity::class.java))
        }
        val logout = View.OnClickListener { confirmLogout() }
        view.findViewById<View>(R.id.rowLogout).setOnClickListener(logout)
        view.findViewById<View>(R.id.accountCard).setOnClickListener(logout)
    }

    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setTitle("Log out?")
            .setMessage("You can log in again with the same or a different Instagram account.")
            .setPositiveButton("Log out") { _, _ ->
                Ig.logout()
                startActivity(Intent(requireContext(), LoginActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
