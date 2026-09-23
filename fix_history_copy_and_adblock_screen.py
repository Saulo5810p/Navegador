#!/usr/bin/env python3
"""
Aplica no repo local (Navegador) as mudanças da tela de Histórico e a
nova tela dedicada de Adblock:

1) Histórico: pressionar-e-segurar num item copia o link pra área de
   transferência (toque continua abrindo o site na WebView, como já
   era).

2) Nova tela "Domínios bloqueados" (AdBlockListActivity), acessada por
   um item de menu em Configurações, logo abaixo do switch de Adblock:
   - Mostra a lista completa de domínios bloqueados (hosts_block.txt +
     os adicionados manualmente), com campo de busca/filtro (a lista
     tem dezenas de milhares de entradas, então o filtro roda numa
     thread separada).
   - Campo + botão pra adicionar um domínio novo na hora, persistido
     via SharedPreferences (AdBlockManager.addCustomDomain) - efeito
     imediato, sem precisar reiniciar o app.
   - Pressionar-e-segurar num domínio adicionado manualmente oferece
     removê-lo; domínios da lista embutida não são removíveis por aqui
     (aviso ao tentar).

Uso (dentro da pasta do projeto Navegador, no Termux):
    python3 fix_history_copy_and_adblock_screen.py

Idempotente: pode rodar de novo sem duplicar nada.
"""
import os
import sys

REPO_ROOT = os.getcwd()

ADBLOCK_MANAGER = os.path.join(REPO_ROOT, "app/src/main/java/com/xaulinxs/funcoes/AdBlockManager.kt")
HISTORY_ACTIVITY = os.path.join(REPO_ROOT, "app/src/main/java/com/xaulinxs/aosp/browser/HistoryActivity.kt")
SETTINGS_ACTIVITY = os.path.join(REPO_ROOT, "app/src/main/java/com/xaulinxs/aosp/browser/SettingsActivity.kt")
SETTINGS_LAYOUT = os.path.join(REPO_ROOT, "app/src/main/res/layout/activity_settings.xml")
STRINGS_XML = os.path.join(REPO_ROOT, "app/src/main/res/values/strings.xml")
MANIFEST = os.path.join(REPO_ROOT, "app/src/main/AndroidManifest.xml")

ADBLOCK_LIST_ACTIVITY = os.path.join(REPO_ROOT, "app/src/main/java/com/xaulinxs/aosp/browser/AdBlockListActivity.kt")
ADBLOCK_LIST_LAYOUT = os.path.join(REPO_ROOT, "app/src/main/res/layout/activity_adblock_list.xml")


def die(msg):
    print("ERRO: " + msg, file=sys.stderr)
    sys.exit(1)


def read(path):
    if not os.path.exists(path):
        die(f"arquivo não encontrado: {path} (rode este script na raiz do projeto Navegador)")
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


# --- 1) AdBlockManager.kt: domínios customizados ---

OLD_CONST_BLOCK = '''    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_ENABLED = "adblock_enabled"
    private const val ASSET_PATH = "adblock/hosts_block.txt"
    private const val DECISION_CACHE_MAX = 4000'''

NEW_CONST_BLOCK = '''    private const val PREFS_NAME = "xaulinxs_browser_prefs"
    private const val KEY_ENABLED = "adblock_enabled"
    private const val KEY_CUSTOM_DOMAINS = "adblock_custom_domains"
    private const val ASSET_PATH = "adblock/hosts_block.txt"
    private const val DECISION_CACHE_MAX = 4000'''

