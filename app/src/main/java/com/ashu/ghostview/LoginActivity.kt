package com.ashu.ghostview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Instagram's own, official login page, full screen. Password, captcha, approval and
 * codes all happen on Instagram's page; BlindView adds nothing on top.
 *
 * Detecting login:
 *  - every second it checks (passively) whether Instagram has set the login cookie.
 *  - it NEVER reloads or navigates the page. After you approve / match the number,
 *    Instagram's own page finishes the login and sets the cookie; BlindView just
 *    watches for that cookie and then opens the app. Nothing is done to the page.
 *
 * The saved login stays until you tap Settings -> Log out.
 */
class LoginActivity : AppCompatActivity() {
    companion object {
        @Volatile var open = false
        private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
        // Desktop Chrome identity, so Instagram serves its desktop login flow, whose
        // device-approval step completes more reliably inside an in-app browser.
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

// (no page classifier needed: the screen is left entirely to Instagram)

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    /** Passive: only watches for the cookie. Never reloads. */
    private val check = object : Runnable {
        override fun run() {
            CookieManager.getInstance().flush()
            if (Ig.hasSessionCookie()) { finishLogin(); return }
            handler.postDelayed(this, 1000)
        }
    }

    private fun finishLogin() {
        if (done) return
        done = true
        Ig.saveSession()
        PageFetch.reset()
        open = false
        finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        open = true
        web = findViewById(R.id.web)
        web.asPlainBrowser()
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = DESKTOP_UA        // desktop login flow
            useWideViewPort = true              // make the desktop page fit the phone
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme
                return s != "http" && s != "https"
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (Ig.hasSessionCookie()) finishLogin()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (Ig.hasSessionCookie()) finishLogin()
            }
        }

        val restored = savedInstanceState?.getBundle("web")?.let { web.restoreState(it) != null } == true
        if (!restored) {
            Ig.forgetSaved()
            web.loadUrl(LOGIN_URL)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishAffinity()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        CookieManager.getInstance().flush()
        if (Ig.hasSessionCookie()) { finishLogin(); return }
        // Do NOT reload or navigate. After you match the number, Instagram's own page
        // finishes the login by itself; we just watch for the cookie it then sets.
        handler.post(check)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(check) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("web", Bundle().also { web.saveState(it) })
    }

    override fun onDestroy() {
        open = false
        handler.removeCallbacks(check)
        super.onDestroy()
    }
}
