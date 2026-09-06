package com.xaulinxs.aosp.browser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.norman.webviewup.lib.UpgradeCallback
import com.norman.webviewup.lib.WebViewUpgrade
import com.xaulinxs.funcoes.DesktopModeManager
import com.xaulinxs.funcoes.FileManagerActivity
import com.xaulinxs.funcoes.SearchEngineManager
import com.xaulinxs.funcoes.download.BrowserDownloadManager

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
    private lateinit var navToolbar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var currentUrl: TextView
    private lateinit var btnBack: Button
    private lateinit var btnHome: Button
    private lateinit var kernelInfo: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnDesktopMode: Button

    // URL recebida de fora (ex: link clicado em outro app, com o
    // XaulinXs AOSP Browser definido como navegador padrão) - guardada
    // aqui porque o WebViewUpgrade é assíncrono e a WebView pode ainda
    // não existir quando o intent chega em onCreate()/onNewIntent().
    private var pendingExternalUrl: String? = null

    private val chromeVersionRegex = Regex("Chrome/([0-9.]+)")

    companion object {
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
        navToolbar = findViewById(R.id.navToolbar)
        searchInput = findViewById(R.id.searchInput)
        currentUrl = findViewById(R.id.currentUrl)
        btnBack = findViewById(R.id.btnBack)
        btnHome = findViewById(R.id.btnHome)
        kernelInfo = findViewById(R.id.kernelInfo)
        bottomBar = findViewById(R.id.bottomBar)
        btnDesktopMode = findViewById(R.id.btnDesktopMode)

        bottomBar.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnBack.setOnClickListener {
            webView?.let { wv -> if (wv.canGoBack()) wv.goBack() else showHome() }
        }

        btnHome.setOnClickListener { showHome() }

        btnDesktopMode.setOnClickListener {
            webView?.let { wv ->
                DesktopModeManager.toggle(this, wv)
                updateDesktopModeButtonLabel()
            }
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

        requestNotificationPermissionIfNeeded()

        // Se o app foi aberto por outro app (ex: link clicado com o
        // XaulinXs AOSP Browser definido como navegador padrão), o Intent
        // chega com ACTION_VIEW e a URL em intent.data - guarda pra
        // carregar assim que a WebView estiver pronta.
        handleViewIntent(intent)

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
        newWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl.text = url ?: ""
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

    private fun updateDesktopModeButtonLabel() {
        val isDesktop = DesktopModeManager.isDesktopMode(this)
        btnDesktopMode.text = if (isDesktop) {
            getString(R.string.desktop_mode_button_mobile)
        } else {
            getString(R.string.desktop_mode_button_desktop)
        }
    }

    private fun updateKernelInfo() {
        val userAgent = WebSettings.getDefaultUserAgent(this)
        val chromeVersion = chromeVersionRegex.find(userAgent)?.groupValues?.get(1) ?: "desconhecida"
        val packageName = WebViewUpgrade.getUpgradeWebViewPackageName()
            ?: WebViewUpgrade.getSystemWebViewPackageName()
            ?: "desconhecido"
        kernelInfo.text = "WebView Chrome/$chromeVersion  •  $packageName"
    }

    override fun onDestroy() {
        WebViewUpgrade.removeUpgradeCallback(upgradeCallback)
        super.onDestroy()
    }

    override fun onBackPressed() {
        val currentWebView = webView
        when {
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
