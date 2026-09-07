package com.xaulinxs.aosp.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.xaulinxs.funcoes.DownloadEntry
import com.xaulinxs.funcoes.DownloadHandler
import com.xaulinxs.funcoes.DownloadsAdapter

/**
 * Tela de Downloads: lista os arquivos já concluídos via
 * DownloadHandler.queryDownloads(). Fase 4: ações explícitas por item
 * (botão de abrir + botão de lixeira), em vez do fluxo antigo de toque
 * simples (abrir)/toque longo (excluir, com mensagem confusa
 * "Downloads?"). A confirmação agora nomeia o arquivo e explica o que
 * vai acontecer.
 */
class DownloadsActivity : Activity(), DownloadsAdapter.OnActionListener {

    private lateinit var listView: ListView
    private lateinit var emptyLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        listView = findViewById(R.id.downloadsList)
        emptyLabel = findViewById(R.id.emptyLabel)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val entries = DownloadHandler.queryDownloads(this)
        listView.adapter = DownloadsAdapter(this, entries, this)
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onOpen(entry: DownloadEntry) {
        DownloadHandler.openDownload(this, entry)
    }

    override fun onDelete(entry: DownloadEntry) {
        confirmDelete(entry)
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
