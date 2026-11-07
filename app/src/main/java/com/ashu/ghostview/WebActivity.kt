package com.ashu.ghostview

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

class WebActivity : AppCompatActivity() {

    companion object {
        const val TAG = "GhostView"
        const val HOME = "https://www.instagram.com/"
        // Normal mobile Chrome user agent (no "wv" marker) so Instagram serves its regular site.
        const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        val ALLOWED_HOSTS = listOf("instagram.com", "facebook.com", "fbcdn.net", "cdninstagram.com", "fb.com")
    }

    private data class Media(val url: String, val type: String, val name: String, val label: String, val current: Boolean)

    private lateinit var web: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var hiddenCount: TextView
    private lateinit var script: String
    private var docStartScript = false
    private var blocked = 0
    private var autoDownloadOnLoad = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        prefs = getSharedPreferences("ghost", MODE_PRIVATE)
        web = findViewById(R.id.web)
        web.asPlainBrowser()
        hiddenCount = findViewById(R.id.hiddenCount)
        script = assets.open("inject.js").bufferedReader().use { it.readText() }

        val ghostSwitch = findViewById<SwitchCompat>(R.id.ghostSwitch)
        ghostSwitch.isChecked = prefs.getBoolean("ghost", true)
        ghostSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("ghost", on).apply()
            toast(if (on) "Anonymous mode ON" else "Anonymous mode OFF — your views will be visible")
        }
        findViewById<TextView>(R.id.btnHome).setOnClickListener { web.loadUrl(HOME) }
        findViewById<TextView>(R.id.btnReload).setOnClickListener { web.reload() }
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { triggerDownload() }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            val shared = urlFromIntent(intent)
            autoDownloadOnLoad = shared != null
            web.loadUrl(shared ?: intent.getStringExtra("url") ?: HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let {
            autoDownloadOnLoad = true
            web.loadUrl(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = UA
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "GhostBridge")

        // Best option: run our script before Instagram's own code on every page load.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("https://www.instagram.com"))
            docStartScript = true
        }

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return true
                // Block instagram:// and intent:// links that would jump to the official app.
                if (scheme != "http" && scheme != "https") return true
                val host = uri.host ?: return true
                if (ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!docStartScript) view.evaluateJavascript(script, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!docStartScript) view.evaluateJavascript(script, null)
                CookieManager.getInstance().flush()
                if (autoDownloadOnLoad) {
                    autoDownloadOnLoad = false
                    view.postDelayed({ triggerDownload() }, 1500)
                }
            }
        }
    }

    private fun triggerDownload() {
        web.evaluateJavascript(
            "(function(){ if (window.__ghostDownload) { window.__ghostDownload(); return 'ok'; } return 'missing'; })()"
        ) { result ->
            if (result?.contains("missing") == true) toast("Page is still loading — try again in a moment")
        }
    }

    private fun urlFromIntent(i: Intent?): String? {
        val text = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return null
        return Regex("https?://(www\\.)?instagram\\.com/\\S+").find(text)?.value
    }

    private fun showMedia(json: String) {
        val obj = JSONObject(json)
        val title = obj.optString("title", "Download")
        val arr = obj.getJSONArray("items")
        val list = (0 until arr.length()).map {
            val j = arr.getJSONObject(it)
            Media(
                url = j.getString("url"),
                type = j.optString("type", "image"),
                name = j.optString("name", "media_$it"),
                label = j.optString("label", ""),
                current = j.optBoolean("current", false)
            )
        }
        if (list.isEmpty()) { toast("Nothing to download here"); return }
        if (list.size == 1) { download(list); return }

        val labels = list.mapIndexed { i, m ->
            val kind = m.label.ifEmpty { if (m.type == "video") "Video" else "Photo" }
            "${i + 1}. $kind" + if (m.current) "   ← this one" else ""
        }.toTypedArray()

        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, i -> download(listOf(list[i])) }
            .setNeutralButton("All (${list.size})") { _, _ -> download(list) }
            .setNegativeButton("Cancel", null)
        list.firstOrNull { it.current }?.let { cur ->
            builder.setPositiveButton("This one") { _, _ -> download(listOf(cur)) }
        }
        builder.show()
    }

    private fun download(items: List<Media>) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        var ok = 0
        for (m in items) {
            try {
                val isVideo = m.type == "video"
                val file = m.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + if (isVideo) ".mp4" else ".jpg"
                val req = DownloadManager.Request(Uri.parse(m.url))
                    .setTitle(file)
                    .setMimeType(if (isVideo) "video/mp4" else "image/jpeg")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "GhostView/$file")
                    .addRequestHeader("User-Agent", UA)
                    .addRequestHeader("Referer", HOME)
                dm.enqueue(req)
                ok++
            } catch (e: Exception) {
                Log.e(TAG, "download failed: ${m.url}", e)
            }
        }
        toast(if (ok > 0) "Saving $ok file(s) to Pictures/GhostView" else "Download failed")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    inner class Bridge {
        @JavascriptInterface
        fun isGhost(): Boolean = prefs.getBoolean("ghost", true)

        @JavascriptInterface
        fun onBlocked(url: String) {
            Log.d(TAG, "blocked seen request: $url")
            runOnUiThread {
                blocked++
                hiddenCount.text = "hidden: $blocked"
            }
        }

        @JavascriptInterface
        fun onMedia(json: String) = runOnUiThread {
            try { showMedia(json) } catch (e: Exception) { toast("Could not read media: ${e.message}") }
        }

        @JavascriptInterface
        fun onError(msg: String) = runOnUiThread { toast(msg) }
    }
}
