package com.xaulinxs.aosp.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.xaulinxs.funcoes.FileManagerActivity
import com.xaulinxs.funcoes.SearchEngineManager
import com.xaulinxs.funcoes.ThemeManager

/**
 * Tela de configurações: lista de motores de busca (fixos + customizados),
 * botão para adicionar um mecanismo novo, e acesso a Downloads/File
 * Manager (reservado pela seção 6 do plano de fase 2).
 */
class SettingsActivity : Activity() {

    private lateinit var searchEngineGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        searchEngineGroup = findViewById(R.id.searchEngineGroup)
        findViewById<Button>(R.id.btnAddSearchEngine).setOnClickListener { showAddEngineDialog() }

        findViewById<TextView>(R.id.menuDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        findViewById<TextView>(R.id.menuFileManager).setOnClickListener {
            val intent = Intent(this, FileManagerActivity::class.java)
            intent.putExtra(FileManagerActivity.EXTRA_MODE, FileManagerActivity.MODE_BROWSE)
            startActivity(intent)
        }

        findViewById<TextView>(R.id.menuDefaultBrowser).setOnClickListener {
            requestDefaultBrowserRole()
        }

        setupThemeSelector()
        renderSearchEngines()
    }

    private fun setupThemeSelector() {
        val themeGroup = findViewById<RadioGroup>(R.id.themeGroup)
        val themeSystem = findViewById<RadioButton>(R.id.themeSystem)
        val themeLight = findViewById<RadioButton>(R.id.themeLight)
        val themeDark = findViewById<RadioButton>(R.id.themeDark)

        when (ThemeManager.getThemeMode(this)) {
            ThemeManager.MODE_LIGHT -> themeLight.isChecked = true
            ThemeManager.MODE_DARK -> themeDark.isChecked = true
            else -> themeSystem.isChecked = true
        }

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> ThemeManager.MODE_LIGHT
                R.id.themeDark -> ThemeManager.MODE_DARK
                else -> ThemeManager.MODE_SYSTEM
            }
            ThemeManager.setThemeMode(this, mode)
            recreate()
        }
    }

    companion object {
        private const val REQUEST_CODE_ROLE_BROWSER = 200
    }

    /**
     * Abre o fluxo do sistema pra o usuário definir este app como
     * navegador padrão. A partir do Android 10 (API 29), o caminho
     * recomendado é o RoleManager (ACTION_REQUEST_ROLE) - ele mostra um
     * diálogo do sistema já filtrado pra navegadores instalados. Em
     * versões mais antigas, cai pra tela geral de "Apps padrão" das
     * Configurações, onde o usuário escolhe manualmente na categoria
     * Navegador. Não existe uma forma de definir o app como padrão
     * programaticamente sem interação do usuário - isso é proposital,
     * o Android exige confirmação explícita pra esse tipo de papel.
     */
    private fun requestDefaultBrowserRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    Toast.makeText(this, R.string.default_browser_already_set, Toast.LENGTH_SHORT).show()
                    return
                }
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                startActivityForResult(intent, REQUEST_CODE_ROLE_BROWSER)
                return
            }
        }
        // Fallback para Android 9 e anteriores (ou se o RoleManager não
        // estiver disponível no dispositivo): abre a tela geral de
        // "Apps padrão" nas Configurações do sistema.
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: android.content.ActivityNotFoundException) {
            // Alguns fabricantes/ROMs minimalistas (o mesmo cenário AOSP
            // puro que motivou o resto do projeto) podem não ter essa
            // tela - último recurso: a tela de detalhes do próprio app.
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }
    }

    private fun renderSearchEngines() {
        searchEngineGroup.removeAllViews()
        val active = SearchEngineManager.activeEngine(this)
        for (engine in SearchEngineManager.allEngines(this)) {
            val radio = RadioButton(this)
            radio.text = engine.name
            radio.id = android.view.View.generateViewId()
            radio.isChecked = engine.name == active.name
            radio.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radio.setOnClickListener {
                SearchEngineManager.setActiveEngine(this, engine.name)
            }
            searchEngineGroup.addView(radio)
        }
    }

    private fun showAddEngineDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.add_search_engine_name_hint)
        container.addView(nameInput)

        val urlInput = EditText(this)
        urlInput.hint = getString(R.string.add_search_engine_url_hint)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_add_search_engine)
            .setView(container)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || !url.contains("%s")) {
                    Toast.makeText(this, R.string.add_search_engine_url_hint, Toast.LENGTH_SHORT).show()
                } else {
                    SearchEngineManager.addCustomEngine(this, name, url)
                    renderSearchEngines()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
