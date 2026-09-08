package com.xaulinxs.aosp.browser.widget

import android.content.Context

/**
 * Define quais atalhos de FUNÇÃO (Desktop/Mobile, Downloads,
 * Configurações, Histórico, Zoom) aparecem na sidebar, e em que ordem. O
 * usuário controla isso pelo popup aberto no botão "+" do rodapé da
 * sidebar (MainActivity.showManageSidebarShortcutsDialog()).
 *
 * Cada função tem um id fixo (não muda entre versões do app, mesmo que o
 * rótulo/ícone mude) - é o que é persistido, não o texto exibido.
 */
enum class SidebarFunction(val id: String) {
    DEVICE_MODE("device_mode"),
    DOWNLOADS("downloads"),
    SETTINGS("settings"),
    HISTORY("history"),
    ZOOM("zoom");

    companion object {
        fun fromId(id: String): SidebarFunction? = values().find { it.id == id }
    }
}

object SidebarPrefsManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_VISIBLE_FUNCTIONS = "sidebar_visible_functions"

    // Todas visíveis por padrão, na ordem natural de declaração do enum -
    // primeira vez que o app abre depois desta atualização, a sidebar já
    // vem com tudo que existia antes na toolbar/bottomBar.
    private val defaultVisible = SidebarFunction.values().map { it.id }.toSet()

    fun visibleFunctions(context: Context): List<SidebarFunction> {
        val stored = prefs(context).getStringSet(KEY_VISIBLE_FUNCTIONS, defaultVisible) ?: defaultVisible
        return SidebarFunction.values().filter { stored.contains(it.id) }
    }

    fun isVisible(context: Context, function: SidebarFunction): Boolean {
        val stored = prefs(context).getStringSet(KEY_VISIBLE_FUNCTIONS, defaultVisible) ?: defaultVisible
        return stored.contains(function.id)
    }

    fun setVisible(context: Context, function: SidebarFunction, visible: Boolean) {
        val current = (prefs(context).getStringSet(KEY_VISIBLE_FUNCTIONS, defaultVisible) ?: defaultVisible).toMutableSet()
        if (visible) current.add(function.id) else current.remove(function.id)
        // getStringSet devolve (por contrato do Android) uma referência
        // que não deve ser mutada e depois salva de volta sem cópia - por
        // isso o toMutableSet() acima antes de qualquer add/remove.
        prefs(context).edit().putStringSet(KEY_VISIBLE_FUNCTIONS, current).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