NEW_METHODS_BLOCK = '''
    /** Domínios adicionados manualmente pelo usuário na tela dedicada de Adblock. */
    fun customDomains(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_CUSTOM_DOMAINS, emptySet()) ?: emptySet()

    /**
     * Adiciona um domínio customizado à lista de bloqueio, digitado
     * manualmente pelo usuário na tela dedicada de Adblock. Aceita tanto
     * um domínio puro ("exemplo.com") quanto uma URL colada por engano
     * ("https://exemplo.com/pagina") - nesse caso extrai só o host.
     * Retorna o domínio normalizado que foi salvo, ou null se a entrada
     * for inválida (vazia, com espaço, ou sem nenhum ponto - mesma regra
     * de "não bloqueia TLD inteiro" usada em isHostBlocked). O efeito é
     * imediato: invalida o cache em memória, então a próxima requisição
     * já enxerga o domínio novo, sem precisar reiniciar o app.
     */
    fun addCustomDomain(context: Context, rawInput: String): String? {
        val domain = normalizeDomainInput(rawInput) ?: return null
        val current = HashSet(customDomains(context))
        current.add(domain)
        prefs(context).edit().putStringSet(KEY_CUSTOM_DOMAINS, current).apply()
        invalidateCache()
        return domain
    }

    /** Remove um domínio customizado (não afeta os domínios vindos do hosts_block.txt). */
    fun removeCustomDomain(context: Context, domain: String) {
        val current = HashSet(customDomains(context))
        current.remove(domain)
        prefs(context).edit().putStringSet(KEY_CUSTOM_DOMAINS, current).apply()
        invalidateCache()
    }

    /** Lista completa (hosts_block.txt + customizados) ordenada, pra exibir na tela dedicada de Adblock. */
    fun allBlockedDomains(context: Context): List<String> = loadDomains(context).sorted()

    private fun invalidateCache() {
        synchronized(this) {
            blockedDomains = null
        }
        decisionCache.clear()
    }

    private fun normalizeDomainInput(rawInput: String): String? {
        var value = rawInput.trim().lowercase()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        if (value.contains("://")) {
            value = try {
                Uri.parse(value).host ?: return null
            } catch (e: Exception) {
                return null
            }
        } else if (value.contains("/")) {
            value = value.substringBefore("/")
        }
        if (value.isEmpty() || !value.contains(".")) return null
        return value
    }

    /**'''

OLD_METHODS_MARKER = '''    /**
     * true se a requisição pra essa URL deve ser bloqueada (resposta'''

OLD_IOEXCEPTION_TAIL = '''            } catch (e: IOException) {
                // Sem lista carregada: o adblock vira no-op (nunca bloqueia
                // nada) em vez de travar a navegação - falha segura.
            }
            blockedDomains = set
            return set'''

NEW_IOEXCEPTION_TAIL = '''            } catch (e: IOException) {
                // Sem lista carregada: o adblock vira no-op (nunca bloqueia
                // nada) em vez de travar a navegação - falha segura.
            }
            set.addAll(customDomains(context))
            blockedDomains = set
            return set'''


def patch_adblock_manager():
    content = read(ADBLOCK_MANAGER)
    if "addCustomDomain" in content:
        print("[skip] AdBlockManager.kt já tem addCustomDomain/removeCustomDomain.")
        return
    if OLD_CONST_BLOCK not in content:
        die("AdBlockManager.kt: bloco de constantes não bateu com o formato esperado.")
    content = content.replace(OLD_CONST_BLOCK, NEW_CONST_BLOCK, 1)

    if OLD_METHODS_MARKER not in content:
        die("AdBlockManager.kt: marcador de shouldBlock() não encontrado.")
    content = content.replace(OLD_METHODS_MARKER, NEW_METHODS_BLOCK + OLD_METHODS_MARKER[len('    /**'):], 1)

    if OLD_IOEXCEPTION_TAIL not in content:
        die("AdBlockManager.kt: bloco de loadDomains()/IOException não bateu com o formato esperado.")
    content = content.replace(OLD_IOEXCEPTION_TAIL, NEW_IOEXCEPTION_TAIL, 1)

    write(ADBLOCK_MANAGER, content)
    print("[ok] AdBlockManager.kt atualizado (domínios customizados).")


# --- 2) HistoryActivity.kt: copiar link ao segurar ---

OLD_HISTORY_IMPORTS = '''import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.xaulinxs.funcoes.HistoryAdapter'''

NEW_HISTORY_IMPORTS = '''import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.xaulinxs.funcoes.HistoryAdapter'''

OLD_HISTORY_CLICK_BLOCK = '''            finish()
        }

        findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {'''

NEW_HISTORY_CLICK_BLOCK = '''            finish()
        }

        listView.setOnItemLongClickListener { parent, _, position, _ ->
            val entry = parent.getItemAtPosition(position) as HistoryEntry
            copyLinkToClipboard(entry.url)
            true
        }

        findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {'''

OLD_HISTORY_TAIL = '''    private fun confirmClearHistory() {'''

NEW_HISTORY_TAIL = '''    /**
     * Copia a URL do item segurado pra área de transferência do sistema,
     * via ClipboardManager padrão do Android (funciona em qualquer app
     * de destino onde o usuário for colar depois).
     */
    private fun copyLinkToClipboard(url: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.history_title), url))
        Toast.makeText(this, R.string.history_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearHistory() {'''


