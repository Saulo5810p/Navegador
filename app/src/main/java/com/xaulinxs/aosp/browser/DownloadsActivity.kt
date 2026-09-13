package com.xaulinxs.aosp.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.xaulinxs.funcoes.DownloadEntry
import com.xaulinxs.funcoes.DownloadHandler
import com.xaulinxs.funcoes.DownloadsAdapter

/**
 * Tela de Downloads: lista TODOS os downloads do DownloadManager (não só
 * concluídos) via DownloadHandler.queryDownloads() - em andamento/na fila
 * piscando com progresso, pausados pelo sistema com o motivo, com falha
 * oferecendo "Baixar novamente", e concluídos com abrir/excluir (fluxo
 * original, inalterado). Enquanto houver algum download ativo, a tela se
 * atualiza sozinha a cada meio segundo pra refletir o progresso.
 */
class DownloadsActivity : Activity(), DownloadsAdapter.OnActionListener {

    private lateinit var listView: ListView
    private lateinit var emptyLabel: TextView

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val hasActive = reload()
            if (hasActive) {
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        listView = findViewById(R.id.downloadsList)
        emptyLabel = findViewById(R.id.emptyLabel)
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    /** Recarrega a lista; retorna true se ainda há algum download em andamento/na fila/pausado. */
    private fun reload(): Boolean {
        val entries = DownloadHandler.queryDownloads(this)
        listView.adapter = DownloadsAdapter(this, entries, this)
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        return entries.any { it.isActive }
    }

    override fun onOpen(entry: DownloadEntry) {
        DownloadHandler.openDownload(this, entry)
    }

    override fun onDelete(entry: DownloadEntry) {
        confirmDelete(entry)
    }

    override fun onRetry(entry: DownloadEntry) {
        DownloadHandler.retryDownload(this, entry)
        reload()
    }

    private fun confirmDelete(entry: DownloadEntry) {
        val fileName = entry.title ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.downloads_delete_confirm_title)
            .setMessage(getString(R.string.downloads_delete_confirm_message, fileName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadHandler.deleteDownload(this, entry)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
