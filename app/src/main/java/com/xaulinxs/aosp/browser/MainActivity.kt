package com.xaulinxs.aosp.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.norman.webviewup.lib.UpgradeCallback
import com.norman.webviewup.lib.WebViewUpgrade
import com.xaulinxs.funcoes.DesktopModeManager
import com.xaulinxs.funcoes.FileManagerActivity
import com.xaulinxs.funcoes.HistoryManager
import com.xaulinxs.funcoes.SearchEngineManager
import com.xaulinxs.funcoes.ShortcutManager
import com.xaulinxs.funcoes.download.BrowserDownloadManager
import com.xaulinxs.aosp.browser.widget.SidebarFunction
import com.xaulinxs.aosp.browser.widget.SidebarPrefsManager
import com.xaulinxs.aosp.browser.widget.SidebarSizeManager
import com.xaulinxs.aosp.browser.widget.SidebarSizeMode

/**
 * Tela principal, estilo Chromium: uma Home com busca grande (nova aba) é
 * mostrada por padrão; navegar por uma URL/busca esconde a Home e mostra a
 * WebView em tela cheia; voltar até o fim do histórico volta pra Home.
 *
 * Trocada de AppCompatActivity para Activity pura (ver themes.xml e a
 * remoção de androidx.appcompat do build.gradle.kts) - sem Material/
 * AppCompat em lugar nenhum do app.
 */
class MainActivity : Activity() {

    // Callback pendente do site que chamou <input type="file">, guardado
    // até o FileManagerActivity (modo pick) devolver um resultado - ver
    // onShowFileChooser() e onActivityResult().
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // A WebView NÃO é criada aqui (nem em campo, nem no layout XML).
    // Ela só nasce dentro de initWebView(), depois que o WebViewUpgrade
    // já decidiu qual provider usar - criar uma WebView antes disso
    // vincula o processo ao WebView do sistema e a troca nunca acontece.
    private var webView: WebView? = null

    private lateinit var webViewContainer: FrameLayout
    private lateinit var homeLayout: LinearLayout
    private lateinit var navToolbar: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var urlEditText: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var kernelInfo: TextView
    private lateinit var sidebarWebViewVersion: TextView
    private lateinit var shortcutsContainer: LinearLayout
    private lateinit var btnAddShortcut: FrameLayout

    // Painel lateral (sidebar) de atalhos de função - substitui a antiga
    // bottomBar e os botões Desktop/Mobile da toolbar. Ver view_sidebar.xml.
    private lateinit var sidebarRoot: FrameLayout
    private lateinit var sidebarItemsContainer: LinearLayout
    private lateinit var btnOpenSidebar: FrameLayout
    private lateinit var btnCloseSidebar: ImageButton
    private lateinit var btnManageSidebarShortcuts: FrameLayout
    private lateinit var btnResizeSidebar: ImageButton

    // URL recebida de fora (ex: link clicado em outro app, com o
    // XaulinXs AOSP Browser definido como navegador padrão) - guardada
    // aqui porque o WebViewUpgrade é assíncrono e a WebView pode ainda
    // não existir quando o intent chega em onCreate()/onNewIntent().
    private var pendingExternalUrl: String? = null

    // true quando o app foi aberto pelo widget de busca (4x1, estilo
    // Chromium) - sinaliza que, assim que a Home estiver visível, o
    // campo de busca deve receber foco e abrir o teclado automaticamente.
    private var focusSearchOnHomeRequested = false

    private val webViewVersionRegex = Regex("Chrome/([0-9.]+)")

    companion object {
        /** Extra usado pelo BrowserSearchWidgetProvider para abrir o app já com o foco no campo de busca. */
        const val EXTRA_FOCUS_SEARCH = "com.xaulinxs.aosp.browser.extra.FOCUS_SEARCH"
        private const val REQUEST_CODE_FILE_CHOOSER = 100
        private const val REQUEST_CODE_NOTIFICATIONS = 101
        private const val TAG = "MainActivity"
    }

