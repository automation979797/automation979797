package com.alcomet.rollingcoilarchive;

import android.webkit.WebView;

/**
 * Small Android WebView cookie adapter. Keeping this type in the app package
 * avoids the java.net.CookieManager/android.webkit.CookieManager name clash
 * while making it explicit that downloads reuse only WebView cookies.
 */
final class CookieManager {
    private static final CookieManager INSTANCE = new CookieManager();
    private final android.webkit.CookieManager delegate = android.webkit.CookieManager.getInstance();

    private CookieManager() {}

    static CookieManager getInstance() {
        return INSTANCE;
    }

    void setAcceptCookie(boolean accept) {
        delegate.setAcceptCookie(accept);
    }

    void setAcceptThirdPartyCookies(WebView webView, boolean accept) {
        delegate.setAcceptThirdPartyCookies(webView, accept);
    }

    String getCookie(String url) {
        return delegate.getCookie(url);
    }
}
