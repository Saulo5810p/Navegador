package com.xaulinxs.funcoes

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * Alternador manual de tema claro/escuro, sem depender de
 * AppCompatDelegate (que viria só com androidx.appcompat, removido do
 * projeto). Usa UiModeManager.setApplicationNightMode - API do próprio
 * framework Android (a partir do Android 10/API 29) que já é respeitada
 * pelo tema DayNight declarado em themes.xml, sem precisar reimplementar
 * a lógica de troca de recursos manualmente.
 *
 * Três opções: SYSTEM (segue o tema do Android, padrão), LIGHT, DARK.
 */
object ThemeManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    fun getThemeMode(context: Context): String {
        return prefs(context).getString(KEY_THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
    }

    /**
     * Salva a escolha e aplica imediatamente via UiModeManager. Em
     * dispositivos anteriores ao Android 10 (onde
     * UiModeManager.setApplicationNightMode não existe), a Activity que
     * chamou precisa se recriar (Activity.recreate()) depois - o tema
     * DayNight já reage à configuração do sistema nesses casos, então a
     * opção manual LIGHT/DARK simplesmente não tem efeito abaixo do
     * Android 10, caindo para MODE_SYSTEM de fato.
     */
    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
        applyToApp(context)
    }

    /** Aplica o modo salvo - chamar no onCreate() da Application e de cada Activity, antes de setContentView. */
    fun applyToApp(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
        val nightMode = when (getThemeMode(context)) {
            MODE_LIGHT -> UiModeManager.MODE_NIGHT_NO
            MODE_DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        uiModeManager.setApplicationNightMode(nightMode)
    }

    /** Chamar depois de mudar o modo, se a Activity atual precisar recompor imediatamente (ex: recursos -night). */
    fun recreateIfNeeded(activity: Activity) {
        activity.recreate()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