def patch_history_activity():
    content = read(HISTORY_ACTIVITY)
    if "copyLinkToClipboard" in content:
        print("[skip] HistoryActivity.kt já tem copyLinkToClipboard.")
        return
    if OLD_HISTORY_IMPORTS not in content:
        die("HistoryActivity.kt: bloco de imports não bateu com o formato esperado.")
    content = content.replace(OLD_HISTORY_IMPORTS, NEW_HISTORY_IMPORTS, 1)

    if OLD_HISTORY_CLICK_BLOCK not in content:
        die("HistoryActivity.kt: bloco do onItemClickListener não bateu com o formato esperado.")
    content = content.replace(OLD_HISTORY_CLICK_BLOCK, NEW_HISTORY_CLICK_BLOCK, 1)

    if OLD_HISTORY_TAIL not in content:
        die("HistoryActivity.kt: confirmClearHistory() não encontrado.")
    content = content.replace(OLD_HISTORY_TAIL, NEW_HISTORY_TAIL, 1)

    write(HISTORY_ACTIVITY, content)
    print("[ok] HistoryActivity.kt atualizado (copiar link ao segurar).")


# --- 3) Novos arquivos: AdBlockListActivity.kt + layout ---

ADBLOCK_LIST_ACTIVITY_CONTENT = '''package com.xaulinxs.aosp.browser

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
'''

ADBLOCK_LIST_LAYOUT_CONTENT = '''<?xml version="1.0" encoding="utf-8"?>
<!--
    Tela dedicada de Adblock: mostra a lista completa de domínios
    bloqueados (hosts_block.txt + domínios adicionados manualmente pelo
    usuário via AdBlockManager.addCustomDomain) e permite adicionar mais
    direto no app. Como a lista tem dezenas de milhares de entradas, tem
    um campo de busca pra filtrar (o filtro roda em background, ver
    AdBlockListActivity.applyFilter).
-->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/adblock_list_title"
        android:textSize="18sp"
        android:padding="16dp" />

    <TextView
        android:id="@+id/adblockCountLabel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="13sp"
        android:textColor="?android:attr/textColorSecondary"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:paddingBottom="8dp" />

    <EditText
        android:id="@+id/adblockFilterInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="12dp"
        android:hint="@string/adblock_list_filter_hint"
        android:inputType="text"
        android:singleLine="true" />

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="#E0E0E0"
        android:layout_marginBottom="12dp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:paddingBottom="12dp">

        <EditText
            android:id="@+id/adblockAddInput"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="@string/adblock_list_add_hint"
            android:inputType="textUri"
            android:singleLine="true" />

        <Button
            android:id="@+id/btnAddBlockedDomain"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:text="@string/adblock_list_add_button" />
    </LinearLayout>

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="#E0E0E0" />

    <ListView
        android:id="@+id/adblockDomainList"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>
'''


def write_new_files():
    if os.path.exists(ADBLOCK_LIST_ACTIVITY):
        print("[skip] AdBlockListActivity.kt já existe.")
    else:
        write(ADBLOCK_LIST_ACTIVITY, ADBLOCK_LIST_ACTIVITY_CONTENT)
        print("[ok] AdBlockListActivity.kt criado.")

    if os.path.exists(ADBLOCK_LIST_LAYOUT):
        print("[skip] activity_adblock_list.xml já existe.")
    else:
        write(ADBLOCK_LIST_LAYOUT, ADBLOCK_LIST_LAYOUT_CONTENT)
        print("[ok] activity_adblock_list.xml criado.")


# --- 4) Item de menu em Configurações (layout + activity) ---

OLD_SETTINGS_LAYOUT_SWITCH = '''            <Switch
                android:id="@+id/adblockSwitch"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
        </LinearLayout>'''

NEW_SETTINGS_LAYOUT_SWITCH = '''            <Switch
                android:id="@+id/adblockSwitch"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
        </LinearLayout>

        <!-- Item de menu: tela dedicada com a lista completa de domínios
             bloqueados + campo pra adicionar mais manualmente. -->
        <TextView
            android:id="@+id/menuAdblockList"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/settings_adblock_list_menu"
            android:textSize="16sp"
            android:padding="14dp"
            android:layout_marginBottom="24dp"
            android:clickable="true"
            android:focusable="true"
            android:background="?android:attr/selectableItemBackground" />'''


def patch_settings_layout():
    content = read(SETTINGS_LAYOUT)
    if "menuAdblockList" in content:
        print("[skip] activity_settings.xml já tem menuAdblockList.")
        return
    if OLD_SETTINGS_LAYOUT_SWITCH not in content:
        die("activity_settings.xml: bloco do adblockSwitch não bateu com o formato esperado.")
    content = content.replace(OLD_SETTINGS_LAYOUT_SWITCH, NEW_SETTINGS_LAYOUT_SWITCH, 1)
    write(SETTINGS_LAYOUT, content)
    print("[ok] activity_settings.xml atualizado (item de menu Adblock).")


