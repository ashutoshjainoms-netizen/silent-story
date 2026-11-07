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
 *  - it NEVER refreshes on a timer. The only refresh is a SINGLE one when you return
 *    to BlindView after approving in the official Instagram app -- that one reload lets
 *    Instagram's page pick up the approval and move on. If it still shows the waiting
 *    screen a few seconds later, it opens the Instagram home page once to finish.
 *  - captcha / password / code screens are never touched.
 *
 * The saved login stays until you tap Settings -> Log out.
 */
class LoginActivity : AppCompatActivity() {
    companion object {
        @Volatile var open = false
        private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
        private const val HOME_URL = "https://www.instagram.com/"

        /** "wait" = passive approval screen (safe to reload once). "hold" = leave it alone. */
        private const val CLASSIFY = """
(function(){
  function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  if(document.querySelector('iframe[src*="recaptcha"],iframe[title*="recaptcha"],.g-recaptcha,[data-sitekey]'))return 'hold';
  var t=(document.body?document.body.innerText:'').toLowerCase();
  if(t.indexOf('not a robot')>=0||t.indexOf('recaptcha')>=0||t.indexOf('security check')>=0)return 'hold';
  var pw=document.querySelector('input[type=password]');
  if(pw&&vis(pw))return 'hold';
  var ins=document.querySelectorAll('input');
  for(var i=0;i<ins.length;i++){var el=ins[i];var ty=(el.type||'').toLowerCase();
    if((ty==='text'||ty==='tel'||ty==='number'||ty==='email'||ty===''||el.getAttribute('inputmode')==='numeric')&&vis(el)&&!el.disabled&&!el.readOnly)return 'hold';}
  if(t.indexOf('check your notifications')>=0||t.indexOf('approve from the other device')>=0||t.indexOf('waiting for approval')>=0||t.indexOf('sent a notification')>=0||t.indexOf('approve the login')>=0)return 'wait';
  return 'hold';
})()
"""
    }

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var nudging = false   // a single resume-triggered reload sequence is in flight

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

    /**
     * Called once when the user comes back (usually straight after approving in the IG app).
     * Reload the waiting screen a single time; if it's still waiting ~6s later, open the
     * home page once so an already-approved session is picked up.
     */
    private fun nudgeIfWaiting() {
        if (done || nudging) return
        web.evaluateJavascript(CLASSIFY) { raw ->
            if (done) return@evaluateJavascript
            if (raw?.trim('"') == "wait") {
                nudging = true
                web.reload()
                handler.postDelayed({
                    CookieManager.getInstance().flush()
                    if (done || Ig.hasSessionCookie()) { nudging = false; return@postDelayed }
                    web.evaluateJavascript(CLASSIFY) { again ->
                        if (!done && again?.trim('"') == "wait") web.loadUrl(HOME_URL)
                        nudging = false
                    }
                }, 6000)
            }
        }
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
        handler.post(check)
        // Give the page a moment to settle after the app comes forward, then nudge once.
        handler.postDelayed({ nudgeIfWaiting() }, 700)
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
