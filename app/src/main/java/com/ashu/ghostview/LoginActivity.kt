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
 * Instagram's own, official login page, full screen, with nothing added by BlindView.
 * Everything (password, approval, codes, "Try another way") is done on Instagram's page.
 * As soon as Instagram gives the login cookie, BlindView saves it and opens the app.
 * The saved login stays until you tap Settings -> Log out.
 */
class LoginActivity : AppCompatActivity() {
    companion object {
        /** True while the login screen exists, so Home never opens a second, fresh one. */
        @Volatile var open = false
        private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
    }

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    /** Every second: did Instagram hand over the login cookie yet? */
    private val check = object : Runnable {
        override fun run() {
            CookieManager.getInstance().flush()
            if (Ig.hasSessionCookie()) { finishLogin(); return }
            // Only watches for the login cookie. Never touches Instagram's page.
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
            userAgentString = Ig.UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme
                return s != "http" && s != "https"   // stay on Instagram's web page
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
            Ig.forgetSaved()   // only reached when there is no login at all
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
        // Never reload: coming back from Gmail must keep Instagram's code page exactly as it was.
        CookieManager.getInstance().flush()
        if (Ig.hasSessionCookie()) { finishLogin(); return }
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
