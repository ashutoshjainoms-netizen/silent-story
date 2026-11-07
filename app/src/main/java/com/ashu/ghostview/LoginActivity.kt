package com.ashu.ghostview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Instagram's own login page. Detects the login automatically:
 *  - checks for the session every second (no button needed),
 *  - while Instagram waits for approval on another device, quietly re-checks the
 *    approval every few seconds, so tapping "Approve" on the other phone is enough.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var banner: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var waitingSince = 0L
    private var lastProbe = 0L
    private var done = false

    private val check = object : Runnable {
        override fun run() {
            CookieManager.getInstance().flush()
            if (Ig.hasSessionCookie()) { finishLogin(); return }
            val url = web.url.orEmpty()
            if (isWaitingPage(url)) {
                if (waitingSince == 0L) waitingSince = System.currentTimeMillis()
                banner.text = "Waiting for you to approve on your other phone… GhostView will log in by itself as soon as you approve."
                // Every 6 s ask Instagram (in the background) whether the approval went through.
                if (System.currentTimeMillis() - lastProbe > 6000) { lastProbe = System.currentTimeMillis(); probe() }
            } else if (waitingSince > 0L && url.contains("/accounts/login")) {
                banner.text = "Instagram went back to the login page. Log in again, and on the approval screen tap \"Try another way\" to get a code by SMS, WhatsApp or email instead."
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun isWaitingPage(url: String) =
        url.contains("challenge") || url.contains("two_factor") || url.contains("auth_platform") ||
            url.contains("codeentry") || url.contains("approval")

    /** Load Instagram's home page in a hidden step; if the approval is done, this sets the session. */
    private fun probe() {
        web.evaluateJavascript(
            
                "fetch('/',{credentials:'include'}).catch(function(){});", null
        )
    }

    private fun finishLogin() {
        if (done) return
        done = true
        Ig.saveSession()
        PageFetch.reset()
        finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        Ig.forgetSaved()   // an old expired session must not mix into this login
        web = findViewById(R.id.web)
        banner = findViewById(R.id.banner)
        findViewById<View>(R.id.btnContinue).setOnClickListener {
            CookieManager.getInstance().flush()
            if (Ig.hasSessionCookie()) finishLogin() else web.loadUrl("https://www.instagram.com/")
        }
        findViewById<View>(R.id.btnRestart).setOnClickListener {
            waitingSince = 0L
            web.loadUrl("https://www.instagram.com/accounts/login/")
        }
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = Ig.UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme
                return s != "http" && s != "https"   // never jump to the Instagram app
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (Ig.hasSessionCookie()) finishLogin()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (Ig.hasSessionCookie()) finishLogin()
            }
        }
        web.loadUrl("https://www.instagram.com/accounts/login/")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishAffinity()
            }
        })
    }

    override fun onResume() { super.onResume(); handler.post(check) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(check) }
}