    private val upgradeCallback = object : UpgradeCallback {
        override fun onUpgradeProcess(percent: Float) {
            kernelInfo.text = getString(R.string.kernel_loading) + " ${(percent * 100).toInt()}%"
        }

        override fun onUpgradeComplete() {
            initWebView()
        }

        override fun onUpgradeError(throwable: Throwable) {
            Log.w(TAG, "Falha ao carregar WebView AOSP embutido, caindo pro WebView do sistema", throwable)
            Toast.makeText(
                this@MainActivity,
                "Falha ao carregar o WebView AOSP embutido, usando o WebView do sistema",
                Toast.LENGTH_LONG
            ).show()
            initWebView()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webViewContainer = findViewById(R.id.webViewContainer)
        homeLayout = findViewById(R.id.homeLayout)
        // navToolbar agora é um <include> (view_top_toolbar.xml) - os ids
        // internos (btnBack, urlEditText, btnReload) continuam
        // encontráveis normalmente a partir da Activity, já que
        // findViewById busca na árvore inteira, não só no include direto.
        navToolbar = findViewById(R.id.navToolbarInclude)
        searchInput = findViewById(R.id.searchInput)
        urlEditText = findViewById(R.id.urlEditText)
        btnBack = findViewById(R.id.btnBack)
        btnReload = findViewById(R.id.btnReload)
        kernelInfo = findViewById(R.id.kernelInfo)
        sidebarWebViewVersion = findViewById(R.id.sidebarWebViewVersion)
        shortcutsContainer = findViewById(R.id.shortcutsContainer)
        btnAddShortcut = findViewById(R.id.btnAddShortcut)

        sidebarRoot = findViewById(R.id.sidebarInclude)
        sidebarItemsContainer = findViewById(R.id.sidebarItemsContainer)
        btnOpenSidebar = findViewById(R.id.btnOpenSidebar)
        btnCloseSidebar = findViewById(R.id.btnCloseSidebar)
        btnManageSidebarShortcuts = findViewById(R.id.btnManageSidebarShortcuts)
        btnResizeSidebar = findViewById(R.id.btnResizeSidebar)

        btnAddShortcut.setOnClickListener { showAddShortcutDialog() }
        renderShortcuts()

        btnOpenSidebar.setOnClickListener { openSidebar() }
        btnCloseSidebar.setOnClickListener { closeSidebar() }
        btnManageSidebarShortcuts.setOnClickListener { showManageSidebarShortcutsDialog() }
        btnResizeSidebar.setOnClickListener { showResizeSidebarDialog() }
        renderSidebarShortcuts()
        applySidebarSizeMode()

        btnBack.setOnClickListener {
            webView?.let { wv -> if (wv.canGoBack()) wv.goBack() else showHome() }
        }

        btnReload.setOnClickListener {
            webView?.reload()
        }

        searchInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO || isEnter) {
                submitQuery(searchInput.text.toString().trim())
                true
            } else {
                false
            }
        }

