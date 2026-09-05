package com.xaulinxs.aosp.browser

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.norman.webviewup.lib.UpgradeCallback
import com.norman.webviewup.lib.WebViewUpgrade

class MainActivity : AppCompatActivity() {

    // A WebView NÃO é criada aqui (nem em campo, nem no layout XML).
    // Ela só nasce dentro de initWebView(), depois que o WebViewUpgrade
    // já decidiu qual provider usar - criar uma WebView antes disso
    // vincula o processo ao WebView do sistema e a troca nunca acontece.
    private var webView: WebView? = null

    private lateinit var webViewContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var btnGo: Button
    private lateinit var btnBack: Button
    private lateinit var kernelInfo: TextView

    // Regex usada pra extrair a versão do Chromium do UserAgent, exatamente
    // como o app "WebView Browser Tester" mostra - é a prova real de qual
    // kernel está rodando, e não só o que a lib *tentou* carregar.
    private val chromeVersionRegex = Regex("Chrome/([0-9.]+)")

    private val upgradeCallback = object : UpgradeCallback {
        override fun onUpgradeProcess(percent: Float) {
            kernelInfo.text = "Carregando kernel AOSP... ${(percent * 100).toInt()}%"
        }

        override fun onUpgradeComplete() {
            initWebView()
        }

        override fun onUpgradeError(throwable: Throwable) {
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
        urlInput = findViewById(R.id.urlInput)
        btnGo = findViewById(R.id.btnGo)
        btnBack = findViewById(R.id.btnBack)
        kernelInfo = findViewById(R.id.kernelInfo)

        btnGo.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val currentWebView = webView
            if (currentWebView == null) {
                Toast.makeText(this, "Ainda carregando o kernel do WebView...", Toast.LENGTH_SHORT).show()
            } else if (url.isNotEmpty()) {
                currentWebView.loadUrl(if (url.startsWith("http")) url else "https://$url")
            }
        }

        btnBack.setOnClickListener {
            webView?.let { if (it.canGoBack()) it.goBack() }
        }

        when {
            WebViewUpgrade.isCompleted() -> initWebView()
            WebViewUpgrade.isFailed() -> initWebView()
            else -> {
                kernelInfo.text = "Carregando kernel AOSP..."
                WebViewUpgrade.addUpgradeCallback(upgradeCallback)
            }
        }
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
        newWebView.webViewClient = WebViewClient()
        newWebView.webChromeClient = WebChromeClient()
        newWebView.loadUrl("https://google.com")

        updateKernelInfo()
    }

    /**
     * Mostra, na barra inferior estilo QSB, a versão real do Chromium (lida
     * do UserAgent, igual o WebView Browser Tester faz) junto com o nome do
     * pacote que está sendo usado como kernel. Se aparecer 113.0.5672.136
     * é o nosso WebView AOSP; qualquer outro número é o WebView comercial
     * do sistema (não embutido).
     */
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
}
