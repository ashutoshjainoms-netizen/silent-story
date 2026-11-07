package com.ashu.ghostview

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class IgException(msg: String) : Exception(msg)

data class IgUser(val id: String, val username: String, val fullName: String, val pic: String)
data class TrayEntry(val user: IgUser, val expiringAt: Long, val latest: Long)
data class StoryItem(val pk: String, val url: String, val isVideo: Boolean, val takenAt: Long)

/**
 * Talks to Instagram's web API with the session cookies from the login WebView.
 * These are read-only calls: nothing here ever tells Instagram a story was seen.
 */
object Ig {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private const val BASE = "https://www.instagram.com"
    private const val APP_ID = "936619743392459"

    fun cookies(): String = CookieManager.getInstance().getCookie(BASE) ?: ""
    private fun cookie(name: String): String? =
        cookies().split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    fun isLoggedIn() = cookie("sessionid")?.isNotEmpty() == true
    fun myId(): String? = cookie("ds_user_id")

    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    // Instagram's iPhone app identity: its API host (i.instagram.com) accepts the same session cookies.
    private const val APP_UA = "Instagram 309.1.0.41.113 (iPhone13,2; iOS 16_6; en_US; en-US; scale=3.00; 1170x2532; 541635890)"

    private class Reply(val code: Int, val text: String, val finalUrl: String)

    private fun fetch(url: String, mobile: Boolean): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", if (mobile) APP_UA else UA)
            c.setRequestProperty("Cookie", cookies())
            c.setRequestProperty("X-IG-App-ID", if (mobile) "124024574287414" else APP_ID)
            c.setRequestProperty("X-CSRFToken", cookie("csrftoken") ?: "")
            c.setRequestProperty("X-ASBD-ID", "129477")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (!mobile) {
                c.setRequestProperty("Referer", "$BASE/")
                c.setRequestProperty("Origin", BASE)
                c.setRequestProperty("Sec-Fetch-Site", "same-origin")
                c.setRequestProperty("Sec-Fetch-Mode", "cors")
                c.setRequestProperty("Sec-Fetch-Dest", "empty")
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Reply(code, text, c.getHeaderField("Location") ?: url)
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

    /** Tries the website API first, then the mobile app API. */
    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val attempts = listOf(BASE + path to false, "https://i.instagram.com$path" to true)
        var last: Reply? = null
        for ((url, mobile) in attempts) {
            val r = try { fetch(url, mobile) } catch (e: Exception) { Reply(-1, e.message ?: "network error", url) }
            parse(r)?.let { j ->
                if (j.optString("status") == "fail" && j.optString("message") == "login_required") {
                    throw IgException("Session expired. Log out and log in again from Settings.")
                }
                return@withContext j
            }
            last = r
        }
        val r = last!!
        when (r.code) {
            401, 403 -> throw IgException("Instagram refused (code ${r.code}). Log out and log in again.")
            429 -> throw IgException("Instagram is rate-limiting. Wait a few minutes.")
            -1 -> throw IgException("No connection: ${r.text}")
        }
        val snippet = r.text.replace(Regex("\\s+"), " ").take(90)
        throw IgException("Instagram error ${r.code} on ${path.substringBefore('?')}\n→ ${r.finalUrl.take(60)}\n$snippet")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun userFrom(j: JSONObject) = IgUser(
        id = j.optString("pk").ifEmpty { j.optString("id") },
        username = j.optString("username"),
        fullName = j.optString("full_name"),
        pic = j.optString("profile_pic_url_hd").ifEmpty { j.optString("profile_pic_url") }
    )

    private fun best(arr: JSONArray?): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .maxByOrNull { it.optInt("width") * it.optInt("height") }
    }

    private fun itemFrom(j: JSONObject): StoryItem? {
        val video = best(j.optJSONArray("video_versions"))
        if (video != null) return StoryItem(j.optString("pk"), video.optString("url"), true, j.optLong("taken_at"))
        val img = best(j.optJSONObject("image_versions2")?.optJSONArray("candidates")) ?: return null
        return StoryItem(j.optString("pk"), img.optString("url"), false, j.optLong("taken_at"))
    }

    /** Everyone you follow who currently has a story. */
    suspend fun tray(): List<TrayEntry> {
        val arr = get("/api/v1/feed/reels_tray/").optJSONArray("tray") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.getJSONObject(i)
            val u = t.optJSONObject("user") ?: return@mapNotNull null
            if (t.optString("id").startsWith("highlight")) return@mapNotNull null
            TrayEntry(userFrom(u), t.optLong("expiring_at"), t.optLong("latest_reel_media"))
        }
    }

    suspend fun stories(userId: String): List<StoryItem> {
        val j = get("/api/v1/feed/reels_media/?reel_ids=${enc(userId)}")
        val reel = j.optJSONObject("reels")?.optJSONObject(userId)
            ?: j.optJSONArray("reels_media")?.optJSONObject(0)
            ?: return emptyList()
        val items = reel.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { itemFrom(items.getJSONObject(it)) }
    }

    suspend fun userByName(username: String): IgUser {
        val u = get("/api/v1/users/web_profile_info/?username=${enc(username)}")
            .optJSONObject("data")?.optJSONObject("user") ?: throw IgException("Could not find @$username")
        return userFrom(u)
    }

    suspend fun userById(id: String): IgUser {
        val u = get("/api/v1/users/$id/info/").optJSONObject("user") ?: throw IgException("User not found")
        val hd = u.optJSONObject("hd_profile_pic_url_info")?.optString("url")
        val base = userFrom(u)
        return if (!hd.isNullOrEmpty()) base.copy(pic = hd) else base
    }

    /** Full-resolution profile photo URL. */
    suspend fun hdPic(user: IgUser): String =
        try { userById(user.id).pic } catch (e: Exception) { user.pic }

    suspend fun me(): IgUser? = myId()?.let { try { userById(it) } catch (e: Exception) { null } }

    suspend fun search(query: String): List<IgUser> {
        val arr = get("/api/v1/web/search/topsearch/?context=blended&query=${enc(query)}")
            .optJSONArray("users") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optJSONObject("user")?.let(::userFrom) }
    }
}