        // Barra de URL da toolbar de navegação: agora é sempre editável -
        // tocar nela permite digitar/alterar a URL a qualquer momento
        // (antes era um TextView que só exibia a URL atual e não
        // aceitava edição nenhuma). Confirmar com Enter/Ir navega pra
        // URL digitada, usando a mesma lógica de detecção de domínio vs.
        // busca do campo da Home.
        urlEditText.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO || isEnter) {
                submitQuery(urlEditText.text.toString().trim())
                true
            } else {
                false
            }
        }

        requestNotificationPermissionIfNeeded()

        // Se o app foi aberto por outro app (ex: link clicado com o
        // XaulinXs AOSP Browser definido como navegador padrão), o Intent
        // chega com ACTION_VIEW e a URL em intent.data - guarda pra
        // carregar assim que a WebView estiver pronta.
        handleViewIntent(intent)
        // Se veio do widget de busca, a Home já começa visível por padrão
        // (nenhum webView carregado ainda) - só falta focar o campo.
        applyPendingSearchFocusIfNeeded()

        when {
            WebViewUpgrade.isCompleted() -> initWebView()
            WebViewUpgrade.isFailed() -> initWebView()
            else -> {
                kernelInfo.text = getString(R.string.kernel_loading)
                WebViewUpgrade.addUpgradeCallback(upgradeCallback)
            }
        }
    }

    /**
     * O app pode ser reaproveitado (singleTask/launchMode padrão com a
     * Activity já em memória) quando o usuário clica em outro link
     * enquanto o navegador já está aberto - esse novo Intent chega aqui,
     * não em onCreate().
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
        applyPendingSearchFocusIfNeeded()
        pendingExternalUrl?.let { url ->
            val currentWebView = webView
            if (currentWebView != null) {
                showWebView()
                currentWebView.loadUrl(url)
                pendingExternalUrl = null
            }
            // se a WebView ainda não existe, initWebView() vai consumir
            // pendingExternalUrl assim que terminar de inicializar.
        }
    }

    /**
     * A partir do Android 13 (API 33), POST_NOTIFICATIONS é uma permissão
     * de runtime - sem concedê-la, a notificação de progresso do
     * DownloadForegroundService não aparece (o download em si continua
     * funcionando normalmente, só fica "silencioso"). Pedida uma vez no
     * início, no momento mais natural pra esse app (antes do primeiro
     * download acontecer).
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS
                )
            }
        }
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val data = intent.dataString
            if (!data.isNullOrEmpty()) {
                pendingExternalUrl = data
            }
        }
        // Vindo da tela de Histórico (Fase 3): usuário tocou numa entrada
        // e quer reabrir aquela URL na WebView.
        val historyUrl = intent?.getStringExtra(HistoryActivity.EXTRA_OPEN_URL)
        if (!historyUrl.isNullOrEmpty()) {
            pendingExternalUrl = historyUrl
        }
        // Vindo do widget de busca (BrowserSearchWidgetProvider): mostra a
        // Home com o campo de busca já em foco e o teclado aberto, sem
        // carregar nenhuma URL - é só um atalho pra começar a digitar.
        if (intent?.getBooleanExtra(EXTRA_FOCUS_SEARCH, false) == true) {
            focusSearchOnHomeRequested = true
        }
    }

    /**
     * Aplica o foco pendente no campo de busca da Home, se solicitado pelo
     * widget. Chamado depois que a WebView (e a Home) já estão prontas,
     * já que o Intent pode chegar antes da UI estar totalmente montada.
     */
    private fun applyPendingSearchFocusIfNeeded() {
        if (!focusSearchOnHomeRequested) return
        focusSearchOnHomeRequested = false
        showHome()
        searchInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    /** Decide se o texto digitado é uma URL válida ou um termo de busca, e navega. */
    private fun submitQuery(input: String) {
        if (input.isEmpty()) return
        val currentWebView = webView
        if (currentWebView == null) {
            Toast.makeText(this, "Ainda carregando o kernel do WebView...", Toast.LENGTH_SHORT).show()
            return
        }
        val destination = when {
            URLUtil.isValidUrl(input) -> input
            looksLikeDomain(input) -> "https://$input"
            else -> SearchEngineManager.buildSearchUrl(this, input)
        }
        showWebView()
        currentWebView.loadUrl(destination)
    }

    /**
     * Heurística simples pra decidir se o texto digitado é um domínio sem
     * esquema (ex: "example.com") em vez de um termo de busca: sem
     * espaços e com um "." que não seja o primeiro nem o último caractere.
     */
    private fun looksLikeDomain(input: String): Boolean {
        if (input.contains(" ")) return false
        val dotIndex = input.indexOf('.')
        return dotIndex in 1 until input.length - 1
    }

    private fun showHome() {
        homeLayout.visibility = View.VISIBLE
        navToolbar.visibility = View.GONE
        searchInput.setText("")
    }

    private fun showWebView() {
        homeLayout.visibility = View.GONE
        navToolbar.visibility = View.VISIBLE
    }

    /**
     * Reconstrói a linha de atalhos da Home a partir do que está salvo em
     * ShortcutManager - chamado no onCreate e de novo toda vez que um
     * atalho é adicionado, pra refletir a mudança na hora sem precisar
     * recriar a Activity inteira.
     */
    private fun renderShortcuts() {
        shortcutsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (shortcut in ShortcutManager.allShortcuts(this)) {
            val item = inflater.inflate(R.layout.view_shortcut_item, shortcutsContainer, false)
            val initial = item.findViewById<TextView>(R.id.shortcutInitial)
            val label = item.findViewById<TextView>(R.id.shortcutLabel)
            initial.text = shortcut.name.take(1).uppercase()
            label.text = shortcut.name
            item.setOnClickListener {
                val currentWebView = webView
                if (currentWebView == null) {
                    Toast.makeText(this, "Ainda carregando o kernel do WebView...", Toast.LENGTH_SHORT).show()
                } else {
                    showWebView()
                    currentWebView.loadUrl(shortcut.url)
                }
            }
            shortcutsContainer.addView(item)
        }
    }

    /**
     * Diálogo de dois campos (Nome + URL) pra adicionar um atalho novo,
     * igual ao fluxo de "Adicionar atalho" do Chromium. Mesma abordagem
     * de SettingsActivity.showAddEngineDialog(): LinearLayout simples
     * montado em código, sem depender de um layout XML dedicado nem de
     * Material Dialog.
     */
    private fun showAddShortcutDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.shortcuts_dialog_name_hint)
        container.addView(nameInput)

        val urlInput = EditText(this)
        urlInput.hint = getString(R.string.shortcuts_dialog_url_hint)
        urlInput.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.shortcuts_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, R.string.shortcuts_dialog_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    // Mesma normalização do mecanismo de busca: "www.x"
                    // sem esquema vira "https://www.x" automaticamente.
                    if (!url.contains("://")) {
                        url = "https://$url"
                    }
                    ShortcutManager.addShortcut(this, name, url)
                    renderShortcuts()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Abre o painel lateral (sidebar) - mostra o overlay e some com o
     * handle "☰" fixo (o botão de fechar já mora dentro do próprio
     * painel, não precisa dos dois visíveis ao mesmo tempo).
     */
    private fun openSidebar() {
        sidebarRoot.visibility = View.VISIBLE
        btnOpenSidebar.visibility = View.GONE
    }

    private fun closeSidebar() {
        sidebarRoot.visibility = View.GONE
        btnOpenSidebar.visibility = View.VISIBLE
    }

    /**
     * Aplica o modo de tamanho salvo (SidebarSizeManager) ajustando
     * height/gravity dos LayoutParams do sidebarRoot (FrameLayout dentro
     * de contentArea) - chamado no onCreate e sempre que o usuário troca
     * o modo no popup de redimensionamento.
     *
     * FULL/padrão: altura total, colada no topo (comportamento original).
     * TOP_HALF/BOTTOM_HALF: metade da altura do contentArea, colada em
     * cima ou embaixo.
     * FULLSCREEN: ocupa a tela toda (contentArea inteiro).
     */
    private fun applySidebarSizeMode() {
        val params = sidebarRoot.layoutParams as FrameLayout.LayoutParams
        val parentHeight = (sidebarRoot.parent as? View)?.height ?: 0
        when (SidebarSizeManager.getSizeMode(this)) {
            SidebarSizeMode.FULL -> {
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.START or android.view.Gravity.TOP
            }
            SidebarSizeMode.TOP_HALF -> {
                params.height = if (parentHeight > 0) parentHeight / 2 else FrameLayout.LayoutParams.WRAP_CONTENT
                params.gravity = android.view.Gravity.START or android.view.Gravity.TOP
            }
            SidebarSizeMode.BOTTOM_HALF -> {
                params.height = if (parentHeight > 0) parentHeight / 2 else FrameLayout.LayoutParams.WRAP_CONTENT
                params.gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
            }
            SidebarSizeMode.FULLSCREEN -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.START or android.view.Gravity.TOP
            }
        }
        // Largura volta ao padrão (240dp definido no XML) pra qualquer
        // modo que não seja FULLSCREEN, caso o usuário alterne de volta.
        if (SidebarSizeManager.getSizeMode(this) != SidebarSizeMode.FULLSCREEN) {
            params.width = (240 * resources.displayMetrics.density).toInt()
        }
        sidebarRoot.layoutParams = params
    }

    /**
     * Popup com as 4 opções de tamanho da sidebar (padrão, metade de
     * cima, metade de baixo, tela toda) - aberto pelo botão de
     * redimensionar no cabeçalho do painel. Escolha é persistida e
     * aplicada imediatamente, sem precisar fechar/reabrir a sidebar.
     */
    private fun showResizeSidebarDialog() {
        val modes = arrayOf(
            SidebarSizeMode.FULL,
            SidebarSizeMode.TOP_HALF,
            SidebarSizeMode.BOTTOM_HALF,
            SidebarSizeMode.FULLSCREEN
        )
        val labels = arrayOf(
            getString(R.string.sidebar_size_full),
            getString(R.string.sidebar_size_top_half),
            getString(R.string.sidebar_size_bottom_half),
            getString(R.string.sidebar_size_fullscreen)
        )
        val currentIndex = modes.indexOf(SidebarSizeManager.getSizeMode(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.sidebar_resize_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                SidebarSizeManager.setSizeMode(this, modes[which])
                applySidebarSizeMode()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Reconstrói a lista de atalhos de função da sidebar a partir de
     * SidebarPrefsManager.visibleFunctions() - chamado no onCreate e
     * sempre que a visibilidade de algum atalho muda (popup de
     * gerenciar) ou o modo Desktop/Mobile é alternado (pra atualizar o
     * rótulo/ícone daquele item específico).
     */
    private fun renderSidebarShortcuts() {
        sidebarItemsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (function in SidebarPrefsManager.visibleFunctions(this)) {
            val item = inflater.inflate(R.layout.view_sidebar_item, sidebarItemsContainer, false)
            val icon = item.findViewById<ImageView>(R.id.sidebarItemIcon)
            val label = item.findViewById<TextView>(R.id.sidebarItemLabel)

            when (function) {
                SidebarFunction.DEVICE_MODE -> {
                    val isDesktop = DesktopModeManager.isDesktopMode(this)
                    // Ícone mostra o modo que será ATIVADO ao tocar (não o
                    // atual) - mesma convenção que o texto antigo do botão
                    // já seguia ("Desktop"/"Mobile" trocava pro oposto).
                    icon.setImageResource(if (isDesktop) R.drawable.ic_device_mobile else R.drawable.ic_device_desktop)
                    label.text = getString(
                        if (isDesktop) R.string.sidebar_item_mobile_mode else R.string.sidebar_item_desktop_mode
                    )
                    item.setOnClickListener {
                        webView?.let { wv ->
                            DesktopModeManager.toggle(this, wv)
                            updateDesktopModeButtonLabel()
                        }
                    }
                }
                SidebarFunction.DOWNLOADS -> {
                    icon.setImageResource(R.drawable.ic_downloads)
                    label.text = getString(R.string.sidebar_item_downloads)
                    item.setOnClickListener {
                        startActivity(Intent(this, DownloadsActivity::class.java))
                        closeSidebar()
                    }
                }
                SidebarFunction.SETTINGS -> {
                    icon.setImageResource(R.drawable.ic_settings)
                    label.text = getString(R.string.sidebar_item_settings)
                    item.setOnClickListener {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        closeSidebar()
                    }
                }
                SidebarFunction.HISTORY -> {
                    icon.setImageResource(R.drawable.ic_history)
                    label.text = getString(R.string.sidebar_item_history)
                    item.setOnClickListener {
                        // Fase 3: HistoryActivity própria, não mais um
                        // placeholder dentro de SettingsActivity.
                        startActivity(Intent(this, HistoryActivity::class.java))
                        closeSidebar()
                    }
                }
            }
            sidebarItemsContainer.addView(item)
        }
    }

    /**
     * Popup com um checkbox por função existente, pra escolher quais
     * atalhos aparecem na sidebar - aberto pelo botão "+" circular no
     * rodapé do painel. Usa AlertDialog.Builder.setMultiChoiceItems, que
     * é puro framework/Activity (sem Material), consistente com o resto
     * dos diálogos do app.
     */
    private fun showManageSidebarShortcutsDialog() {
        val allFunctions = SidebarFunction.values()
        val labels = allFunctions.map {
            when (it) {
                SidebarFunction.DEVICE_MODE -> getString(R.string.sidebar_item_desktop_mode)
                SidebarFunction.DOWNLOADS -> getString(R.string.sidebar_item_downloads)
                SidebarFunction.SETTINGS -> getString(R.string.sidebar_item_settings)
                SidebarFunction.HISTORY -> getString(R.string.sidebar_item_history)
            }
        }.toTypedArray()
        val checkedStates = allFunctions.map { SidebarPrefsManager.isVisible(this, it) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.sidebar_manage_shortcuts_title)
            .setMultiChoiceItems(labels, checkedStates) { _, which, isChecked ->
                SidebarPrefsManager.setVisible(this, allFunctions[which], isChecked)
            }
            .setPositiveButton(R.string.dialog_done) { _, _ -> renderSidebarShortcuts() }
            .show()
    }

    /**
     * Único lugar do app onde um objeto WebView é instanciado. Só é
     * chamado depois que o WebViewUpgrade já terminou (com sucesso ou
     * erro) - nunca antes, pra não travar o provider errado.
     */
    private fun initWebView() {
        if (webView != null) return // já inicializada, evita duplicar

        val newWebView = WebView(this)
        webViewContainer.addView(
            newWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView = newWebView

        newWebView.settings.javaScriptEnabled = true
        newWebView.settings.domStorageEnabled = true
        // Necessário pra abrir arquivos locais (.html/.htm/.txt) recebidos
        // de outro app via VIEW com file:// ou content:// (ver os novos
        // intent-filters no AndroidManifest) - deixado explícito porque o
        // WebView AOSP embutido via WebViewUpgrade é um provider trocado
        // manualmente, então não dá pra confiar cegamente nos defaults
        // que o WebView de sistema teria.
        newWebView.settings.allowFileAccess = true
        newWebView.settings.allowContentAccess = true
        newWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Só atualiza o texto da barra de URL se o usuário não
                // estiver com o foco nela (editando) - evita sobrescrever
                // o que ele está digitando caso a página termine de
                // carregar nesse meio-tempo.
                if (!urlEditText.hasFocus()) {
                    urlEditText.setText(url ?: "")
                }
                // Fase 3: registra a visita no histórico. Usa view?.title
                // (título já carregado pela página nesse ponto) como
                // rótulo de exibição, caindo pra própria URL se o título
                // ainda não estiver disponível.
                HistoryManager.addVisit(this@MainActivity, view?.title, url)
            }
        }

        // Upload de arquivo: sites com <input type="file"> chamam
        // onShowFileChooser. Em vez do seletor padrão do Android
        // (DocumentsUI), abrimos nosso FileManagerActivity próprio em modo
        // "pick" - ao tocar num arquivo, ele já volta selecionado pro site.
        newWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = Intent(this@MainActivity, FileManagerActivity::class.java)
                intent.putExtra(FileManagerActivity.EXTRA_MODE, FileManagerActivity.MODE_PICK)
                startActivityForResult(intent, REQUEST_CODE_FILE_CHOOSER)
                return true
            }
        }

        // Download de arquivo: agora via BrowserDownloadManager, que
        // enfileira no DownloadManager do sistema E inicia o
        // DownloadForegroundService com notificação de progresso real
        // (substituindo a notificação básica automática do DownloadManager).
        newWebView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            BrowserDownloadManager.startDownload(this, url, userAgent, contentDisposition, mimeType)
        }

        // Aplica o modo Desktop/Mobile salvo (padrão: mobile) antes de
        // qualquer carregamento, e reflete no texto do botão.
        DesktopModeManager.applyTo(this, newWebView)
        updateDesktopModeButtonLabel()

        // Não navega mais direto pro Google: a tela inicial agora é a
        // própria Home do app (busca grande estilo Chromium). A WebView
        // só carrega algo quando o usuário confirma uma busca/URL - ou
        // quando existe uma URL externa pendente (app aberto via link de
        // outro app, com o XaulinXs AOSP Browser como navegador padrão).
        pendingExternalUrl?.let { url ->
            showWebView()
            newWebView.loadUrl(url)
            pendingExternalUrl = null
        }

        updateKernelInfo()
    }

    /**
     * Atualiza o rótulo e o ícone do item "Desktop/Mobile" dentro da
     * sidebar - substitui o antigo botão da toolbar (btnDesktopMode). O
     * item é recriado inteiro (renderSidebarShortcuts) sempre que o modo
     * muda, então esta função só existe pra ser chamada logo após o
     * toggle, sem esperar a Activity inteira ser recriada.
     */
    private fun updateDesktopModeButtonLabel() {
        renderSidebarShortcuts()
    }

    private fun updateKernelInfo() {
        val userAgent = WebSettings.getDefaultUserAgent(this)
        val webViewVersion = webViewVersionRegex.find(userAgent)?.groupValues?.get(1) ?: "desconhecida"
        val packageName = WebViewUpgrade.getUpgradeWebViewPackageName()
            ?: WebViewUpgrade.getSystemWebViewPackageName()
            ?: "desconhecido"
        val infoText = getString(R.string.kernel_info_format, webViewVersion, packageName)
        kernelInfo.text = infoText
        // Mesma informação também no rodapé da sidebar, pra quem navega
        // com o painel aberto e não passa pela Home.
        sidebarWebViewVersion.text = infoText
    }

    override fun onDestroy() {
        WebViewUpgrade.removeUpgradeCallback(upgradeCallback)
        super.onDestroy()
    }

    override fun onBackPressed() {
        val currentWebView = webView
        when {
            // Painel lateral aberto tem prioridade sobre qualquer outra
            // navegação - botão físico/gesto de voltar só fecha o painel,
            // igual ao comportamento padrão de um drawer/overlay lateral.
            sidebarRoot.visibility == View.VISIBLE -> closeSidebar()
            navToolbar.visibility == View.VISIBLE && currentWebView != null && currentWebView.canGoBack() -> {
                currentWebView.goBack()
            }
            navToolbar.visibility == View.VISIBLE -> {
                // Fim do histórico de navegação: volta pra Home em vez de
                // fechar o app, igual ao comportamento do Chrome.
                showHome()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            // Vem do FileManagerActivity em modo "pick" - data.getData() é o
            // arquivo local escolhido (ou null se o usuário cancelou/voltou).
            val callback = filePathCallback ?: return
            val result = if (resultCode == Activity.RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            callback.onReceiveValue(result)
            filePathCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
