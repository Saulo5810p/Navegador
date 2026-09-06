package com.xaulinxs.aosp.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ListView
import android.widget.TextView
import com.xaulinxs.funcoes.DownloadEntry
import com.xaulinxs.funcoes.DownloadHandler
import com.xaulinxs.funcoes.DownloadsAdapter

/**
 * Tela de Downloads: lista os arquivos já concluídos via
 * DownloadHandler.queryDownloads(), com toque para abrir e toque longo
 * para excluir. É a peça que faltava para o usuário conseguir ver o
 * resultado dos downloads feitos pelo DownloadManager - antes não havia
 * nenhuma UI que expusesse essa lista.
 */
class DownloadsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        listView = findViewById(R.id.downloadsList)
        emptyLabel = findViewById(R.id.emptyLabel)

        listView.setOnItemClickListener { parent: AdapterView<*>, _, position, _ ->
            val entry = parent.getItemAtPosition(position) as DownloadEntry
            DownloadHandler.openDownload(this, entry)
        }

        listView.setOnItemLongClickListener { parent, _, position, _ ->
            val entry = parent.getItemAtPosition(position) as DownloadEntry
            confirmDelete(entry)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val entries = DownloadHandler.queryDownloads(this)
        listView.adapter = DownloadsAdapter(this, entries)
        emptyLabel.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun confirmDelete(entry: DownloadEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.title ?: "")
            .setMessage(getString(R.string.settings_downloads) + "?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadHandler.deleteDownload(this, entry)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
