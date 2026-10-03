package com.xaulinxs.funcoes

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * XAULINXS_FIX_V3_LOGIN
 *
 * Preferências de cookies (Configurações). Padrão: tudo LIGADO, porque
 * login (inclusive o Google Sign-In/GSI em sites de terceiros) depende de
 * cookies e de cookies entre domínios.
 *
 * setAcceptCookie é global do processo (vale na hora); o de terceiros é
 * por WebView, então a MainActivity reaplica em onResume e em cada
 * WebView/popup que cria.
 */
object CookiePrefsManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_COOKIES = "cookies_enabled"
    private const val KEY_THIRD_PARTY = "cookies_third_party_enabled"

    fun isCookiesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COOKIES, true)

    fun setCookiesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COOKIES, enabled).apply()
        CookieManager.getInstance().setAcceptCookie(enabled)
    }

    fun isThirdPartyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_THIRD_PARTY, true)

    fun setThirdPartyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_THIRD_PARTY, enabled).apply()
    }

    /** Aplica as duas preferências (global + a WebView informada, se houver). */
    fun apply(context: Context, webView: WebView?) {
        val manager = CookieManager.getInstance()
        val cookies = isCookiesEnabled(context)
        manager.setAcceptCookie(cookies)
        if (webView != null) {
            manager.setAcceptThirdPartyCookies(webView, cookies && isThirdPartyEnabled(context))
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
