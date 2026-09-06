package com.xaulinxs.funcoes

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Alterna a WebView entre "modo celular" (User-Agent móvel padrão do
 * dispositivo, viewport normal) e "versão para computador" (User-Agent de
 * desktop, WebView.setUseWideViewPort ligado) - o mesmo alternador que
 * existe em qualquer navegador Android (Chrome, Firefox, etc). O estado
 * fica salvo por aba/sessão (aqui simplificado como um estado global do
 * app, já que o navegador ainda não tem múltiplas abas) em
 * SharedPreferences, sobrevivendo a reinícios do app.
 */
object DesktopModeManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_DESKTOP_MODE = "desktop_mode_enabled"

    // User-Agent de desktop genérico (Chrome/Windows), o mesmo tipo de
    // string que outros navegadores Android usam pro "modo desktop" -
    // basta anunciar uma plataforma não-móvel pro site servir o layout
    // completo em vez do responsivo/mobile.
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36"

    fun isDesktopMode(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DESKTOP_MODE, false)
    }

    fun setDesktopMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
    }

    /**
     * Aplica o modo atual (salvo em preferências) às configurações da
     * WebView. Chamar depois de criar a WebView e sempre que o modo for
     * alternado pelo usuário.
     */
    fun applyTo(context: Context, webView: WebView) {
        val desktop = isDesktopMode(context)
        applyMode(webView, desktop)
    }

    /** Alterna o modo, persiste a escolha e reaplica na WebView, recarregando a página atual. */
    fun toggle(context: Context, webView: WebView) {
        val newValue = !isDesktopMode(context)
        setDesktopMode(context, newValue)
        applyMode(webView, newValue)
        webView.reload()
    }

    private fun applyMode(webView: WebView, desktop: Boolean) {
        val settings = webView.settings
        if (desktop) {
            settings.userAgentString = DESKTOP_USER_AGENT
            // setUseWideViewPort + setLoadWithOverviewMode: a dupla clássica
            // que faz a WebView renderizar a página como se fosse uma tela
            // larga de desktop, com zoom inicial ajustado pra caber tudo.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        } else {
            // Volta pro User-Agent móvel real do dispositivo/WebView em uso
            // (o que já vinha por padrão antes de qualquer alternância).
            settings.userAgentString = WebSettings.getDefaultUserAgent(webView.context)
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
