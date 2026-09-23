package com.xaulinxs.aosp.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.xaulinxs.funcoes.AdBlockManager

/**
 * Tela dedicada ao Adblock (separada do switch on/off em Configurações):
 * mostra a lista completa de domínios bloqueados - hosts_block.txt
 * embutido no APK mais os domínios adicionados manualmente pelo usuário
 * (AdBlockManager.addCustomDomain/removeCustomDomain) - e deixa
 * adicionar novos direto no app, sem precisar editar nenhum arquivo na
 * mão. Como a lista tem dezenas de milhares de entradas, tem um campo de
 * busca pra filtrar; o filtro roda numa thread separada com um pequeno
 * debounce (250ms) pra não travar a digitação.
 */
class AdBlockListActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var countLabel: TextView
    private lateinit var filterInput: EditText
    private lateinit var adapter: ArrayAdapter<String>

    private var allDomains: List<String> = emptyList()
    private var customDomains: Set<String> = emptySet()

    private val filterHandler = Handler(Looper.getMainLooper())
    private var pendingFilter: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adblock_list)

        listView = findViewById(R.id.adblockDomainList)
        countLabel = findViewById(R.id.adblockCountLabel)
        filterInput = findViewById(R.id.adblockFilterInput)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = adapter

        filterInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                scheduleFilter(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val addInput = findViewById<EditText>(R.id.adblockAddInput)
        findViewById<Button>(R.id.btnAddBlockedDomain).setOnClickListener {
            val added = AdBlockManager.addCustomDomain(this, addInput.text.toString())
            if (added == null) {
                Toast.makeText(this, R.string.adblock_list_invalid_domain, Toast.LENGTH_SHORT).show()
            } else {
                addInput.setText("")
                Toast.makeText(
                    this,
                    getString(R.string.adblock_list_added_format, added),
                    Toast.LENGTH_SHORT
                ).show()
                reload()
                scheduleFilter(filterInput.text.toString())
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val domain = adapter.getItem(position)
            if (domain != null) {
                if (customDomains.contains(domain)) {
                    confirmRemoveCustomDomain(domain)
                } else {
                    Toast.makeText(this, R.string.adblock_list_builtin_domain, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        reload()
    }

    private fun reload() {
        allDomains = AdBlockManager.allBlockedDomains(this)
        customDomains = AdBlockManager.customDomains(this)
        adapter.clear()
        adapter.addAll(allDomains)
        adapter.notifyDataSetChanged()
        updateCountLabel(allDomains.size)
    }

    private fun updateCountLabel(shown: Int) {
        countLabel.text = getString(R.string.adblock_list_count_format, shown, allDomains.size)
    }

    private fun scheduleFilter(query: String) {
        pendingFilter?.let { filterHandler.removeCallbacks(it) }
        val runnable = Runnable { applyFilter(query) }
        pendingFilter = runnable
        filterHandler.postDelayed(runnable, 250)
    }

    /**
     * Filtra a lista (já carregada em memória) numa thread separada -
     * com dezenas de milhares de domínios, fazer isso na thread principal
     * a cada tecla digitada travaria a digitação.
     */
    private fun applyFilter(query: String) {
        val trimmed = query.trim().lowercase()
        val baseline = allDomains
        Thread {
            val filtered = if (trimmed.isEmpty()) baseline else baseline.filter { it.contains(trimmed) }
            runOnUiThread {
                if (baseline === allDomains) {
                    adapter.clear()
                    adapter.addAll(filtered)
                    adapter.notifyDataSetChanged()
                    updateCountLabel(filtered.size)
                }
            }
        }.start()
    }

    private fun confirmRemoveCustomDomain(domain: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.adblock_list_remove_confirm_format, domain))
            .setPositiveButton(R.string.shortcuts_dialog_delete) { _, _ ->
                AdBlockManager.removeCustomDomain(this, domain)
                reload()
                scheduleFilter(filterInput.text.toString())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
