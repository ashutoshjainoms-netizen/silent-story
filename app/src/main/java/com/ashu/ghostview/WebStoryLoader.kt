package com.ashu.ghostview

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * Loads someone's stories exactly as instagram.com does, inside an invisible browser.
 * loader.js blocks every "seen" request, so the owner never sees you.
 */
class WebStoryLoader(activity: Activity, container: ViewGroup) {
    private val web = WebView(activity)
    private val script = activity.assets.open("loader.js").bufferedReader().use { it.readText() }
    private var latest = CompletableDeferred<List<Pair<String, StoryItem>>>()
    private var collected: List<Pair<String, StoryItem>> = emptyList()
    @Volatile private var target = ""
    var lastError = ""
        private set

    inner class Bridge {
        @JavascriptInterface
        fun onItems(json: String) {
            try {
                val arr = JSONArray(json)
                val list = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    o.optString("user") to StoryItem(
                        pk = o.optString("pk"),
                        url = o.optString("url"),
                        isVideo = o.optBoolean("video"),
                        takenAt = o.optLong("takenAt")
                    )
                }
                collected = list
                // Done only once the person we asked for (or unlabeled items) shows up.
                if (list.any { it.first.isEmpty() || it.first.equals(target, ignoreCase = true) }) latest.complete(list)
            } catch (_: Exception) {}
        }
    }

    init {
        @SuppressLint("SetJavaScriptEnabled")
        val s = web.settings
        web.asPlainBrowser()
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true   // nothing plays, no sound
        s.userAgentString = Ig.UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "GVLoader")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("https://www.instagram.com"))
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (Ig.isLogoutUrl(request.url)) return true
                val sc = request.url.scheme
                return sc != "http" && sc != "https"
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(script, null)
            }
        }
        web.alpha = 0f
        web.isFocusable = false
        // Behind everything else in the viewer, full size so the page lays out normally.
        container.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Returns the current stories of [username], or an empty list. */
    suspend fun load(username: String): List<StoryItem> {
        Ig.restoreSession()   // make sure the login is in place before Instagram's page loads
        lastError = ""
        collected = emptyList()
        target = username
        latest = CompletableDeferred()
        web.loadUrl("https://www.instagram.com/stories/$username/")

        val first = withTimeoutOrNull(25000) { latest.await() }
        if (first == null) {
            lastError = "Instagram's story page didn't return any stories (timed out)."
            stop()
            return emptyList()
        }
        delay(1500)   // let any remaining items arrive
        stop()
        val all = collected
        val mine = all.filter { it.first.equals(username, ignoreCase = true) }.map { it.second }
        val result = (if (mine.isNotEmpty()) mine else all.filter { it.first.isEmpty() }.map { it.second })
        if (result.isEmpty()) lastError = "Stories came back for other accounts only."
        return result.distinctBy { it.pk }.sortedBy { it.takenAt }
    }

    private fun stop() {
        web.stopLoading()
        web.loadUrl("about:blank")
    }

    fun destroy() {
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) {}
    }
}
