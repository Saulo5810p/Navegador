package com.xaulinxs.funcoes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Representa uma visita registrada no histórico: título da página (ou a
 * própria URL, se o título não estiver disponível ainda), URL completa e
 * timestamp da visita.
 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val timestamp: Long
)

/**
 * Registro de navegação (Fase 3) - o app não tinha nenhum histórico até
 * aqui. Persistido como JSON em SharedPreferences, mesmo padrão já usado
 * por ShortcutManager/SidebarPrefsManager - suficiente pro volume de
 * dados esperado (histórico de navegação pessoal, não um dataset grande).
 *
 * Cada visita é adicionada ao registrar onPageFinished() na WebView
 * (MainActivity). Entradas mais recentes ficam no topo da lista.
 */
object HistoryManager {

    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_HISTORY = "browsing_history"

    // Limite de entradas guardadas, pra não deixar o SharedPreferences
    // crescer sem controle numa sessão de navegação longa.
    private const val MAX_ENTRIES = 500

    fun addVisit(context: Context, title: String?, url: String?) {
        if (url.isNullOrBlank()) return
        val entries = allEntries(context).toMutableList()
        entries.add(0, HistoryEntry(title?.takeIf { it.isNotBlank() } ?: url, url, System.currentTimeMillis()))
        val trimmed = entries.take(MAX_ENTRIES)
        save(context, trimmed)
    }

    fun allEntries(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        val array = JSONArray(raw)
        val result = mutableListOf<HistoryEntry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                HistoryEntry(
                    title = obj.optString("title"),
                    url = obj.optString("url"),
                    timestamp = obj.optLong("timestamp")
                )
            )
        }
        return result
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("title", entry.title)
            obj.put("url", entry.url)
            obj.put("timestamp", entry.timestamp)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
