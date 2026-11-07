package com.ashu.ghostview

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local-only data: favorites and which stories you've watched in GhostView. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("ghost_store", Context.MODE_PRIVATE)

    fun seenUpTo(userId: String): Long = p.getLong("seen_$userId", 0)
    fun markSeen(userId: String, takenAt: Long) {
        if (takenAt > seenUpTo(userId)) p.edit().putLong("seen_$userId", takenAt).apply()
    }

    fun favorites(): List<IgUser> {
        val arr = JSONArray(p.getString("favorites", "[]"))
        return (0 until arr.length()).map {
            val j = arr.getJSONObject(it)
            IgUser(j.optString("id"), j.optString("username"), j.optString("fullName"), j.optString("pic"))
        }
    }

    fun isFavorite(username: String) = favorites().any { it.username == username }

    fun toggleFavorite(u: IgUser): Boolean {
        val list = favorites().toMutableList()
        val existing = list.indexOfFirst { it.username == u.username }
        val nowFav = existing < 0
        if (nowFav) list.add(0, u) else list.removeAt(existing)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("username", it.username)
                .put("fullName", it.fullName).put("pic", it.pic))
        }
        p.edit().putString("favorites", arr.toString()).apply()
        return nowFav
    }
}
