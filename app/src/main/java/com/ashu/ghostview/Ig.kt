package com.ashu.ghostview

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class IgException(msg: String) : Exception(msg)

data class IgUser(val id: String, val username: String, val fullName: String, val pic: String)
data class TrayEntry(val user: IgUser, val expiringAt: Long, val latest: Long)
data class StoryItem(val pk: String, val url: String, val isVideo: Boolean, val takenAt: Long)

/**
 * Talks to Instagram with the session cookies from the login screen.
 *
 * ANONYMITY: every call here is a read-only GET. Instagram only learns that you
 * watched a story when a client POSTs to its "seen" endpoint (media/seen). This app
 * never makes that call, so story owners never see you in their viewer list.
 *
 * Each request tries several Instagram servers/endpoints and uses the first one that
 * actually returns data, so a change on one of them doesn't break the app.
 */
object Ig {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private const val APP_UA = "Instagram 309.1.0.41.113 (iPhone13,2; iOS 16_6; en_US; en-US; " +
            "scale=3.00; 1170x2532; 541635890)"
    private const val WWW = "https://www.instagram.com"
    private const val MOB = "https://i.instagram.com"
    private const val WEB_APP_ID = "936619743392459"
    private const val IOS_APP_ID = "124024574287414"

    /** Story items that came with the tray, keyed by user id (saves a request). */
    private val trayItems = ConcurrentHashMap<String, List<StoryItem>>()

    /** Short log of which server answered each request (shown by the connection test). */
    private val log = ArrayDeque<String>()
    private fun note(s: String) = synchronized(log) { log.addLast(s); while (log.size > 40) log.removeFirst() }

    // ---------------- session ----------------
    fun cookies(): String = CookieManager.getInstance().getCookie(WWW) ?: ""
    private fun cookie(name: String): String? =
        cookies().split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    fun isLoggedIn() = cookie("sessionid")?.isNotEmpty() == true
    fun myId(): String? = cookie("ds_user_id")

