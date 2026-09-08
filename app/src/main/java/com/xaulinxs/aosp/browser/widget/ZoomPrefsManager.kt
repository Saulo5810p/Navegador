package com.xaulinxs.aosp.browser.widget

import android.content.Context

/**
 * Persiste o nível de zoom da página (50% a 200%, padrão 100%) escolhido
 * pelo usuário - via slider, tanto no popup rápido aberto pela barra de
 * atalhos (MainActivity.showZoomDialog()) quanto na seção equivalente
 * dentro de SettingsActivity. Os dois pontos de acesso leem/escrevem o
 * mesmo valor aqui, então mudar num lugar reflete no outro na próxima
 * vez que abrir.
 *
 * O valor persistido é aplicado à página carregada via
 * MainActivity.applyPageZoom() - ver esse método pra entender COMO o
 * zoom é aplicado na WebView (CSS "zoom" injetado por JavaScript).
 */
object ZoomPrefsManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_ZOOM_PERCENT = "page_zoom_percent"

    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 100

    fun getZoomPercent(context: Context): Int =
        prefs(context).getInt(KEY_ZOOM_PERCENT, DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun setZoomPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_ZOOM_PERCENT, percent.coerceIn(MIN_PERCENT, MAX_PERCENT)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
