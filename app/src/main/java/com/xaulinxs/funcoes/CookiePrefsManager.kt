package com.xaulinxs.funcoes

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import com.norman.webviewup.lib.WebViewUpgrade

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
        // XAULINXS_COOKIE_EARLY_V1: a preferencia ja foi gravada; se o upgrade
        // ainda nao terminou, apply() aplica quando a WebView for criada.
        if (providerReady()) {
            CookieManager.getInstance().setAcceptCookie(enabled)
        }
    }

    fun isThirdPartyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_THIRD_PARTY, true)

    fun setThirdPartyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_THIRD_PARTY, enabled).apply()
    }

    /** Aplica as duas preferências (global + a WebView informada, se houver). */
    fun apply(context: Context, webView: WebView?) {
        // XAULINXS_COOKIE_EARLY_V1: CookieManager.getInstance() carrega o provider
        // do WebView. Antes do upgrade terminar isso carregaria o WebView DO
        // SISTEMA e o upgrade falharia ("can only be replaced before System
        // WebView init"). initWebView() chama apply() de novo ja com o provider
        // certo, entao pular aqui nao perde nada.
        if (!providerReady()) return
        val manager = CookieManager.getInstance()
        val cookies = isCookiesEnabled(context)
        manager.setAcceptCookie(cookies)
        if (webView != null) {
            manager.setAcceptThirdPartyCookies(webView, cookies && isThirdPartyEnabled(context))
        }
    }

    /** Grava os cookies em disco - sem tocar no provider antes do upgrade terminar. */
    fun flush() {
        if (!providerReady()) return
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // sem provider utilizavel: nada para gravar
        }
    }

    /** true quando o WebViewUpgrade ja terminou (com sucesso ou com falha) - o provider final ja esta decidido. */
    private fun providerReady(): Boolean =
        WebViewUpgrade.isCompleted() || WebViewUpgrade.isFailed()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
