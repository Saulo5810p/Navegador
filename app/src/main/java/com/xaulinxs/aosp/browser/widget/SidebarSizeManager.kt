package com.xaulinxs.aosp.browser.widget

import android.content.Context

/**
 * Modo de exibição da sidebar (Fase 2): metade superior, metade inferior
 * ou tela toda, além do padrão (altura total, colada no topo). O usuário
 * escolhe pelo popup de redimensionamento (botão dedicado no cabeçalho
 * do painel), e a escolha é lembrada entre aberturas.
 */
enum class SidebarSizeMode(val id: String) {
    FULL("full"),
    TOP_HALF("top_half"),
    BOTTOM_HALF("bottom_half"),
    FULLSCREEN("fullscreen");

    companion object {
        fun fromId(id: String?): SidebarSizeMode =
            values().find { it.id == id } ?: FULL
    }
}

object SidebarSizeManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_SIZE_MODE = "sidebar_size_mode"

    // Lembra se a barra estava expandida (tudo visível) ou colapsada (só
    // a faixa fina de ícones) da última vez que o app foi usado -
    // padrão colapsada (false), já que o pedido era pra ela aparecer
    // assim sem precisar ser expandida na primeira abertura.
    private const val KEY_EXPANDED = "sidebar_expanded"

    fun getSizeMode(context: Context): SidebarSizeMode =
        SidebarSizeMode.fromId(prefs(context).getString(KEY_SIZE_MODE, SidebarSizeMode.FULL.id))

    fun setSizeMode(context: Context, mode: SidebarSizeMode) {
        prefs(context).edit().putString(KEY_SIZE_MODE, mode.id).apply()
    }

    fun getExpanded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPANDED, false)

    fun setExpanded(context: Context, expanded: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPANDED, expanded).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
