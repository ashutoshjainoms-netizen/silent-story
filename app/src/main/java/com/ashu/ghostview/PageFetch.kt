package com.ashu.ghostview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes requests exactly like instagram.com does in a browser: from inside an invisible
 * page on www.instagram.com, so Instagram receives its own cookies and browser headers.
 * The page loaded is robots.txt (plain text), so none of Instagram's own scripts run
 * and nothing can ever send a "seen" signal.
 */
object PageFetch {
    private var web: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

    class Bridge {
        @JavascriptInterface
        fun done(id: String, code: Int, body: String) {
            pending.remove(id)?.complete(code to body)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun init(ctx: Context) {
        if (web != null) return
        val w = WebView(ctx.applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = Ig.UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "GVFetch")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (url?.startsWith("https://www.instagram.com") == true) ready.complete(Unit)
            }
        }
        w.loadUrl("https://www.instagram.com/robots.txt")
        web = w
    }

    /** Reload after login/logout so the page uses the current session. */
    fun reset() {
        val w = web ?: return
        ready = CompletableDeferred()
        w.loadUrl("https://www.instagram.com/robots.txt")
    }

    /** GET [path] (e.g. "/api/v1/feed/reels_tray/") from the page. Returns (status, body). */
    suspend fun get(path: String): Pair<Int, String> = withContext(Dispatchers.Main) {
        if (web == null) Ig.appContext()?.let { init(it) }   // app restarted on another screen
        val w = web ?: return@withContext (-1 to "page not started")
        try {
            withTimeout(12000) { ready.await() }
        } catch (e: Exception) {
            return@withContext (-1 to "page didn't load")
        }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<Pair<Int, String>>()
        pending[id] = result
        val js = """
            (function(){
              var m = document.cookie.match(/(?:^|;\s*)csrftoken=([^;]+)/);
              var claim = '0';
              try { claim = sessionStorage.getItem('www-claim-v2') || '0'; } catch(e) {}
              fetch(${JSONObject.quote(path)}, {
                credentials: 'include',
                headers: {
                  'X-IG-App-ID': '936619743392459',
                  'X-CSRFToken': m ? m[1] : '',
                  'X-IG-WWW-Claim': claim,
                  'X-ASBD-ID': '129477',
                  'X-Requested-With': 'XMLHttpRequest',
                  'Accept': '*/*'
                }
              }).then(function(r){
                return r.text().then(function(t){ GVFetch.done('$id', r.status, t); });
              }).catch(function(e){ GVFetch.done('$id', -1, String(e)); });
            })();
        """.trimIndent()
        w.evaluateJavascript(js, null)
        try {
            withTimeout(20000) { result.await() }
        } catch (e: Exception) {
            pending.remove(id)
            -1 to "timeout"
        }
    }
}
