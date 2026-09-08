package com.xaulinxs.aosp.browser

import android.app.Application
import android.content.Context
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
        private const val ASSET_FINGERPRINT_PREFS = "xaulinxs_webview_asset_fingerprint"
        private const val KEY_ASSET_LENGTH = "asset_length_bytes"
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

            // BUG encontrado (era a causa real da versão errada continuar
            // aparecendo mesmo depois de trocar o apk em assets/ e
            // recompilar): UpgradePathSource (classe-mãe de
            // UpgradeAssetSource) marca "já copiei" numa SharedPreferences
            // própria, usando como CHAVE o caminho de destino do arquivo
            // (que aqui é sempre o mesmo, filesDir/aosp_webview.apk,
            // fixo) - não o conteúdo/versão do apk em assets/. Resultado:
            // depois da primeira cópia bem-sucedida, TODA execução
            // seguinte pula a cópia e reusa o arquivo antigo extraído pra
            // sempre, mesmo trocando o aosp_webview.apk dentro de
            // assets/ e recompilando - só reagia a uma reinstalação
            // limpa (que apaga o armazenamento privado do app).
            //
            // Correção: guardamos nosso próprio "fingerprint" (tamanho em
            // bytes) do arquivo ATUAL em assets/aosp_webview.apk numa
            // SharedPreferences separada. Se ele mudou desde a última
            // execução (ou é a primeira vez), chamamos
            // upgradeSource.delete() ANTES de upgrade() - esse método já
            // existe na lib (UpgradePathSource.delete()) e apaga tanto o
            // arquivo extraído antigo quanto a flag "já copiei", forçando
            // a lib a reextrair o apk novo de assets/ de verdade.
            if (bundledWebViewApkChanged()) {
                upgradeSource.delete()
            }

            WebViewUpgrade.upgrade(upgradeSource)
        } catch (e: Exception) {
            // Se o apk não existir em assets ainda (ex.: primeira build sem o
            // arquivo copiado), o app cai de volta pro WebView do sistema.
            e.printStackTrace()
        }
    }

    /**
     * Compara o tamanho (em bytes) do aosp_webview.apk atual dentro de
     * assets/ com o tamanho salvo da última vez que o app rodou. Tamanho
     * já é suficiente pra detectar troca de apk aqui (duas versões
     * distintas do Chromium/WebView praticamente nunca têm o mesmo
     * tamanho em bytes) e é muitíssimo mais barato que calcular um hash
     * de um arquivo que pode passar de 100MB, toda vez que o app abre.
     * Já salva o novo tamanho de uma vez, então a próxima chamada só
     * detecta mudança se o apk for trocado de novo.
     */
    private fun bundledWebViewApkChanged(): Boolean {
        val currentLength = try {
            assets.openFd(WEBVIEW_ASSET_NAME).use { it.declaredLength }
        } catch (e: Exception) {
            return false
        }
        val prefs = getSharedPreferences(ASSET_FINGERPRINT_PREFS, Context.MODE_PRIVATE)
        val storedLength = prefs.getLong(KEY_ASSET_LENGTH, -1L)
        prefs.edit().putLong(KEY_ASSET_LENGTH, currentLength).apply()
        return storedLength != currentLength
    }
}
