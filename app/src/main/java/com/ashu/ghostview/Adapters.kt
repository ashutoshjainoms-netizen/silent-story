package com.ashu.ghostview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun ImageView.loadCircle(url: String?) = load(url) {
    crossfade(true)
    transformations(CircleCropTransformation())
}

fun countdown(secondsLeft: Long): String {
    if (secondsLeft <= 0) return "expired"
    val h = secondsLeft / 3600
    val m = (secondsLeft % 3600) / 60
    val s = secondsLeft % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun nowSec() = System.currentTimeMillis() / 1000

/** Story circles. hurry = horizontal "Hurry Up!" row with countdown timers. */
class StoryAdapter(
    private val hurry: Boolean,
    private val onClick: (List<TrayEntry>, Int) -> Unit
) : RecyclerView.Adapter<StoryAdapter.VH>() {

    private var items = listOf<TrayEntry>()

    fun submit(list: List<TrayEntry>) {
        items = list
        notifyDataSetChanged()
    }

    fun tick() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, "tick")
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ring: View = v.findViewById(R.id.ring)
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val badge: TextView = v.findViewById(R.id.badge)
        val name: TextView = v.findViewById(R.id.name)
        val timer: TextView = v.findViewById(R.id.timer)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_story, parent, false)
        v.layoutParams = RecyclerView.LayoutParams(
            if (hurry) parent.dp(116) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) bindTimer(h, items[position]) else onBindViewHolder(h, position)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val unseen = e.latest > Store(h.itemView.context).seenUpTo(e.user.id)
        h.ring.setBackgroundResource(if (unseen) R.drawable.ring_unseen else R.drawable.ring_seen)
        h.avatar.loadCircle(e.user.pic)
        h.name.text = e.user.username
        h.badge.isVisible = unseen && nowSec() - e.latest < 3600
        bindTimer(h, e)
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items, pos)
        }
    }

    private fun bindTimer(h: VH, e: TrayEntry) {
        h.timer.isVisible = hurry
        if (hurry) h.timer.text = countdown(e.expiringAt - nowSec())
    }
}

/** Simple list of accounts (search results, favorites). */
class UserAdapter(private val onClick: (IgUser) -> Unit) : RecyclerView.Adapter<UserAdapter.VH>() {
    private var items = listOf<IgUser>()

    fun submit(list: List<IgUser>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val username: TextView = v.findViewById(R.id.username)
        val fullname: TextView = v.findViewById(R.id.fullname)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val u = items[position]
        h.avatar.loadCircle(u.pic)
        h.username.text = u.username
        h.fullname.text = u.fullName
        h.fullname.isVisible = u.fullName.isNotEmpty()
        h.itemView.setOnClickListener { onClick(u) }
    }
}
