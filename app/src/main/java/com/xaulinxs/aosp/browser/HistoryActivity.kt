package com.xaulinxs.aosp.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.xaulinxs.funcoes.HistoryAdapter
import com.xaulinxs.funcoes.HistoryEntry
import com.xaulinxs.funcoes.HistoryManager

/**
 * Tela de Histórico (Fase 3): lista as visitas registradas por
 * HistoryManager, mais recentes primeiro. Toque num item reabre a
 * MainActivity já carregando aquela URL; "Limpar histórico" apaga tudo
 * de uma vez, com confirmação.
 */
class HistoryActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyLabel: TextView

    companion object {
        /** Extra lido pela MainActivity para carregar a URL escolhida ao voltar. */
        const val EXTRA_OPEN_URL = "com.xaulinxs.aosp.browser.extra.OPEN_URL_FROM_HISTORY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        listView = findViewById(R.id.historyList)
        emptyLabel = findViewById(R.id.historyEmptyLabel)

        listView.setOnItemClickListener { parent, _, position, _ ->
            val entry = parent.getItemAtPosition(position) as HistoryEntry
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            intent.putExtra(EXTRA_OPEN_URL, entry.url)
            startActivity(intent)
            finish()
        }

        findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {
            confirmClearHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val entries = HistoryManager.allEntries(this)
        listView.adapter = HistoryAdapter(this, entries)
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                HistoryManager.clear(this)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