    fun logout() {
        trayItems.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    // ---------------- HTTP ----------------
    private class Reply(val code: Int, val text: String, val location: String?)

    private fun fetch(url: String): Reply {
        val mobile = url.startsWith(MOB)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", if (mobile) APP_UA else UA)
            c.setRequestProperty("Cookie", cookies())
            c.setRequestProperty("X-IG-App-ID", if (mobile) IOS_APP_ID else WEB_APP_ID)
            c.setRequestProperty("X-CSRFToken", cookie("csrftoken") ?: "")
            c.setRequestProperty("X-ASBD-ID", "129477")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (!mobile) {
                c.setRequestProperty("Referer", "$WWW/")
                c.setRequestProperty("Origin", WWW)
                c.setRequestProperty("Sec-Fetch-Site", "same-origin")
                c.setRequestProperty("Sec-Fetch-Mode", "cors")
                c.setRequestProperty("Sec-Fetch-Dest", "empty")
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Reply(code, text, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    private fun parse(r: Reply): JSONObject? {
        if (r.code !in 200..299) return null
        val t = r.text.trim().removePrefix("for (;;);").trim()
        if (!t.startsWith("{")) return null
        return try { JSONObject(t) } catch (e: Exception) { null }
    }

    private fun describe(r: Reply): String = when {
        r.code == -1 -> "no connection (${r.text.take(60)})"
        r.code in 300..399 -> "redirect to ${r.location?.take(60)}"
        r.code in 200..299 -> "not data: ${r.text.replace(Regex("\\s+"), " ").take(60)}"
        else -> "HTTP ${r.code} ${r.text.replace(Regex("\\s+"), " ").take(60)}"
    }

    /**
     * Tries each URL in order. Returns the first JSON reply that [hasData] accepts.
     * If replies were valid but empty, returns the last valid one (genuinely no data).
     */
    private suspend fun first(
        label: String,
        urls: List<String>,
        hasData: (JSONObject) -> Boolean = { true }
    ): JSONObject = withContext(Dispatchers.IO) {
        var emptyButValid: JSONObject? = null
        val problems = mutableListOf<String>()
        for (url in urls) {
            val host = if (url.startsWith(MOB)) "app" else "web"
            val r = try { fetch(url) } catch (e: Exception) { Reply(-1, e.message ?: "error", null) }
            val j = parse(r)
            if (j == null) {
                problems += "$host: ${describe(r)}"
                continue
            }
            if (j.optString("message") == "login_required" || j.optBoolean("require_login")) {
                problems += "$host: login_required"
                continue
            }
            if (hasData(j)) {
                note("$label ✓ $host")
                return@withContext j
            }
            problems += "$host: empty"
            emptyButValid = j
        }
        emptyButValid?.let {
            note("$label – empty (${problems.joinToString("; ")})")
            return@withContext it
        }
        note("$label ✗ ${problems.joinToString("; ")}")
        val text = problems.joinToString("\n")
        throw IgException(
            when {
                problems.any { it.contains("login_required") || it.contains("HTTP 401") || it.contains("accounts/login") } ->
                    "Instagram says you're logged out. Go to Settings → Log out, then log in again."
                problems.any { it.contains("HTTP 429") } -> "Instagram is rate-limiting. Wait a few minutes."
                else -> "Couldn't load $label.\n$text"
            }
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- parsing ----------------
    private fun userFrom(j: JSONObject) = IgUser(
        id = j.optString("pk_id").ifEmpty { j.optString("pk") }.ifEmpty { j.optString("id") },
        username = j.optString("username"),
        fullName = j.optString("full_name"),
        pic = j.optJSONObject("hd_profile_pic_url_info")?.optString("url").orEmpty()
            .ifEmpty { j.optString("profile_pic_url_hd") }
            .ifEmpty { j.optString("profile_pic_url") }
    )

    private fun best(arr: JSONArray?): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .maxByOrNull { it.optInt("width") * it.optInt("height") }
    }

    private fun itemFrom(j: JSONObject): StoryItem? {
        val pk = j.optString("pk").ifEmpty { j.optString("id").substringBefore("_") }
        val taken = j.optLong("taken_at")
        val video = best(j.optJSONArray("video_versions"))
        if (video != null && video.optString("url").isNotEmpty()) return StoryItem(pk, video.optString("url"), true, taken)
        val img = best(j.optJSONObject("image_versions2")?.optJSONArray("candidates")) ?: return null
        return StoryItem(pk, img.optString("url"), false, taken)
    }

    private fun itemsOf(arr: JSONArray?): List<StoryItem> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { itemFrom(arr.getJSONObject(it)) }

    /** Finds the reel object for [userId] in any of Instagram's response shapes. */
    private fun reelIn(j: JSONObject, userId: String): JSONObject? {
        j.optJSONObject("reels")?.let { reels ->
            reels.optJSONObject(userId)?.let { return it }
            val keys = reels.keys()
            if (keys.hasNext()) return reels.optJSONObject(keys.next())
        }
        j.optJSONArray("reels_media")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val owner = r.optJSONObject("user")?.let(::userFrom)?.id ?: r.optString("id")
                if (owner == userId) return r
            }
            if (arr.length() > 0) return arr.getJSONObject(0)
        }
        j.optJSONObject("reel")?.let { return it }
        return null
    }

    // ---------------- public API ----------------

    /** Everyone you follow who currently has a story. */
    suspend fun tray(): List<TrayEntry> {
        val j = first("story list", listOf(
            "$MOB/api/v1/feed/reels_tray/",
            "$WWW/api/v1/feed/reels_tray/"
        )) { (it.optJSONArray("tray")?.length() ?: 0) > 0 }
        val arr = j.optJSONArray("tray") ?: return emptyList()
        trayItems.clear()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.getJSONObject(i)
            val u = t.optJSONObject("user") ?: return@mapNotNull null
            if (t.optString("id").startsWith("highlight")) return@mapNotNull null
            val user = userFrom(u)
            if (user.id.isEmpty()) return@mapNotNull null
            // Keep items Instagram already sent, but only if the set is complete.
            val items = itemsOf(t.optJSONArray("items"))
            val total = t.optInt("media_count", 0)
            if (items.isNotEmpty() && (total == 0 || items.size >= total)) trayItems[user.id] = items
            TrayEntry(user, t.optLong("expiring_at"), t.optLong("latest_reel_media"))
        }
    }

    /** All current stories of one user. */
    suspend fun stories(userId: String): List<StoryItem> {
        trayItems[userId]?.let { if (it.isNotEmpty()) return it }
        val id = enc(userId)
        val j = first("stories", listOf(
            "$MOB/api/v1/feed/reels_media/?reel_ids=$id",
            "$MOB/api/v1/feed/user/$id/story/",
            "$MOB/api/v1/feed/user/$id/reel_media/",
            "$WWW/api/v1/feed/reels_media/?reel_ids=$id"
        )) { (reelIn(it, userId)?.optJSONArray("items")?.length() ?: 0) > 0 }
        return itemsOf(reelIn(j, userId)?.optJSONArray("items"))
    }

    suspend fun userByName(username: String): IgUser {
        val u = enc(username)
        val j = first("profile", listOf(
            "$MOB/api/v1/users/web_profile_info/?username=$u",
            "$WWW/api/v1/users/web_profile_info/?username=$u",
            "$MOB/api/v1/users/$u/usernameinfo/"
        )) { it.optJSONObject("data")?.optJSONObject("user") != null || it.optJSONObject("user") != null }
        val user = j.optJSONObject("data")?.optJSONObject("user") ?: j.optJSONObject("user")
            ?: throw IgException("Could not find @$username")
        return userFrom(user)
    }

    suspend fun userById(id: String): IgUser {
        val j = first("profile", listOf(
            "$MOB/api/v1/users/${enc(id)}/info/",
            "$WWW/api/v1/users/${enc(id)}/info/"
        )) { it.optJSONObject("user") != null }
        return userFrom(j.optJSONObject("user") ?: throw IgException("User not found"))
    }

    /** Full-resolution profile photo URL. */
    suspend fun hdPic(user: IgUser): String =
        try { userById(user.id).pic.ifEmpty { user.pic } } catch (e: Exception) { user.pic }

    suspend fun me(): IgUser? = myId()?.let { try { userById(it) } catch (e: Exception) { null } }

    suspend fun search(query: String): List<IgUser> {
        val q = enc(query)
        val j = first("search", listOf(
            "$MOB/api/v1/users/search/?q=$q&count=30",
            "$WWW/api/v1/web/search/topsearch/?context=blended&query=$q",
            "$MOB/api/v1/fbsearch/topsearch_flat/?query=$q&context=blended"
        )) { (it.optJSONArray("users")?.length() ?: 0) > 0 || (it.optJSONArray("list")?.length() ?: 0) > 0 }
        val out = mutableListOf<IgUser>()
        j.optJSONArray("users")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += userFrom(o.optJSONObject("user") ?: o)   // both {user:{..}} and flat user shapes
            }
        }
        j.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) arr.getJSONObject(i).optJSONObject("user")?.let { out += userFrom(it) }
        }
        return out.filter { it.username.isNotEmpty() }.distinctBy { it.username }
    }

    /** Runs every feature once and reports what worked. Used by Settings → Connection test. */
    suspend fun selfTest(): String {
        val sb = StringBuilder()
        fun line(ok: Boolean, name: String, detail: String) = sb.append(if (ok) "✅ " else "❌ ").append(name).append(": ").append(detail).append("\n\n")
        line(isLoggedIn(), "Logged in", if (isLoggedIn()) "session found" else "no session, log in again")
        try { val m = me(); line(m != null, "Your account", m?.username ?: "couldn't read") } catch (e: Exception) { line(false, "Your account", e.message ?: "") }
        var tray: List<TrayEntry> = emptyList()
        try { tray = tray(); line(true, "Story list", "${tray.size} people with stories") } catch (e: Exception) { line(false, "Story list", e.message ?: "") }
        val target = tray.firstOrNull()
        if (target != null) {
            trayItems.remove(target.user.id)   // force a real network fetch for the test
            try {
                val s = stories(target.user.id)
                line(s.isNotEmpty(), "Open a story", "@${target.user.username}: ${s.size} stories (${s.count { it.isVideo }} videos)")
            } catch (e: Exception) { line(false, "Open a story", e.message ?: "") }
            try { val p = hdPic(target.user); line(p.isNotEmpty(), "HD profile photo", if (p.isNotEmpty()) "ok" else "missing") } catch (e: Exception) { line(false, "HD profile photo", e.message ?: "") }
        }
        try { val r = search("instagram"); line(r.isNotEmpty(), "Search", "${r.size} results") } catch (e: Exception) { line(false, "Search", e.message ?: "") }
        try { val u = userByName("instagram"); line(u.id.isNotEmpty(), "Profile lookup", "@${u.username}") } catch (e: Exception) { line(false, "Profile lookup", e.message ?: "") }
        sb.append("Anonymous: this app never sends Instagram's \"seen\" signal.\n\n— log —\n")
        synchronized(log) { log.takeLast(12).forEach { sb.append(it).append('\n') } }
        return sb.toString()
    }
}
