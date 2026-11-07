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
    companion object {
        /** True while the login screen exists, so Home never opens a second, fresh one. */
        @Volatile var open = false
    }
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
                if (!emailMode) banner.text = "Approve the login in the Instagram app (on this phone or another one), then come back here. BlindView logs in by itself, no need to log in again."
                // Every 6 s ask Instagram (in the background) whether the approval went through.
                if (System.currentTimeMillis() - lastProbe > 6000) { lastProbe = System.currentTimeMillis(); probe() }
            } else if (!emailMode && waitingSince > 0L && url.contains("/accounts/login")) {
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

    // ---------- "Get code by email" ----------
    private var emailTries = 0
    private var emailRunning = false
    private var emailMode = false
    private val emailScript by lazy { assets.open("email_code.js").bufferedReader().use { it.readText() } }

    private fun startEmailCode() {
        CookieManager.getInstance().flush()
        if (Ig.hasSessionCookie()) { finishLogin(); return }
        emailTries = 0
        emailMode = true
        if (!emailRunning) { emailRunning = true; emailStep() }
        banner.text = "Asking Instagram to send a code to your email…"
    }

    private fun emailStep() {
        if (done || isFinishing) { emailRunning = false; return }
        web.evaluateJavascript(emailScript) { raw ->
            val r = raw?.trim('"').orEmpty()
            emailTries++
            when (r) {
                "login" -> {
                    emailRunning = false
                    emailMode = false
                    banner.text = "Log in with your username and password first. When Instagram asks you to approve, tap \"Get code by email instead\"."
                    return@evaluateJavascript
                }
                "code" -> {
                    emailRunning = false
                    banner.text = "Instagram sent a code to your email. Open your email, then type the code in the box below and tap Continue/Confirm."
                    return@evaluateJavascript
                }
                "another" -> banner.text = "Opened \"Try another way\"…"
                "email" -> banner.text = "Picked email…"
                "send" -> banner.text = "Sending the code to your email…"
                "help" -> banner.text = "Looking in Instagram's help options…"
                "none" -> if (emailTries % 4 == 0) banner.text = "Waiting for Instagram to show \"Try another way\"… (it can take up to a minute)"
            }
            if (emailTries >= 60) {
                emailRunning = false
                banner.text = "Instagram didn't offer another way on this screen. Easiest: pull down your notification bar, tap Instagram's \"Was this you?\" → Yes, and come back here. Or tap Start over and log in again to get a new screen."
                return@evaluateJavascript
            }
            handler.postDelayed({ emailStep() }, 1500)
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
        if (savedInstanceState == null) Ig.forgetSaved()   // an old expired session must not mix into this login
        web = findViewById(R.id.web)
        web.asPlainBrowser()
        banner = findViewById(R.id.banner)
        findViewById<View>(R.id.btnContinue).setOnClickListener {
            CookieManager.getInstance().flush()
            if (Ig.hasSessionCookie()) finishLogin() else web.loadUrl("https://www.instagram.com/")
        }
        findViewById<View>(R.id.btnEmailCode).setOnClickListener { startEmailCode() }
        findViewById<View>(R.id.btnRestart).setOnClickListener {
            waitingSince = 0L
            emailMode = false
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
        val restored = savedInstanceState?.getBundle("web")?.let { web.restoreState(it) != null } == true
        if (restored) waitingSince = savedInstanceState?.getLong("waiting") ?: 0L
        else web.loadUrl("https://www.instagram.com/accounts/login/")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishAffinity()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Coming back after approving in the Instagram app: reload the waiting page so
        // Instagram notices the approval right away instead of asking again.
        CookieManager.getInstance().flush()
        if (Ig.hasSessionCookie()) { finishLogin(); return }
        if (waitingSince > 0L && isWaitingPage(web.url.orEmpty())) web.reload()
        handler.post(check)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("web", Bundle().also { web.saveState(it) })
        outState.putLong("waiting", waitingSince)
    }

    override fun onDestroy() {
        open = false
        handler.removeCallbacks(check)
        super.onDestroy()
    }
    override fun onPause() { super.onPause(); handler.removeCallbacks(check) }
}
