package com.ashu.ghostview

import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
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
    /** Browser identity. Replaced at start-up with this phone's real Chrome version (see init). */
    @Volatile var UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private const val APP_UA = "Instagram 309.1.0.41.113 (iPhone13,2; iOS 16_6; en_US; en-US; " +
            "scale=3.00; 1170x2532; 541635890)"
    private const val WWW = "https://www.instagram.com"
    private const val MOB = "https://i.instagram.com"
    /** Marker: run this path through the invisible instagram.com page (most reliable). */
    private const val PAGE = "page:"
    private const val WEB_APP_ID = "936619743392459"
    private const val IOS_APP_ID = "124024574287414"

    /** Story items that came with the tray, keyed by user id (saves a request). */
    private val trayItems = ConcurrentHashMap<String, List<StoryItem>>()
    /** Incomplete item lists from the tray: used only if every other way fails. */
    private val trayPartial = ConcurrentHashMap<String, List<StoryItem>>()
    /** Tray reel id per user (normally the same as the user id). */
    private val reelIds = ConcurrentHashMap<String, String>()

    /** Short log of which server answered each request (shown by the connection test). */
    private val log = ArrayDeque<String>()
    private fun note(s: String) = synchronized(log) { log.addLast(s); while (log.size > 40) log.removeFirst() }
    fun lastNote(): String = synchronized(log) { log.lastOrNull() ?: "" }

    // ---------------- session ----------------
    fun cookies(): String = CookieManager.getInstance().getCookie(WWW) ?: ""
    private fun cookie(name: String): String? =
        cookies().split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    private var app: android.content.Context? = null
    private val KEEP_NAMES = listOf("sessionid", "ds_user_id", "csrftoken", "mid", "ig_did", "rur", "datr")

    fun init(ctx: android.content.Context) {
        app = ctx.applicationContext
        UA = realBrowserUa(ctx) ?: UA
        restoreSession()
    }

    /**
     * The phone's own WebView identity, cleaned to look like normal Chrome, so the version
     * Instagram reads from the user agent matches what the browser engine itself reports.
     */
    private fun realBrowserUa(ctx: android.content.Context): String? = try {
        android.webkit.WebSettings.getDefaultUserAgent(ctx)
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
            .takeIf { it.contains("Chrome/") }
    } catch (_: Exception) { null }

    fun appContext(): android.content.Context? = app

    private fun prefs() = app?.getSharedPreferences("ghost_session", android.content.Context.MODE_PRIVATE)

    /** Saves the login cookies so the session survives anything that clears them. */
    fun saveSession() {
        val sid = cookie("sessionid")
        if (sid.isNullOrEmpty()) return
        val e = prefs()?.edit() ?: return
        KEEP_NAMES.forEach { n -> cookie(n)?.takeIf { it.isNotEmpty() }?.let { e.putString(n, it) } }
        e.apply()
    }

    /** If the login cookie went missing, put the saved one back. */
    fun restoreSession() {
        if (!cookie("sessionid").isNullOrEmpty()) { saveSession(); return }
        val p = prefs() ?: return
        if (p.getString("sessionid", null).isNullOrEmpty()) return
        val cm = CookieManager.getInstance()
        KEEP_NAMES.forEach { n ->
            p.getString(n, null)?.let { v ->
                cm.setCookie(WWW, "$n=$v; Domain=.instagram.com; Path=/; Max-Age=315360000; Secure")
            }
        }
        cm.flush()
    }

    /** Raw check without restoring anything (used while logging in). Looks at every Instagram host. */
    fun hasSessionCookie(): Boolean {
        val cm = CookieManager.getInstance()
        for (host in listOf(WWW, "https://instagram.com", MOB)) {
            val c = cm.getCookie(host) ?: continue
            val sid = c.split(";").map { it.trim() }.firstOrNull { it.startsWith("sessionid=") }?.substringAfter("=")
            if (!sid.isNullOrEmpty()) {
                if (host != WWW) cm.setCookie(WWW, "sessionid=$sid; Domain=.instagram.com; Path=/; Max-Age=31536000; Secure")
                return true
            }
        }
        return false
    }

    /** True for Instagram's web logout links. Only Settings -> Log out may end the login. */
    fun isLogoutUrl(u: android.net.Uri?): Boolean = u?.path?.contains("/accounts/logout") == true

    /** Forget the saved session (it expired) so it can't interfere with a fresh login. */
    fun forgetSaved() { prefs()?.edit()?.clear()?.apply() }

    fun isLoggedIn(): Boolean {
        if (cookie("sessionid").isNullOrEmpty()) restoreSession()
        return cookie("sessionid")?.isNotEmpty() == true
    }

    fun myId(): String? = cookie("ds_user_id") ?: prefs()?.getString("ds_user_id", null)

    /** Only called from Settings → Log out. */
    fun logout() {
        trayItems.clear()
        prefs()?.edit()?.clear()?.apply()
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
        restoreSession()
        var emptyButValid: JSONObject? = null
        val problems = mutableListOf<String>()
        for (url in urls) {
            val host = when {
                url.startsWith(PAGE) -> "page"
                url.startsWith(MOB) -> "app"
                else -> "web"
            }
            val r = try {
                if (url.startsWith(PAGE)) {
                    val (code, body) = PageFetch.get(url.removePrefix(PAGE))
                    Reply(code, body, null)
                } else fetch(url)
            } catch (e: Exception) { Reply(-1, e.message ?: "error", null) }
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
    /** Reads a numeric id without scientific notation or decimals (e.g. 1.2E10 -> 12000000000). */
    private fun idOf(j: JSONObject?, vararg keys: String): String {
        if (j == null) return ""
        for (k in keys) {
            val v = j.opt(k) ?: continue
            val raw = v.toString().trim().substringBefore("_")
            if (raw.isEmpty() || raw == "null") continue
            if (raw.all { it.isDigit() }) return raw
            try { return java.math.BigDecimal(raw).toBigInteger().toString() } catch (e: Exception) {}
        }
        return ""
    }

    private fun userFrom(j: JSONObject) = IgUser(
        id = idOf(j, "pk_id", "pk", "id"),
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

    private fun bestRes(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .maxByOrNull { it.optInt("config_width") * it.optInt("config_height") }?.optString("src")
    }

    private fun itemFrom(j: JSONObject): StoryItem? {
        val pk = idOf(j, "pk", "id")
        val taken = if (j.has("taken_at")) j.optLong("taken_at") else j.optLong("taken_at_timestamp")
        // GraphQL shape (website internal API)
        if (j.has("display_resources") || j.has("video_resources") || j.has("display_url")) {
            if (j.optBoolean("is_video")) {
                val v = bestRes(j.optJSONArray("video_resources"))
                if (!v.isNullOrEmpty()) return StoryItem(pk, v, true, taken)
            }
            val img = bestRes(j.optJSONArray("display_resources")) ?: j.optString("display_url")
            return if (img.isNullOrEmpty()) null else StoryItem(pk, img, false, taken)
        }
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
        j.optJSONObject("data")?.let { d ->
            if (d !== j) reelIn(d, userId)?.let { return it }
        }
        return null
    }

    // ---------------- public API ----------------

    /** Everyone you follow who currently has a story. */
    suspend fun tray(): List<TrayEntry> {
        val j = first("story list", listOf(
            "${PAGE}/api/v1/feed/reels_tray/",
            "$WWW/api/v1/feed/reels_tray/"
        )) { (it.optJSONArray("tray")?.length() ?: 0) > 0 }
        val arr = j.optJSONArray("tray") ?: return emptyList()
        trayItems.clear(); trayPartial.clear(); reelIds.clear()
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
            else if (items.isNotEmpty()) trayPartial[user.id] = items
            idOf(t, "id").takeIf { it.isNotEmpty() && it != user.id }?.let { reelIds[user.id] = it }
            TrayEntry(user, t.optLong("expiring_at"), t.optLong("latest_reel_media"))
        }
    }

    /**
     * Stories that already came with the story list. Returns empty when none are cached;
     * the viewer then loads them through Instagram's own story page (WebStoryLoader),
     * which is what a normal browser does, so no extra API requests are made.
     */
    fun stories(userId: String): List<StoryItem> =
        trayItems[userId]?.takeIf { it.isNotEmpty() } ?: emptyList()

    suspend fun userByName(username: String): IgUser {
        val u = enc(username)
        val j = first("profile", listOf(
            "${PAGE}/api/v1/users/web_profile_info/?username=$u",
            "$WWW/api/v1/users/web_profile_info/?username=$u",
        )) { it.optJSONObject("data")?.optJSONObject("user") != null || it.optJSONObject("user") != null }
        val user = j.optJSONObject("data")?.optJSONObject("user") ?: j.optJSONObject("user")
            ?: throw IgException("Could not find @$username")
        return userFrom(user)
    }

    suspend fun userById(id: String): IgUser {
        val j = first("profile", listOf(
            "${PAGE}/api/v1/users/${enc(id)}/info/",
            "$WWW/api/v1/users/${enc(id)}/info/"
        )) { it.optJSONObject("user") != null }
        return userFrom(j.optJSONObject("user") ?: throw IgException("User not found"))
    }

    /** Full-resolution profile photo URL. */
    suspend fun hdPic(user: IgUser): String =
        try { userById(user.id).pic.ifEmpty { user.pic } } catch (e: Exception) { user.pic }

    suspend fun me(): IgUser? {
        // Web endpoint that returns the logged-in account (works with the web session).
        try {
            val j = first("me", listOf(
                "${PAGE}/api/v1/accounts/current_user/?edit=false",
                "$WWW/api/v1/accounts/current_user/?edit=false",
            )) { it.optJSONObject("user") != null }
            j.optJSONObject("user")?.let { return userFrom(it) }
        } catch (e: Exception) { /* fall back below */ }
        return myId()?.let { try { userById(it) } catch (e: Exception) { null } }
    }

    suspend fun search(query: String): List<IgUser> {
        val q = enc(query)
        val out = mutableListOf<IgUser>()
        // Try several search endpoints; use whichever still returns results.
        try {
            val j = first("search", listOf(
                "${PAGE}/api/v1/web/search/topsearch/?context=blended&query=$q",
                "${PAGE}/web/search/topsearch/?context=blended&query=$q",
                "${PAGE}/api/v1/fbsearch/account_serp/?query=$q&count=30",
                "${MOB}/api/v1/users/search/?q=$q&count=30",
                "$WWW/api/v1/web/search/topsearch/?context=blended&query=$q",
            )) { (it.optJSONArray("users")?.length() ?: 0) > 0 || (it.optJSONArray("list")?.length() ?: 0) > 0 }
            j.optJSONArray("users")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += userFrom(o.optJSONObject("user") ?: o)   // both {user:{..}} and flat user shapes
                }
            }
            j.optJSONArray("list")?.let { arr ->
                for (i in 0 until arr.length()) arr.getJSONObject(i).optJSONObject("user")?.let { out += userFrom(it) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* fall through */ }

        // If the search endpoints are unavailable, fall back to an exact-username lookup
        // (works when you type the full handle), reusing the profile endpoint that works.
        if (out.isEmpty()) {
            try { out += userByName(query) } catch (e: Exception) {}
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
                        try {
                val s = stories(target.user.id)
                sb.append(if (s.isNotEmpty()) "✅ " else "ℹ️ ").append("Story (quick method): ")
                    .append(if (s.isNotEmpty()) "${s.size} stories" else "not available, the viewer uses Instagram's story page instead (normal)").append("\n\n")
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
