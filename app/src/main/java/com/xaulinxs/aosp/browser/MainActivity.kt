package com.xaulinxs.aosp.browser

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.norman.webviewup.lib.UpgradeCallback
import com.norman.webviewup.lib.WebViewUpgrade
import com.xaulinxs.funcoes.AdBlockManager
import com.xaulinxs.funcoes.CookiePrefsManager
import com.xaulinxs.funcoes.DesktopModeManager
import com.xaulinxs.funcoes.FileManagerActivity
import com.xaulinxs.funcoes.HistoryManager
import com.xaulinxs.funcoes.SearchEngineManager
import com.xaulinxs.funcoes.ShortcutManager
import com.xaulinxs.funcoes.download.BrowserDownloadManager
import com.xaulinxs.funcoes.download.BlobDownloadBridge
import com.xaulinxs.funcoes.download.DownloadFileNameResolver
import com.xaulinxs.aosp.browser.widget.SidebarFunction
import com.xaulinxs.aosp.browser.widget.SidebarPrefsManager
import com.xaulinxs.aosp.browser.widget.SidebarSizeManager
import com.xaulinxs.aosp.browser.widget.SidebarSizeMode
import com.xaulinxs.aosp.browser.widget.VoiceSearchDialog
import com.xaulinxs.aosp.browser.widget.ZoomPrefsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.JavascriptInterface
import android.os.Handler
import android.os.Looper
import android.os.Message
import java.io.File

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

    // XAULINXS_FIX_V3_LOGIN: popup real de window.open() (login Google/GSI etc.)
    private var popupDialog: android.app.Dialog? = null
    private var popupWebView: WebView? = null

    // XAULINXS_FIX_V2: tela cheia de video (WebChromeClient.onShowCustomView)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousUiVisibility: Int = 0

    private lateinit var webViewContainer: FrameLayout
    private lateinit var homeLayout: LinearLayout
    private lateinit var navToolbar: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var searchMicIcon: ImageView
    private lateinit var urlEditText: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var kernelInfo: TextView
    private lateinit var sidebarWebViewVersion: TextView
    private lateinit var shortcutsContainer: LinearLayout
    private lateinit var btnAddShortcut: FrameLayout

    // Barra de atalhos (sidebar) de função - substitui a antiga bottomBar
    // e os botões Desktop/Mobile da toolbar. Ver view_sidebar.xml. Não
    // tem mais um botão externo pra "abrir": ela é sempre visível,
    // colapsada (só ícones) ou expandida, e a troca entre os dois
    // estados é feita pela alça sidebarDragHandle.
    private lateinit var sidebarRoot: FrameLayout
    private lateinit var sidebarItemsContainer: LinearLayout
    private lateinit var sidebarDragHandle: View
    private lateinit var sidebarManageRow: LinearLayout
    private lateinit var sidebarBottomHeader: LinearLayout
    private lateinit var sidebarTitleText: TextView
    private lateinit var btnCloseSidebar: ImageButton
    private lateinit var btnManageSidebarShortcuts: FrameLayout
    private lateinit var btnResizeSidebar: ImageButton

    // true = barra expandida (tudo visível: kernel no topo, rótulos dos
    // atalhos, cabeçalho com título+hambúrguer no rodapé). false = só a
    // faixa fina de ícones. Começa colapsada por padrão (ver onCreate) -
    // e é lembrada entre aberturas via SidebarSizeManager.getExpanded().
    private var sidebarExpanded = false

    // URL recebida de fora (ex: link clicado em outro app, com o
    // XaulinXs AOSP Browser definido como navegador padrão) - guardada
    // aqui porque o WebViewUpgrade é assíncrono e a WebView pode ainda
    // não existir quando o intent chega em onCreate()/onNewIntent().
    private var pendingExternalUrl: String? = null

    // XAULINXS_MEDIA_LONGPRESS_MENU: última posição tocada na WebView (ACTION_DOWN), em
    // pixels de tela - convertida pra CSS px (dividindo por
    // webView.scale) na hora de montar o JS de detecção. Usada pelo
    // long-press pra saber ONDE checar se existe imagem/vídeo.
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Handler pra rodar no thread principal o callback do
    // JavascriptInterface (evaluateJavascript/addJavascriptInterface
    // chamam de uma thread de WebCore, nunca da UI thread).
    private val mediaMenuHandler = Handler(Looper.getMainLooper())
    private val blobBridge by lazy { BlobDownloadBridge(this) }

    // true quando o app foi aberto pelo widget de busca (4x1, estilo
    // Chromium) - sinaliza que, assim que a Home estiver visível, o
    // campo de busca deve receber foco e abrir o teclado automaticamente.
    private var focusSearchOnHomeRequested = false

    // true quando o app foi aberto pelo ícone de microfone do widget de
    // busca - sinaliza que, assim que a Home estiver visível, o popup de
    // busca por voz deve abrir sozinho (sem precisar tocar de novo no
    // ícone dentro do app).
    private var voiceSearchOnHomeRequested = false

    companion object {
        /** Extra usado pelo BrowserSearchWidgetProvider para abrir o app já com o foco no campo de busca. */
        const val EXTRA_FOCUS_SEARCH = "com.xaulinxs.aosp.browser.extra.FOCUS_SEARCH"
        /** Extra usado pelo BrowserSearchWidgetProvider para abrir o app já disparando a busca por voz. */
        const val EXTRA_START_VOICE_SEARCH = "com.xaulinxs.aosp.browser.extra.START_VOICE_SEARCH"
        private const val REQUEST_CODE_FILE_CHOOSER = 100
        private const val REQUEST_CODE_NOTIFICATIONS = 101
        private const val REQUEST_CODE_RECORD_AUDIO = 102
        private const val TAG = "MainActivity"

        // Largura da barra de atalhos colapsada (só ícones) e a largura
        // "padrão" quando expandida (usada em todo modo de espaço exceto
        // Tela Toda, onde a expandida vira a tela inteira - ver
        // expandedSidebarWidthPx()). 80dp colapsada dá espaço de sobra
        // pro ícone (22dp) + paddings do item (16dp de cada lado) mais a
        // faixa da alça de arrastar (16dp) sem comprimir nada - com
        // menos que isso o ícone ficava espremido/cortado.
        private const val SIDEBAR_COLLAPSED_WIDTH_DP = 80f
        private const val SIDEBAR_EXPANDED_WIDTH_DP = 240f
        private const val SIDEBAR_RESIZE_ANIMATION_MS = 180L
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
        searchMicIcon = findViewById(R.id.searchMicIcon)
        searchMicIcon.setOnClickListener { startVoiceSearch() }
        urlEditText = findViewById(R.id.urlEditText)
        btnBack = findViewById(R.id.btnBack)
        btnReload = findViewById(R.id.btnReload)
        kernelInfo = findViewById(R.id.kernelInfo)
        sidebarWebViewVersion = findViewById(R.id.sidebarWebViewVersion)
        shortcutsContainer = findViewById(R.id.shortcutsContainer)
        btnAddShortcut = findViewById(R.id.btnAddShortcut)

        sidebarRoot = findViewById(R.id.sidebarInclude)
        sidebarItemsContainer = findViewById(R.id.sidebarItemsContainer)
        sidebarDragHandle = findViewById(R.id.sidebarDragHandle)
        sidebarManageRow = findViewById(R.id.sidebarManageRow)
        sidebarBottomHeader = findViewById(R.id.sidebarBottomHeader)
        sidebarTitleText = findViewById(R.id.sidebarTitleText)
        btnCloseSidebar = findViewById(R.id.btnCloseSidebar)
        btnManageSidebarShortcuts = findViewById(R.id.btnManageSidebarShortcuts)
        btnResizeSidebar = findViewById(R.id.btnResizeSidebar)

        btnAddShortcut.setOnClickListener { showAddShortcutDialog() }
        renderShortcuts()

        // Estado inicial (colapsada/expandida) vem do que o usuário
        // deixou salvo da última vez - precisa ser lido ANTES de
        // renderSidebarShortcuts()/applySidebarSizeMode(), já que os
        // dois consultam sidebarExpanded pra decidir rótulo e largura.
        sidebarExpanded = SidebarSizeManager.getExpanded(this)
        setExpandedContentVisible(sidebarExpanded)
        btnCloseSidebar.setOnClickListener { toggleSidebarExpanded() }
        btnManageSidebarShortcuts.setOnClickListener { showManageSidebarShortcutsDialog() }
        btnResizeSidebar.setOnClickListener { showResizeSidebarDialog() }
        setupSidebarDragHandle()
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
        applyPendingVoiceSearchIfNeeded()

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
        applyPendingVoiceSearchIfNeeded()
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
        // Vindo do ícone de microfone do widget de busca: mostra a Home e
        // já abre o popup de busca por voz sozinho.
        if (intent?.getBooleanExtra(EXTRA_START_VOICE_SEARCH, false) == true) {
            voiceSearchOnHomeRequested = true
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

    /**
     * Abre o popup de busca por voz pendente, se solicitado pelo ícone de
     * microfone do widget. Mesmo padrão de applyPendingSearchFocusIfNeeded():
     * chamado depois que a Home já está pronta, já que o Intent pode
     * chegar antes da UI estar totalmente montada.
     */
    private fun applyPendingVoiceSearchIfNeeded() {
        if (!voiceSearchOnHomeRequested) return
        voiceSearchOnHomeRequested = false
        showHome()
        startVoiceSearch()
    }

    /**
     * Ponto de entrada da busca por voz (ícone de microfone da Home e do
     * widget): confirma a permissão de microfone antes de abrir o popup
     * próprio do app (VoiceSearchDialog, via SpeechRecognizer nativo).
     */
    private fun startVoiceSearch() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_RECORD_AUDIO
            )
            return
        }

        VoiceSearchDialog(this) { recognizedText ->
            searchInput.setText(recognizedText)
            searchInput.setSelection(recognizedText.length)
            submitQuery(recognizedText)
        }.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startVoiceSearch()
            } else {
                Toast.makeText(this, R.string.voice_search_error_no_permission, Toast.LENGTH_SHORT).show()
            }
        }
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
            item.setOnLongClickListener {
                showEditShortcutDialog(shortcut)
                true
            }
            shortcutsContainer.addView(item)
        }
    }

    /**
     * Popup de pressionar-e-segurar em cima de um atalho da Home: permite
     * editar nome/URL (Salvar) ou excluir o atalho. Mesmo esqueleto de
     * showAddShortcutDialog(), só que pré-preenchido com os valores atuais
     * e com um terceiro botão neutro pra exclusão.
     */
    private fun showEditShortcutDialog(shortcut: ShortcutManager.Shortcut) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.shortcuts_dialog_name_hint)
        nameInput.setText(shortcut.name)
        container.addView(nameInput)

        val urlInput = EditText(this)
        urlInput.hint = getString(R.string.shortcuts_dialog_url_hint)
        urlInput.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        urlInput.setText(shortcut.url)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.shortcuts_dialog_edit_title)
            .setView(container)
            .setPositiveButton(R.string.shortcuts_dialog_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, R.string.shortcuts_dialog_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    if (!url.contains("://")) {
                        url = "https://$url"
                    }
                    ShortcutManager.updateShortcut(this, shortcut, name, url)
                    renderShortcuts()
                }
            }
            .setNeutralButton(R.string.shortcuts_dialog_delete) { _, _ ->
                ShortcutManager.removeShortcut(this, shortcut)
                renderShortcuts()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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

    /** Converte um valor em dp pra pixels, usando a densidade da tela atual. */
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun collapsedSidebarWidthPx(): Int = dp(SIDEBAR_COLLAPSED_WIDTH_DP)

    /**
     * Largura-alvo da barra quando EXPANDIDA. Segue a configuração de
     * espaço escolhida no popup de redimensionar (mesmo
     * SidebarSizeMode que já controlava a altura): no modo Tela Toda a
     * barra expande até ocupar a largura inteira do contentArea; nos
     * outros três modos, a largura expandida padrão é 240dp. A barra
     * COLAPSADA nunca respeita esse valor - sempre volta pra faixa fina
     * de ícones (collapsedSidebarWidthPx()), mesmo em modo Tela Toda.
     */
    private fun expandedSidebarWidthPx(): Int {
        return if (SidebarSizeManager.getSizeMode(this) == SidebarSizeMode.FULLSCREEN) {
            (sidebarRoot.parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        } else {
            dp(SIDEBAR_EXPANDED_WIDTH_DP)
        }
    }

    private fun setSidebarWidth(widthPx: Int) {
        val params = sidebarRoot.layoutParams as FrameLayout.LayoutParams
        params.width = widthPx
        sidebarRoot.layoutParams = params
    }

    /**
     * Mostra/esconde os blocos que só cabem com a barra expandida:
     * informação de kernel (topo), botão "+" de gerenciar atalhos, o
     * título "Atalhos" e o botão de redimensionar (rodapé). O botão
     * hambúrguer (sidebarBottomHeader como um todo) NÃO entra nessa
     * lista - fica sempre visível, colapsada ou não, é o único controle
     * fixo pra expandir de novo sem precisar arrastar a alça; só a
     * gravidade da linha muda pra centralizar ele sozinho quando os
     * outros dois somem. Não mexe nos rótulos dos itens de função -
     * isso é responsabilidade de renderSidebarShortcuts(), que lê
     * sidebarExpanded na hora de montar cada item; por isso todo lugar
     * que muda sidebarExpanded chama as duas funções junto.
     */
    private fun setExpandedContentVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        sidebarWebViewVersion.visibility = visibility
        sidebarManageRow.visibility = visibility
        sidebarTitleText.visibility = visibility
        btnResizeSidebar.visibility = visibility
        sidebarBottomHeader.gravity = if (visible) Gravity.CENTER_VERTICAL else Gravity.CENTER
    }

    /**
     * Anima a largura da barra até o alvo do novo estado (colapsada ou
     * expandida), persiste a escolha (SidebarSizeManager.setExpanded)
     * pra lembrar entre aberturas do app, e atualiza os blocos/rótulos
     * que só existem expandida.
     *
     * `animate = false` é usado só na inicialização (onCreate), pra
     * aplicar o estado salvo sem uma animação de abertura desnecessária
     * assim que a tela aparece.
     */
    private fun applySidebarExpandedState(expanded: Boolean, animate: Boolean = true) {
        sidebarExpanded = expanded
        SidebarSizeManager.setExpanded(this, expanded)

        val targetWidth = if (expanded) expandedSidebarWidthPx() else collapsedSidebarWidthPx()
        val currentWidth = (sidebarRoot.layoutParams as? FrameLayout.LayoutParams)?.width
            ?.takeIf { it > 0 } ?: collapsedSidebarWidthPx()

        // Colapsando: some com rótulo/kernel/cabeçalho JÁ, não dá pra
        // caber texto na largura final de 56dp e ficaria cortado durante
        // a animação. Expandindo: só reaparece quando a animação
        // terminar (listener abaixo), senão o texto "estica" torto
        // enquanto a barra ainda está estreita no meio do gesto.
        if (!expanded) {
            setExpandedContentVisible(false)
            renderSidebarShortcuts()
        }

        if (!animate) {
            setSidebarWidth(targetWidth)
            if (expanded) {
                setExpandedContentVisible(true)
                renderSidebarShortcuts()
            }
            return
        }

        ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = SIDEBAR_RESIZE_ANIMATION_MS
            addUpdateListener { setSidebarWidth(it.animatedValue as Int) }
            if (expanded) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        setExpandedContentVisible(true)
                        renderSidebarShortcuts()
                    }
                })
            }
            start()
        }
    }

    private fun toggleSidebarExpanded() = applySidebarExpandedState(!sidebarExpanded)

    /** Usado pelos itens de função (Downloads/Configurações/Histórico) depois de navegar. */
    private fun collapseSidebar() = applySidebarExpandedState(false)

    /**
     * Liga o gesto de puxar com o dedo na alça fina (sidebarDragHandle)
     * colada na borda interna da barra:
     *
     * - Arrastar: ajusta a largura em tempo real, entre
     *   collapsedSidebarWidthPx() e expandedSidebarWidthPx() - como a
     *   barra agora fica colada na borda "end" (direita em LTR), puxar
     *   o dedo pra ESQUERDA (deltaX negativo) aumenta a largura, puxar
     *   pra DIREITA encolhe. Ao cruzar o meio do caminho, já
     *   mostra/esconde rótulo e cabeçalho, sem esperar soltar o dedo.
     * - Soltar depois de arrastar: assenta (snap) no estado mais
     *   próximo de onde o dedo parou (aberta ou colapsada por completo).
     * - Tocar sem arrastar (movimento menor que o touch slop do
     *   sistema): funciona como um toque normal, alternando o estado -
     *   pra quem prefere tocar a arrastar.
     */
    private fun setupSidebarDragHandle() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downWidth = 0
        var isDragging = false

        sidebarDragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downWidth = (sidebarRoot.layoutParams as? FrameLayout.LayoutParams)?.width
                        ?.takeIf { it > 0 } ?: collapsedSidebarWidthPx()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    if (!isDragging && kotlin.math.abs(deltaX) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val min = collapsedSidebarWidthPx()
                        val max = expandedSidebarWidthPx()
                        val newWidth = (downWidth - deltaX.toInt()).coerceIn(min, max)
                        setSidebarWidth(newWidth)
                        val expandedNow = newWidth > (min + max) / 2
                        if (expandedNow != sidebarExpanded) {
                            sidebarExpanded = expandedNow
                            setExpandedContentVisible(expandedNow)
                            renderSidebarShortcuts()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        // sidebarExpanded já reflete o lado mais próximo
                        // de onde o dedo soltou (atualizado no último
                        // cruzamento do meio, acima) - só falta terminar
                        // a animação até a largura final desse estado.
                        applySidebarExpandedState(sidebarExpanded)
                    } else {
                        toggleSidebarExpanded()
                    }
                    true
                }
                else -> false
            }
        }
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
     * FULLSCREEN: ocupa a tela toda (contentArea inteiro) - largura só
     * quando EXPANDIDA (ver expandedSidebarWidthPx()); colapsada, a
     * largura continua sendo a faixa fina de ícones mesmo nesse modo.
     *
     * A barra fica colada na borda "end" (direita em LTR) - trocado de
     * "start" (esquerda) porque era o lado que não era o desejado.
     */
    private fun applySidebarSizeMode() {
        val params = sidebarRoot.layoutParams as FrameLayout.LayoutParams
        val parentHeight = (sidebarRoot.parent as? View)?.height ?: 0
        when (SidebarSizeManager.getSizeMode(this)) {
            SidebarSizeMode.FULL -> {
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.END or Gravity.TOP
            }
            SidebarSizeMode.TOP_HALF -> {
                params.height = if (parentHeight > 0) parentHeight / 2 else FrameLayout.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.END or Gravity.TOP
            }
            SidebarSizeMode.BOTTOM_HALF -> {
                params.height = if (parentHeight > 0) parentHeight / 2 else FrameLayout.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.END or Gravity.BOTTOM
            }
            SidebarSizeMode.FULLSCREEN -> {
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.END or Gravity.TOP
            }
        }
        params.width = if (sidebarExpanded) expandedSidebarWidthPx() else collapsedSidebarWidthPx()
        sidebarRoot.layoutParams = params
    }

    /**
     * Popup com as 4 opções de tamanho da sidebar (padrão, metade de
     * cima, metade de baixo, tela toda) - aberto pelo botão de
     * redimensionar, agora no rodapé do painel (sidebarBottomHeader,
     * junto do hambúrguer e do título "Atalhos"). Escolha é persistida
     * e aplicada imediatamente, sem precisar recolher/reexpandir a barra.
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
     * SidebarPrefsManager.visibleFunctions() - chamado no onCreate,
     * sempre que a visibilidade de algum atalho muda (popup de
     * gerenciar), o modo Desktop/Mobile é alternado (pra atualizar o
     * rótulo/ícone daquele item específico), e toda vez que
     * sidebarExpanded muda de estado (colapsar esconde o rótulo de cada
     * item e centraliza só o ícone; expandir traz o rótulo de volta).
     */
    private fun renderSidebarShortcuts() {
        sidebarItemsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (function in SidebarPrefsManager.visibleFunctions(this)) {
            val item = inflater.inflate(R.layout.view_sidebar_item, sidebarItemsContainer, false) as LinearLayout
            val icon = item.findViewById<ImageView>(R.id.sidebarItemIcon)
            val label = item.findViewById<TextView>(R.id.sidebarItemLabel)

            // Colapsada: só o ícone, centralizado na faixa fina (o
            // rótulo com layout_weight="1" não ocupa espaço nenhum
            // quando GONE, então o item encolhe naturalmente pro
            // tamanho do ícone). Expandida: layout original, ícone +
            // rótulo lado a lado alinhados à esquerda da barra.
            label.visibility = if (sidebarExpanded) View.VISIBLE else View.GONE
            item.gravity = if (sidebarExpanded) Gravity.CENTER_VERTICAL else Gravity.CENTER

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
                        collapseSidebar()
                    }
                }
                SidebarFunction.SETTINGS -> {
                    icon.setImageResource(R.drawable.ic_settings)
                    label.text = getString(R.string.sidebar_item_settings)
                    item.setOnClickListener {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        collapseSidebar()
                    }
                }
                SidebarFunction.HISTORY -> {
                    icon.setImageResource(R.drawable.ic_history)
                    label.text = getString(R.string.sidebar_item_history)
                    item.setOnClickListener {
                        // Fase 3: HistoryActivity própria, não mais um
                        // placeholder dentro de SettingsActivity.
                        startActivity(Intent(this, HistoryActivity::class.java))
                        collapseSidebar()
                    }
                }
                SidebarFunction.ZOOM -> {
                    icon.setImageResource(R.drawable.ic_zoom)
                    label.text = getString(R.string.sidebar_item_zoom)
                    item.setOnClickListener {
                        // Recolhe a barra antes de abrir o popup, pra
                        // sobrar a tela toda mostrando o preview em
                        // tempo real do zoom na página por trás do
                        // diálogo.
                        collapseSidebar()
                        showZoomDialog()
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
                SidebarFunction.ZOOM -> getString(R.string.sidebar_item_zoom)
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
        // XAULINXS_FIX_V3_LOGIN: GSI abre o login via window.open() - sem
        // multiplas janelas ele renderiza no frame errado (tela branca em
        // /gsi/transform). databaseEnabled/dom storage ajudam o Turnstile.
        newWebView.settings.databaseEnabled = true
        newWebView.settings.setSupportMultipleWindows(true)
        newWebView.settings.javaScriptCanOpenWindowsAutomatically = true
        CookiePrefsManager.apply(this, newWebView)
        // XAULINXS_FIX_V2: videos/segmentos http dentro de pagina https eram
        // bloqueados (padrao NEVER_ALLOW); cookies de terceiros sao
        // necessarios para varios players/CDNs; autoplay sem gesto
        // evita players que chamam play() por script e falham.
        newWebView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        newWebView.settings.mediaPlaybackRequiresUserGesture = false

        // XAULINXS_MEDIA_LONGPRESS_MENU: guarda a posição do toque pra usar no long-press,
        // e nunca consome o evento aqui (return false) - scroll, zoom
        // por pinça e clique normal continuam funcionando exatamente
        // como antes.
        newWebView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            false
        }

        // XAULINXS_MEDIA_LONGPRESS_MENU: ponte JS -> Kotlin usada só pra descobrir se o
        // ponto tocado tem uma imagem ou vídeo por baixo (ver
        // MEDIA_DETECTION_JS). Não expõe nada além disso.
        newWebView.addJavascriptInterface(MediaLongPressBridge(), "XaulinXsMediaBridge")
        newWebView.addJavascriptInterface(blobBridge, "XaulinXsBlobBridge")

        newWebView.setOnLongClickListener {
            val current = webView ?: return@setOnLongClickListener false
            val scale = current.scale.takeIf { it > 0f } ?: 1f
            val cssX = (lastTouchX / scale).toInt()
            val cssY = (lastTouchY / scale).toInt()
            current.evaluateJavascript(mediaDetectionJs(cssX, cssY), null)
            // XAULINXS_FIX_V2: NAO consome mais o long-press (return false). Antes
            // ele era sempre consumido e isso matava a selecao manual de
            // texto do WebView em qualquer pagina. Agora o JS acima roda
            // em paralelo e so abre o popup de midia se houver imagem/video
            // sob o dedo; sobre texto, a selecao nativa funciona normal.
            false
        }

        newWebView.webViewClient = object : WebViewClient() {
            /**
             * Adblock: intercepta cada sub-recurso (imagem, script,
             * iframe de anúncio) ANTES do WebView buscar na rede, e
             * devolve uma resposta vazia se o host bater com a lista de
             * bloqueio (AdBlockManager). Nunca mexe em isForMainFrame ==
             * true - a navegação da página em si nunca é bloqueada, só
             * os recursos que ela carrega por baixo.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null && !request.isForMainFrame) {
                    val url = request.url?.toString()
                    if (url != null && AdBlockManager.shouldBlock(this@MainActivity, url)) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                enableTextSelection(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                enableTextSelection(view)
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
                // Reaplica o zoom salvo (ZoomPrefsManager) a cada página
                // nova - o CSS injetado não sobrevive de uma navegação
                // pra outra, então precisa reinjetar sempre que o
                // documento termina de carregar.
                applyPageZoom(view)
            }
        }

        // Upload de arquivo: sites com <input type="file"> chamam
        // onShowFileChooser. Em vez do seletor padrão do Android
        // (DocumentsUI), abrimos nosso FileManagerActivity próprio em modo
        // "pick" - ao tocar num arquivo, ele já volta selecionado pro site.
        newWebView.webChromeClient = object : WebChromeClient() {
            // XAULINXS_FIX_V2 ---- video em tela cheia / poster / DRM ----
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                val decor = window.decorView as FrameLayout
                previousUiVisibility = decor.systemUiVisibility
                decor.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                exitCustomView()
            }

            // Sem poster padrao, alguns WebViews lancam NPE ao preparar
            // <video> sem atributo poster.
            override fun getDefaultVideoPoster(): android.graphics.Bitmap? {
                return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            }

            // Conteudo protegido (EME/DRM): so libera o ID de midia
            // protegida; camera/microfone continuam negados.
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                if (request == null) return
                val allowed = request.resources
                    .filter { it == android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    .toTypedArray()
                if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
            }

            // XAULINXS_FIX_V3_LOGIN ---- janelas (window.open / target=_blank) ----
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (view == null || resultMsg == null) return false
                val hit = view.hitTestResult.type
                val isLink = hit == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hit == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                // Mini bloqueador de pop-up: janela sem gesto do usuario e que
                // nao veio de um link e recusada.
                if (!isUserGesture && !isLink) return false
                return if (isLink) openLinkInCurrentView(resultMsg) else openPopupWindow(resultMsg)
            }

            override fun onCloseWindow(window: WebView?) {
                if (window != null && window === popupWebView) closePopupWindow()
            }

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
            handleDownloadRequest(url, userAgent, contentDisposition, mimeType)
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

    // XAULINXS_FIX_V2
    /**
     * Ponto único de entrada para downloads da WebView:
     *  - http/https -> DownloadManager (fluxo antigo, com notificação);
     *  - blob:      -> lido dentro da página via JS e salvo por BlobDownloadBridge;
     *  - data:      -> decodificado direto e salvo em Downloads.
     * Qualquer falha vira Toast (nunca mais derruba o app).
     */
    private fun handleDownloadRequest(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        // XAULINXS_DL_NAME_V1: nenhum download comeca sem o popup de confirmacao.
        try {
            val lower = url.lowercase()
            val isBlob = lower.startsWith("blob:")
            val isData = lower.startsWith("data:")
            val isHttp = lower.startsWith("http://") || lower.startsWith("https://")

            if (!isBlob && !isData && !isHttp) {
                Toast.makeText(this, R.string.download_unsupported_scheme, Toast.LENGTH_SHORT).show()
                return
            }
            // Evita empilhar varios popups para o mesmo link (ex.: toque duplo).
            if (downloadDialogUrl == url) return
            downloadDialogUrl = url

            if (isHttp) {
                // A sonda de cabecalhos usa rede: roda fora da thread principal.
                Thread {
                    val suggested = try {
                        DownloadFileNameResolver.resolveBlocking(url, userAgent, contentDisposition, mimeType)
                    } catch (e: Exception) {
                        DownloadFileNameResolver.resolveOffline(url, contentDisposition, mimeType)
                    }
                    runOnUiThread {
                        showDownloadConfirmDialog(url, suggested) { finalName ->
                            BrowserDownloadManager.startDownload(
                                this, url, userAgent, contentDisposition, mimeType, finalName
                            )
                        }
                    }
                }.start()
            } else {
                val suggested = DownloadFileNameResolver.resolveOffline(url, contentDisposition, mimeType)
                showDownloadConfirmDialog(url, suggested) { finalName ->
                    if (isBlob) {
                        webView?.evaluateJavascript(blobBridge.buildFetchScript(url, finalName), null)
                    } else {
                        blobBridge.saveDataUri(url, finalName)
                    }
                }
            }
        } catch (e: Exception) {
            downloadDialogUrl = null
            Log.e("BrowserDownload", "Falha ao iniciar download: $url", e)
            Toast.makeText(this, R.string.blob_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Link cujo popup de confirmacao esta aberto (ou sendo preparado). */
    private var downloadDialogUrl: String? = null

    /**
     * Popup de confirmacao de download: nome do arquivo editavel no topo e
     * botoes Confirmar / Cancelar. onConfirm so roda depois do Confirmar.
     * Mesmo padrao sem Material do resto do app (AlertDialog + EditText).
     */
    private fun showDownloadConfirmDialog(
        url: String,
        suggestedName: String,
        onConfirm: (String) -> Unit
    ) {
        if (isFinishing || isDestroyed) {
            downloadDialogUrl = null
            return
        }

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, 0)

        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.download_confirm_name_hint)
        nameInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        nameInput.maxLines = 3
        nameInput.setText(suggestedName)
        container.addView(nameInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.download_confirm_title)
            .setView(container)
            .setPositiveButton(R.string.download_confirm_ok, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setOnDismissListener { downloadDialogUrl = null }
            .create()

        dialog.setOnShowListener {
            // Seleciona so o nome (sem a extensao) para facilitar a edicao.
            nameInput.requestFocus()
            val dot = suggestedName.lastIndexOf('.')
            nameInput.setSelection(0, if (dot > 0) dot else suggestedName.length)
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
            // Handler proprio no Confirmar: nome vazio nao fecha o popup.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = DownloadFileNameResolver.sanitize(nameInput.text.toString())
                if (typed.isEmpty()) {
                    Toast.makeText(this, R.string.download_confirm_empty_name, Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    try {
                        onConfirm(typed)
                    } catch (e: Exception) {
                        Log.e("BrowserDownload", "Falha ao iniciar download: $url", e)
                        Toast.makeText(this, R.string.blob_download_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Força a seleção manual de texto em qualquer página: sobrescreve
     * user-select/touch-callout via CSS (!important) e bloqueia, em fase
     * de captura, os handlers de "selectstart"/"copy" que sites usam para
     * impedir a seleção. Reaplicada em onPageCommitVisible/onPageFinished.
     */
    private fun enableTextSelection(view: WebView?) {
        view?.evaluateJavascript(
            """
            (function() {
                try {
                    var css = '*,*::before,*::after{-webkit-user-select:text !important;user-select:text !important;-webkit-touch-callout:default !important;}';
                    var st = document.getElementById('xaulinxs-select-style');
                    if (!st) {
                        st = document.createElement('style');
                        st.id = 'xaulinxs-select-style';
                        (document.head || document.documentElement).appendChild(st);
                    }
                    st.textContent = css;
                    if (!window.__xsSelectUnlock) {
                        window.__xsSelectUnlock = true;
                        ['selectstart', 'copy'].forEach(function(t) {
                            document.addEventListener(t, function(e) { e.stopImmediatePropagation(); }, true);
                        });
                        document.onselectstart = null;
                        if (document.body) document.body.onselectstart = null;
                    }
                } catch (e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    /** Sai do vídeo em tela cheia e restaura as barras do sistema. */
    private fun exitCustomView() {
        val v = customView ?: return
        val decor = window.decorView as FrameLayout
        decor.removeView(v)
        customView = null
        decor.systemUiVisibility = previousUiVisibility
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // XAULINXS_MEDIA_LONGPRESS_MENU
    /**
     * Ponte chamada pelo JavaScript injetado no long-press
     * (mediaDetectionJs). Roda numa thread interna do WebView, então
     * qualquer coisa que toque em Views precisa passar pelo
     * mediaMenuHandler antes.
     */
    private inner class MediaLongPressBridge {
        @JavascriptInterface
        fun onMediaFound(type: String, src: String) {
            if (src.isBlank()) return
            mediaMenuHandler.post {
                webView?.evaluateJavascript("window.getSelection().removeAllRanges();", null)
                showMediaContextMenu(type, src)
            }
        }
    }

    /**
     * JS injetado no ponto tocado (em CSS px): sobe a árvore de
     * ancestrais a partir de document.elementFromPoint() procurando,
     * nesta ordem, uma <img>, um <video>/<source> (usando currentSrc
     * quando disponível - é o que reflete a fonte realmente em
     * reprodução) ou um elemento com background-image no CSS
     * computado (comum em galerias que não usam <img> de verdade).
     * Resolve URLs relativas com "new URL(..., location.href)" antes
     * de devolver, pra sempre chegar no Kotlin como link absoluto.
     */
    private fun mediaDetectionJs(x: Int, y: Int): String = """
        (function() {
            try {
                var el = document.elementFromPoint($x, $y);
                while (el) {
                    var tag = el.tagName ? el.tagName.toUpperCase() : '';
                    if (tag === 'IMG' && el.src) {
                        XaulinXsMediaBridge.onMediaFound('image', new URL(el.src, location.href).href);
                        return;
                    }
                    if (tag === 'VIDEO') {
                        var vsrc = el.currentSrc || el.src || '';
                        if (!vsrc) {
                            var sourceEl = el.querySelector('source');
                            if (sourceEl && sourceEl.src) vsrc = sourceEl.src;
                        }
                        if (vsrc) {
                            XaulinXsMediaBridge.onMediaFound('video', new URL(vsrc, location.href).href);
                            return;
                        }
                    }
                    if (tag === 'SOURCE' && el.parentElement && el.parentElement.tagName === 'VIDEO' && el.src) {
                        XaulinXsMediaBridge.onMediaFound('video', new URL(el.src, location.href).href);
                        return;
                    }
                    var bg = window.getComputedStyle(el).backgroundImage;
                    if (bg && bg !== 'none' && !(el.innerText || '').trim()) {
                        var match = bg.match(/url\(["']?(.*?)["']?\)/);
                        if (match && match[1]) {
                            XaulinXsMediaBridge.onMediaFound('image', new URL(match[1], location.href).href);
                            return;
                        }
                    }
                    el = el.parentElement;
                }
            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Popup compacto (AlertDialog.setItems, mesmo padrão sem Material
     * já usado no resto do app) mostrado quando o long-press acha uma
     * imagem ou vídeo sob o dedo. type é "image" ou "video" - só muda
     * o rótulo do primeiro item (Baixar imagem / Baixar vídeo).
     */
    private fun showMediaContextMenu(type: String, url: String) {
        val downloadLabel = if (type == "video") {
            getString(R.string.media_menu_download_video)
        } else {
            getString(R.string.media_menu_download_image)
        }
        val options = arrayOf(
            downloadLabel,
            getString(R.string.media_menu_copy_link),
            getString(R.string.media_menu_new_webview)
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadMediaDirect(url)
                    1 -> copyMediaLinkToClipboard(url)
                    2 -> recreateWebViewWithUrl(url)
                }
            }
            .show()
    }

    /**
     * Dispara o download da mídia direto pela URL captada (sem passar
     * pelo WebView.setDownloadListener, já que aqui não existe uma
     * navegação/Content-Disposition do servidor disparando o
     * download - é um GET direto no link da imagem/vídeo). Só funciona
     * quando a URL é acessível publicamente sem autenticação extra
     * além dos cookies já salvos (CookieManager, usado dentro de
     * BrowserDownloadManager) - daí a ressalva "se o site permitir".
     */
    private fun downloadMediaDirect(url: String) {
        try {
            handleDownloadRequest(url, webView?.settings?.userAgentString, null, null)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.media_menu_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Mesmo padrão de HistoryActivity.copyLinkToClipboard(). */
    private fun copyMediaLinkToClipboard(url: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
        Toast.makeText(this, R.string.media_menu_link_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * "Abrir em novo WebView": não é uma nova aba - é literalmente
     * destruir a instância atual de WebView (removendo do
     * webViewContainer e chamando destroy(), pra liberar o processo
     * renderer/sandbox associado) e recriar do zero via
     * initWebView(), que já sabe carregar pendingExternalUrl assim
     * que a nova instância termina de ser montada.
     */
    private fun recreateWebViewWithUrl(url: String) {
        webView?.let { old ->
            webViewContainer.removeView(old)
            old.destroy()
        }
        webView = null
        pendingExternalUrl = url
        showWebView()
        initWebView()
    }

    private fun updateDesktopModeButtonLabel() {
        renderSidebarShortcuts()
    }

    /**
     * Monta a info de kernel exibida na Home e no topo da sidebar.
     *
     * A VERSÃO não vem mais de WebViewUpgrade.getUpgradeWebViewVersion()
     * como fonte principal - achado depois de reportado que o nome do
     * pacote aparecia certo mas a versão ficava presa numa antiga: esse
     * getter (lá na lib vendorizada, WebViewReplace.loadCurrentWebViewPackageInfo())
     * NÃO lê o .apk que foi de fato carregado - ele pergunta pro
     * WebViewUpdateService/WebView.getCurrentWebViewPackage() do PRÓPRIO
     * Android qual é "o" WebView ativo, e esse serviço pode devolver um
     * PackageInfo cacheado/desatualizado mesmo com o hook de troca
     * funcionando (o pacote bate porque é só um nome; a versão reportada
     * pelo serviço do sistema não necessariamente é resolvida de novo a
     * cada consulta).
     *
     * A correção: quando a troca foi reportada como bem-sucedida
     * (getUpgradeWebViewPackageName() não-nulo), lemos o PackageInfo
     * DIRETO do arquivo aosp_webview.apk extraído em
     * filesDir/BrowserApplication.WEBVIEW_ASSET_NAME (o mesmo arquivo que
     * WebViewUpgrade.upgrade() usou pra trocar) via
     * PackageManager.getPackageArchiveInfo() - isso lê o
     * AndroidManifest.xml de dentro do próprio apk e devolve o
     * versionName/versionCode exatos gravados nele, sem depender de
     * nenhum cache do sistema. Só cai pro getter da lib
     * (getUpgradeWebViewVersion()) se por algum motivo esse arquivo não
     * existir mais ou não puder ser parseado.
     */
    private fun updateKernelInfo() {
        val upgradedPackageName = WebViewUpgrade.getUpgradeWebViewPackageName()

        val packageName: String?
        val version: String?
        if (upgradedPackageName != null) {
            val archiveInfo = readExtractedWebViewApkInfo()
            packageName = archiveInfo?.packageName ?: upgradedPackageName
            version = archiveInfo?.let { formatApkVersion(it) } ?: WebViewUpgrade.getUpgradeWebViewVersion()
        } else {
            packageName = WebViewUpgrade.getSystemWebViewPackageName()
            version = WebViewUpgrade.getSystemWebViewPackageVersion()
        }

        val label = packageName?.let { webViewPackageLabel(it) } ?: getString(R.string.kernel_name_unknown)
        val versionText = version ?: getString(R.string.kernel_version_unknown)
        val packageText = packageName ?: getString(R.string.kernel_package_unknown)

        val infoText = getString(R.string.kernel_info_format, label, versionText, packageText)
        kernelInfo.text = infoText
        // Mesma informação também no topo da sidebar, pra quem navega
        // com o painel expandido e não passa pela Home.
        sidebarWebViewVersion.text = infoText
    }

    /**
     * Lê o PackageInfo real direto do arquivo .apk extraído pro
     * armazenamento privado do app (ver BrowserApplication.kt) -
     * PackageManager.getPackageArchiveInfo() parseia o
     * AndroidManifest.xml de dentro do arquivo sem precisar que ele
     * esteja "instalado" de verdade, então reflete exatamente o que está
     * gravado nesse apk específico, ignorando qualquer cache do
     * WebViewUpdateService do sistema. Retorna null se o arquivo não
     * existir (troca ainda não rodou/falhou) ou não puder ser parseado.
     */
    private fun readExtractedWebViewApkInfo(): PackageInfo? {
        val apkFile = File(filesDir, BrowserApplication.WEBVIEW_ASSET_NAME)
        if (!apkFile.exists()) return null
        return try {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * versionName sozinho (ex: "116.0.5845.0") já é o de sempre, mas
     * inclui o versionCode entre parênteses quando disponível - um
     * número inteiro que só existe pra identificar builds de forma
     * inequívoca, útil aqui como confirmação extra de que a versão
     * exibida é mesmo a do apk certo (o motivo de tudo isso ter sido
     * revisado). getLongVersionCode() só existe a partir do API 28; em
     * versões antigas cai pro campo versionCode (int, deprecated mas
     * funcional).
     */
    private fun formatApkVersion(packageInfo: PackageInfo): String? {
        val versionName = packageInfo.versionName ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return "$versionName ($versionCode)"
    }

    /**
     * Nome de exibição (label) do apk do WebView identificado pelo pacote
     * informado - lido direto do PackageManager, não um texto nosso. Cai
     * pro próprio nome do pacote se o PackageManager não conseguir
     * resolver o ApplicationInfo (pacote não instalado como app comum
     * visível, por exemplo).
     */
    private fun webViewPackageLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /**
     * Aplica o zoom de página (ZoomPrefsManager, 50%-200%) na WebView via
     * injeção de JavaScript, ajustando a propriedade CSS não-padrão
     * "zoom" do elemento raiz do documento - suportada pelo motor
     * Chromium por trás do WebView AOSP (é a mesma usada pelo próprio
     * Chrome/Chromium internamente pro zoom de página do desktop), o que
     * dá um zoom visual de verdade (imagens, layout, tudo escala junto),
     * diferente de só aumentar o tamanho do texto
     * (WebSettings.textZoom, que deixa o resto do layout do tamanho
     * original).
     *
     * Reaplicado a cada "onPageFinished" (o estilo injetado não
     * sobrevive a uma navegação nova) e toda vez que o usuário mexe no
     * slider (showZoomDialog()/SettingsActivity), pra dar o preview
     * imediato na página que já está carregada.
     */
    private fun applyPageZoom(view: WebView?, percent: Int = ZoomPrefsManager.getZoomPercent(this)) {
        view?.evaluateJavascript(
            "document.documentElement.style.zoom='${percent}%';",
            null
        )
    }

    /**
     * Popup rápido de zoom, aberto pelo atalho "Zoom" da sidebar - slider
     * de 50% a 200% (SeekBar puro, sem Material) com preview em tempo
     * real na página atual e um botão pra voltar a 100%. O valor
     * escolhido é persistido em ZoomPrefsManager, então continua valendo
     * na próxima página/próxima abertura do app, e fica em sincronia com
     * o slider equivalente em SettingsActivity.
     */
    private fun showZoomDialog() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(padding, padding, padding, padding)

        val percentLabel = TextView(this)
        percentLabel.textSize = 16f
        percentLabel.gravity = Gravity.CENTER
        container.addView(percentLabel)

        val seekBar = SeekBar(this)
        // SeekBar não tem "min" configurável de forma confiável antes da
        // API 26 (android:min só passou a funcionar no O) - o range real
        // é sempre 0..max aqui, e a conversão pra porcentagem (50-200) é
        // feita manualmente somando/subtraindo MIN_PERCENT.
        seekBar.max = ZoomPrefsManager.MAX_PERCENT - ZoomPrefsManager.MIN_PERCENT
        val currentPercent = ZoomPrefsManager.getZoomPercent(this)
        seekBar.progress = currentPercent - ZoomPrefsManager.MIN_PERCENT
        percentLabel.text = getString(R.string.zoom_percent_format, currentPercent)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val percent = progress + ZoomPrefsManager.MIN_PERCENT
                percentLabel.text = getString(R.string.zoom_percent_format, percent)
                if (fromUser) {
                    ZoomPrefsManager.setZoomPercent(this@MainActivity, percent)
                    applyPageZoom(webView, percent)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        container.addView(seekBar)

        AlertDialog.Builder(this)
            .setTitle(R.string.zoom_dialog_title)
            .setView(container)
            .setNeutralButton(R.string.zoom_reset) { _, _ ->
                ZoomPrefsManager.setZoomPercent(this, ZoomPrefsManager.DEFAULT_PERCENT)
                applyPageZoom(webView, ZoomPrefsManager.DEFAULT_PERCENT)
            }
            .setPositiveButton(R.string.dialog_done, null)
            .show()
    }

    // XAULINXS_FIX_V3_LOGIN
    /**
     * Link com target="_blank": em vez de criar uma janela, descobre a URL
     * pela WebView temporária (transport) e carrega na WebView atual.
     */
    private fun openLinkInCurrentView(resultMsg: Message): Boolean {
        val temp = WebView(this)
        var handled = false
        fun redirect(url: String?) {
            if (handled || url.isNullOrBlank() || url == "about:blank") return
            handled = true
            webView?.loadUrl(url)
            mediaMenuHandler.post { temp.destroy() }
        }
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                redirect(request?.url?.toString())
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                redirect(url)
            }
        }
        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = temp
        resultMsg.sendToTarget()
        // Se nada navegou (janela vazia), não deixa a WebView temporária vazando.
        mediaMenuHandler.postDelayed({ if (!handled) temp.destroy() }, 10000)
        return true
    }

    /**
     * window.open() de verdade (ex.: login do Google/GSI): abre um Dialog
     * com uma WebView própria e devolve ela como destino da janela. Assim
     * window.opener e postMessage continuam funcionando, que é o que o
     * GSI usa para devolver o token para a página original. A janela fecha
     * sozinha quando o site chama window.close() (onCloseWindow).
     */
    private fun openPopupWindow(resultMsg: Message): Boolean {
        closePopupWindow()

        val popup = WebView(this)
        val ps = popup.settings
        ps.javaScriptEnabled = true
        ps.domStorageEnabled = true
        ps.databaseEnabled = true
        ps.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ps.setSupportMultipleWindows(false)
        ps.javaScriptCanOpenWindowsAutomatically = true
        // Mesmo User-Agent da WebView principal durante toda a sessão.
        webView?.settings?.userAgentString?.let { ps.userAgentString = it }
        CookiePrefsManager.apply(this, popup)
        popup.webViewClient = object : WebViewClient() {}

        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val title = TextView(this)
        title.textSize = 14f
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(pad, pad, pad, pad)

        val close = TextView(this)
        close.text = "\u2715"
        close.textSize = 20f
        close.setPadding(pad * 2, pad, pad * 2, pad)
        close.setOnClickListener { closePopupWindow() }

        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                closePopupWindow()
            }

            override fun onReceivedTitle(view: WebView?, t: String?) {
                title.text = t ?: ""
            }
        }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(close)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(bar)
        root.addView(popup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (popup.canGoBack()) popup.goBack() else closePopupWindow()
                }
                true
            } else {
                false
            }
        }
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        popupWebView = popup
        popupDialog = dialog

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        dialog.show()
        return true
    }

    private fun closePopupWindow() {
        val dialog = popupDialog
        val popup = popupWebView
        popupDialog = null
        popupWebView = null
        try {
            dialog?.dismiss()
        } catch (e: Exception) {
            // Activity já finalizando - nada a fazer.
        }
        if (popup != null) {
            popup.stopLoading()
            (popup.parent as? android.view.ViewGroup)?.removeView(popup)
            popup.destroy()
        }
    }

    override fun onResume() {
        super.onResume()
        // O toggle de cookies de terceiros vive nas Configurações (outra
        // Activity): reaplica na WebView ao voltar.
        CookiePrefsManager.apply(this, webView)
    }

    override fun onPause() {
        // Grava os cookies em disco - mantém o login se o processo morrer.
        android.webkit.CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        closePopupWindow() // XAULINXS_FIX_V3_LOGIN
        exitCustomView()
        WebViewUpgrade.removeUpgradeCallback(upgradeCallback)
        super.onDestroy()
    }

    override fun onBackPressed() {
        val currentWebView = webView
        when {
            // Barra de atalhos expandida tem prioridade sobre qualquer
            // outra navegação - botão físico/gesto de voltar só a
            // recolhe de volta pra faixa fina, igual ao comportamento
            // padrão de um drawer/overlay lateral (a barra em si nunca
            // fecha por completo, então não há "fechar" aqui de fato).
            customView != null -> exitCustomView()
            sidebarExpanded -> collapseSidebar()
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