OLD_SETTINGS_ACTIVITY_CLICK = '''        findViewById<TextView>(R.id.menuHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }'''

NEW_SETTINGS_ACTIVITY_CLICK = '''        findViewById<TextView>(R.id.menuHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<TextView>(R.id.menuAdblockList).setOnClickListener {
            startActivity(Intent(this, AdBlockListActivity::class.java))
        }'''


def patch_settings_activity():
    content = read(SETTINGS_ACTIVITY)
    if "AdBlockListActivity::class.java" in content:
        print("[skip] SettingsActivity.kt já abre AdBlockListActivity.")
        return
    if OLD_SETTINGS_ACTIVITY_CLICK not in content:
        die("SettingsActivity.kt: bloco do menuHistory não bateu com o formato esperado.")
    content = content.replace(OLD_SETTINGS_ACTIVITY_CLICK, NEW_SETTINGS_ACTIVITY_CLICK, 1)
    write(SETTINGS_ACTIVITY, content)
    print("[ok] SettingsActivity.kt atualizado (abre a tela de Adblock).")


# --- 5) strings.xml ---

STRINGS_TO_ADD = [
    ("settings_adblock_list_menu", "Ver lista de bloqueios"),
    ("adblock_list_title", "Domínios bloqueados"),
    ("adblock_list_count_format", "%1$d de %2$d domínios"),
    ("adblock_list_filter_hint", "Filtrar domínios"),
    ("adblock_list_add_hint", "Adicionar domínio (ex: exemplo.com)"),
    ("adblock_list_add_button", "Adicionar"),
    ("adblock_list_added_format", "%1$s bloqueado"),
    ("adblock_list_invalid_domain", "Digite um domínio válido"),
    ("adblock_list_builtin_domain", "Esse domínio é da lista embutida e não pode ser removido aqui"),
    ("adblock_list_remove_confirm_format", "Remover %1$s da lista de bloqueio?"),
]

OLD_STRINGS_MARKER = '    <string name="settings_adblock_title">Bloquear anúncios</string>'


def patch_strings_xml():
    content = read(STRINGS_XML)
    changed = False

    if "settings_adblock_list_menu" not in content:
        if OLD_STRINGS_MARKER not in content:
            die("strings.xml: settings_adblock_title não encontrado para inserir as novas strings ao lado.")
        block = "\n".join(f'    <string name="{name}">{value}</string>' for name, value in STRINGS_TO_ADD)
        content = content.replace(OLD_STRINGS_MARKER, OLD_STRINGS_MARKER + "\n" + block, 1)
        changed = True
    else:
        print("[skip] strings.xml já tem as strings da lista de Adblock.")

    if "history_link_copied" not in content:
        marker2 = '    <string name="history_clear_confirm">Apagar todo o histórico de navegação?</string>'
        if marker2 not in content:
            die("strings.xml: history_clear_confirm não encontrado para inserir history_link_copied ao lado.")
        content = content.replace(
            marker2,
            marker2 + '\n    <string name="history_link_copied">Link copiado</string>',
            1,
        )
        changed = True
    else:
        print("[skip] strings.xml já tem history_link_copied.")

    if changed:
        write(STRINGS_XML, content)
        print("[ok] strings.xml atualizado.")


# --- 6) AndroidManifest.xml ---

OLD_MANIFEST_HISTORY = '''        <activity
            android:name=".HistoryActivity"
            android:label="@string/history_title"
            android:exported="false" />'''

NEW_MANIFEST_HISTORY = '''        <activity
            android:name=".HistoryActivity"
            android:label="@string/history_title"
            android:exported="false" />

        <activity
            android:name=".AdBlockListActivity"
            android:label="@string/adblock_list_title"
            android:exported="false" />'''


def patch_manifest():
    content = read(MANIFEST)
    if "AdBlockListActivity" in content:
        print("[skip] AndroidManifest.xml já registra AdBlockListActivity.")
        return
    if OLD_MANIFEST_HISTORY not in content:
        die("AndroidManifest.xml: bloco da HistoryActivity não bateu com o formato esperado.")
    content = content.replace(OLD_MANIFEST_HISTORY, NEW_MANIFEST_HISTORY, 1)
    write(MANIFEST, content)
    print("[ok] AndroidManifest.xml atualizado.")


def main():
    patch_adblock_manager()
    patch_history_activity()
    write_new_files()
    patch_settings_layout()
    patch_settings_activity()
    patch_strings_xml()
    patch_manifest()
    print("\nPronto. Agora é só compilar: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
