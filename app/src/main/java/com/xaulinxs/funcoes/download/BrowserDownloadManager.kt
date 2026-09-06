package com.xaulinxs.funcoes.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.TextUtils
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import com.xaulinxs.aosp.browser.R

/**
 * Classe utilitária responsável por disparar um download via
 * DownloadManager.Request, passando cookies, User-Agent e cabeçalhos do
 * navegador, e por acionar o DownloadForegroundService que vai monitorar
 * e notificar o progresso real em tempo real.
 *
 * Uso típico dentro do WebView.setDownloadListener():
 *   webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
 *       BrowserDownloadManager.startDownload(context, url, userAgent, contentDisposition, mimeType)
 *   }
 */
object BrowserDownloadManager {

    /**
     * Enfileira um download via DownloadManager com os headers corretos e
     * inicia o Foreground Service que vai monitorar seu progresso.
     */
    fun startDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val resolvedMimeType = resolveMimeType(url, contentDisposition, mimeType)
        val fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMimeType)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(resolvedMimeType)
            if (!userAgent.isNullOrEmpty()) {
                addRequestHeader("User-Agent", userAgent)
            }
            val cookie = CookieManager.getInstance().getCookie(url)
            if (cookie != null) {
                addRequestHeader("Cookie", cookie)
            }
            setTitle(fileName)
            setDescription(context.getString(R.string.funcoes_download_description))
            // VISIBILITY_HIDDEN exige a permissão de sistema
            // DOWNLOAD_WITHOUT_NOTIFICATION (não concedida a apps
            // comuns) - usá-la sem essa permissão faz o ContentProvider
            // do DownloadManager rejeitar o insert com SecurityException
            // ("Invalid value for visibility"). Usamos
            // VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION: o sistema não
            // mostra notificação de progresso próprio (evitando duplicar
            // com a do DownloadForegroundService), só uma notificação ao
            // concluir - que fica redundante com a nossa de conclusão,
            // mas não trava/crasha o app.
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)
            allowScanningByMediaScanner()
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        Toast.makeText(
            context,
            context.getString(R.string.funcoes_download_started, fileName),
            Toast.LENGTH_SHORT
        ).show()

        // Dispara o Foreground Service já com o ID do download e o nome
        // do arquivo, pra a notificação nascer com o título certo mesmo
        // antes da primeira consulta ao DownloadManager.
        val serviceIntent = Intent(context, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_TRACK_DOWNLOAD
            putExtra(DownloadForegroundService.EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(DownloadForegroundService.EXTRA_FILE_NAME, fileName)
        }
        // startForegroundService é obrigatório a partir do Android 8 (API
        // 26) quando o app está em segundo plano; funciona igual a
        // startService quando o app está em primeiro plano.
        context.startForegroundService(serviceIntent)
    }

    /**
     * Descobre a extensão/mimetype de verdade do arquivo baixado. Ordem de
     * prioridade: 1) nome de arquivo explícito no Content-Disposition, 2)
     * extensão presente na própria URL, 3) o mimeType reportado pelo
     * servidor - só usado por último porque muitos servidores mandam
     * "application/octet-stream" genérico.
     */
    fun resolveMimeType(url: String, contentDisposition: String?, mimeType: String?): String? {
        val isGeneric = mimeType.isNullOrEmpty() ||
            mimeType.equals("application/octet-stream", ignoreCase = true) ||
            mimeType.equals("binary/octet-stream", ignoreCase = true)

        if (!isGeneric) return mimeType

        var nameFromDisposition: String? = null
        if (contentDisposition != null) {
            val idx = contentDisposition.indexOf("filename=")
            if (idx >= 0) {
                nameFromDisposition = contentDisposition.substring(idx + 9)
                    .replace("\"", "").replace(";", "").trim()
            }
        }

        val candidate = if (!TextUtils.isEmpty(nameFromDisposition)) nameFromDisposition else url
        var ext = MimeTypeMap.getFileExtensionFromUrl(candidate)
        if (ext.isNullOrEmpty()) {
            val dot = candidate?.lastIndexOf('.') ?: -1
            if (dot >= 0 && candidate != null && dot < candidate.length - 1) {
                ext = candidate.substring(dot + 1).replace(Regex("[^a-zA-Z0-9]"), "")
            }
        }

        if (!ext.isNullOrEmpty()) {
            val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (guessed != null) return guessed
        }
        return mimeType
    }
}
