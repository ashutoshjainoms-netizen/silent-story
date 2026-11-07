package com.ashu.ghostview

import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Stops Android's WebView from adding the "X-Requested-With: com.ashu.ghostview" header,
 * so Instagram sees a normal Chrome browser instead of an unknown app's embedded page.
 */
fun WebView.asPlainBrowser() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        try { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) } catch (_: Exception) {}
    }
}
