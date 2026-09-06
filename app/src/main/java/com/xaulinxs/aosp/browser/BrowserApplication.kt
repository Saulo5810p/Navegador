package com.xaulinxs.aosp.browser

import android.app.Application
import java.io.File
import com.norman.webviewup.lib.WebViewUpgrade
import com.norman.webviewup.lib.util.ProcessUtils
import com.norman.webviewup.lib.source.UpgradeAssetSource
import com.xaulinxs.funcoes.ThemeManager

/**
 * Dispara a troca do kernel de WebView o mais cedo possível no ciclo de vida
 * do app - antes de qualquer android.webkit.WebView ser instanciado.
 *
 * O apk fica em app/src/main/assets/aosp_webview.apk (não incluído neste
 * projeto - copie o arquivo baixado para essa pasta antes de compilar).
 */
class BrowserApplication : Application() {

    companion object {
        const val WEBVIEW_ASSET_NAME = "aosp_webview.apk"
    }

    override fun onCreate() {
        super.onCreate()

        // Aplica o modo de tema salvo (sistema/claro/escuro) o mais cedo
        // possível, antes de qualquer Activity ser criada.
        ThemeManager.applyToApp(this)
        // WebViewUpgrade.upgrade() so pode rodar no processo principal.
        // Application.onCreate() roda de novo em cada processo sandbox
        // (":sandboxed_process0" etc.) que a propria lib cria durante a
        // verificacao (checkWebView) - sem essa guarda, o upgrade reentra
        // recursivamente nesses processos e deixa o provider inconsistente
        // bem na hora em que a MainActivity real cria sua WebView.
        if (!ProcessUtils.isMainProcess(this)) {
            return
        }
        try {
            // Usamos o construtor de 3 parâmetros (Context, assetName, File
            // de destino) apontando manualmente pro armazenamento privado
            // do app. A lib agora é código-fonte vendorizado direto no
            // projeto (com.norman.webviewup.lib), não mais uma dependência
            // do Maven Central - a versão 0.1.0 publicada lá é mais antiga
            // que o código-fonte atual e não marca o apk copiado como
            // somente-leitura, o que o Android 14 (targetSdk 34) exige
            // pra qualquer dex/apk carregado dinamicamente ("Safer dynamic
            // code loading") - sem isso o app caía com SecurityException:
            // "Writable dex file ... is not allowed".
            val apkFile = File(filesDir, WEBVIEW_ASSET_NAME)
            val upgradeSource = UpgradeAssetSource(this, WEBVIEW_ASSET_NAME, apkFile)
            WebViewUpgrade.upgrade(upgradeSource)
        } catch (e: Exception) {
            // Se o apk não existir em assets ainda (ex.: primeira build sem o
            // arquivo copiado), o app cai de volta pro WebView do sistema.
            e.printStackTrace()
        }
    }
}
