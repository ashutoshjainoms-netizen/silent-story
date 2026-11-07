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
 * Password, approval and codes all happen on Instagram's page.
 *
 * Two things the loop does:
 *  - every second: if Instagram has set the login cookie, save it and open the app.
 *  - on a "waiting for approval" screen only: refresh it every few seconds so that
 *    when you approve in the official Instagram app, BlindView notices and continues.
 *    A code-entry screen is NEVER refreshed, so a code you are typing is never lost.
 *
 * The saved login stays until you tap Settings -> Log out.
 */
class LoginActivity : AppCompatActivity() {
    companion object {
        /** True while the login screen exists, so Home never opens a second, fresh one. */
        @Volatile var open = false
        private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
        private const val RELOAD_EVERY = 5000L   // refresh a waiting screen this often

        /** Classifies the current page so we only refresh the safe ones. */
        private const val CLASSIFY = """
(function(){
  function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  var p=document.querySelector('input[type=password]');
  if(p&&vis(p))return 'login';
  var c=document.querySelector('input[autocomplete=one-time-code],input[name=verificationCode],input[name=security_code],input[name=code],input[inputmode=numeric]');
  if(c&&vis(c))return 'code';
  var t=(document.body?document.body.innerText:'').toLowerCase();
  if(t.indexOf('check your notifications')>=0||t.indexOf('approve the login')>=0||t.indexOf('waiting for approval')>=0||t.indexOf('sent a notification')>=0||t.indexOf('approve from the other device')>=0)return 'wait';
  if(/challenge|two_factor|auth_platform|approval/.test(location.href))return 'wait';
  return 'other';
})()
"""
    }

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var lastReload = 0L

    private val check = object : Runnable {
        override fun run() {
            CookieManager.getInstance().flush()
            if (Ig.hasSessionCookie()) { finishLogin(); return }
            if (System.currentTimeMillis() - lastReload >= RELOAD_EVERY) {
                web.evaluateJavascript(CLASSIFY) { raw ->
                    val kind = raw?.trim('"').orEmpty()
                    if (kind == "wait" && !done) {
                        lastReload = System.currentTimeMillis()
                        web.reload()   // re-ask Instagram whether the approval went through
                    }
                }
            }
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
        CookieManager.getInstance().flush()
        if (Ig.hasSessionCookie()) { finishLogin(); return }
        // Just came back (likely from approving in the official app): classify once now,
        // so a waiting screen refreshes right away instead of after the next interval.
        lastReload = 0L
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
