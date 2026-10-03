#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_download_names_confirm_popup.py   (marcador: XAULINXS_DL_NAME_V1)

Uso (na raiz do repo Navegador, no Termux):
    python3 fix_download_names_confirm_popup.py
ou: python3 fix_download_names_confirm_popup.py /caminho/do/Navegador

O que faz
---------
1) Corrige os nomes genericos ("downloadfile.bin", hash.bin, etc.):
   - novo DownloadFileNameResolver.kt: le Content-Disposition completo
     (filename*=UTF-8''..., filename="...", filename=...), usa nome da URL
     so se for confiavel (tem extensao real, nao e hash/.bin/.php), e quando
     nao ha nome confiavel faz uma sonda leve em segundo plano (GET com
     Range 0-15, segue redirecionamentos, manda Cookie/User-Agent) para ler
     o Content-Disposition/Content-Type finais e farejar os primeiros bytes
     (zip, gz, 7z, rar, pdf, png, jpg, xz, bz2). Extensao tambem vem do MIME.
2) Popup de confirmacao em TODOS os downloads (http/https, blob:, data:,
   baixar imagem/video do long-press): nome do arquivo editavel no topo +
   botoes Confirmar / Cancelar. So inicia o download apos Confirmar.
3) BrowserDownloadManager.startDownload ganha o parametro opcional
   fileNameOverride (@JvmOverloads: chamadas Java antigas continuam
   compilando) e evita colisao de nome em Downloads.
4) "Baixar novamente" (DownloadHandler.retryDownload) reaproveita o nome ja
   confirmado do item.

Idempotente: rodar duas vezes nao duplica nada.
"""
import os
import sys

MARK = "XAULINXS_DL_NAME_V1"

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
MAIN = os.path.join(ROOT, "app", "src", "main")
JAVA = os.path.join(MAIN, "java", "com", "xaulinxs")


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def need(path):
    if not os.path.isfile(path):
        sys.exit("ERRO: arquivo nao encontrado: %s\n(rode na raiz do repo Navegador)" % path)


# ----------------------------------------------------------------------
# 1) DownloadFileNameResolver.kt
# ----------------------------------------------------------------------
RESOLVER_KT = r'''package com.xaulinxs.funcoes.download

import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

// XAULINXS_DL_NAME_V1
/**
 * Descobre o nome correto de um download. Ordem de prioridade:
 *  1) Content-Disposition entregue pela WebView (filename*= / filename=);
 *  2) nome na URL, SO se for confiavel (extensao real, nao e hash/.bin/.php);
 *  3) sonda de rede leve (cabecalhos finais depois dos redirecionamentos,
 *     ex.: mediafire, GitHub releases, links assinados) + faro dos bytes;
 *  4) extensao pelo MIME; ultimo recurso: "download".
 *
 * resolveBlocking() faz rede: chamar SEMPRE fora da thread principal.
 */
object DownloadFileNameResolver {

    private const val MAX_REDIRECTS = 8
    private const val TIMEOUT_MS = 8000
    private const val MAX_NAME_LENGTH = 120

    private val GENERIC_MIMES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/force-download",
        "application/x-download",
        "application/download",
        "application/unknown"
    )

    private val DYNAMIC_EXTS = setOf(
        "php", "asp", "aspx", "jsp", "jspx", "cgi", "do", "action", "ashx", "cfm", "pl"
    )

    private val QUERY_KEYS = listOf("filename", "file_name", "fname", "file", "name", "download")

    private val HEX_LIKE = Regex("^[0-9a-fA-F\\-]{8,}$")

    class Probe(
        val contentDisposition: String?,
        val mimeType: String?,
        val finalUrl: String,
        val sniffedExt: String?
    )

    // ------------------------------------------------------------------
    // API publica
    // ------------------------------------------------------------------

    /** Sem rede - usado para blob:/data: e como plano B. */
    fun resolveOffline(url: String, contentDisposition: String?, mimeType: String?): String {
        return compose(url, contentDisposition, mimeType, null)
    }

    /** Pode usar rede (sonda de cabecalhos) - NAO chamar na thread principal. */
    fun resolveBlocking(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val hasGoodName = parseContentDisposition(contentDisposition) != null ||
            reliableUrlName(url) != null
        val lower = url.lowercase(Locale.ROOT)
        val isHttp = lower.startsWith("http://") || lower.startsWith("https://")
        val probe = if (!hasGoodName && isHttp) probe(url, userAgent) else null
        return compose(url, contentDisposition, mimeType, probe)
    }

    /** Tira caracteres proibidos em nome de arquivo e limita o tamanho. */
    fun sanitize(name: String): String {
        var n = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        if (n.length > MAX_NAME_LENGTH) {
            val ext = extensionOf(n)
            n = if (ext != null) {
                n.substring(0, MAX_NAME_LENGTH - ext.length - 1).trimEnd() + "." + ext
            } else {
                n.substring(0, MAX_NAME_LENGTH)
            }
        }
        return n
    }

    /** Se ja existir um arquivo com esse nome em Downloads, devolve "nome (1).ext". */
    fun makeUnique(name: String): String {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!File(dir, name).exists()) {
                name
            } else {
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                var n = 1
                var candidate = "$base ($n)$ext"
                while (File(dir, candidate).exists() && n < 1000) {
                    n++
                    candidate = "$base ($n)$ext"
                }
                candidate
            }
        } catch (e: Exception) {
            name
        }
    }

    fun isGenericMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return true
        return GENERIC_MIMES.contains(mime.substringBefore(';').trim().lowercase(Locale.ROOT))
    }

    fun extensionOf(name: String?): String? {
        if (name == null) return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return null
        val ext = name.substring(dot + 1)
        if (ext.length > 8 || !ext.all { it.isLetterOrDigit() }) return null
        return ext.lowercase(Locale.ROOT)
    }

    // ------------------------------------------------------------------
    // Content-Disposition
    // ------------------------------------------------------------------

    fun parseContentDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null

        // RFC 5987: filename*=UTF-8''nome%20com%20espaco.zip
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = m.groupValues[1].trim().ifBlank { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"')
            try {
                val decoded = URLDecoder.decode(raw.replace("+", "%2B"), charset).trim()
                if (decoded.isNotBlank()) return decoded.substringAfterLast('/').substringAfterLast('\\')
            } catch (e: Exception) {
                // cai para o filename= simples abaixo
            }
        }

        Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = m.groupValues[1].trim()
            if (v.isNotBlank()) return decodeIfPercent(v).substringAfterLast('/').substringAfterLast('\\')
        }

        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = m.groupValues[1].trim().trim('\'', '"')
            if (v.isNotBlank()) return decodeIfPercent(v).substringAfterLast('/').substringAfterLast('\\')
        }
        return null
    }

    private fun decodeIfPercent(s: String): String {
        if (!s.contains('%')) return s
        return try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            s
        }
    }

    // ------------------------------------------------------------------
    // Nome vindo da URL
    // ------------------------------------------------------------------

    private fun nameFromUrl(url: String): String? {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return null
        }
        if (uri.isOpaque) return null
        for (key in QUERY_KEYS) {
            val v = try {
                uri.getQueryParameter(key)
            } catch (e: Exception) {
                null
            }
            if (!v.isNullOrBlank()) {
                val last = v.substringAfterLast('/')
                if (extensionOf(last) != null) return last
            }
        }
        val seg = uri.lastPathSegment
        return if (seg.isNullOrBlank()) null else seg
    }

    /** Nome da URL so quando parece um nome de arquivo de verdade. */
    private fun reliableUrlName(url: String): String? {
        val raw = nameFromUrl(url) ?: return null
        val ext = extensionOf(raw) ?: return null
        if (ext == "bin" || DYNAMIC_EXTS.contains(ext)) return null
        return raw
    }

    private fun isHashLike(s: String): Boolean {
        if (s.length < 8) return false
        val digits = s.count { it.isDigit() }
        val letters = s.count { it.isLetter() }
        if (HEX_LIKE.matches(s) && digits > 0) return true
        return s.length >= 16 && digits >= 2 && letters >= 2 &&
            !s.contains(' ') && !s.contains('_') && !s.contains('-')
    }

    // ------------------------------------------------------------------
    // Composicao final
    // ------------------------------------------------------------------

    private fun compose(url: String, cd: String?, mime: String?, probe: Probe?): String {
        var name: String? = parseContentDisposition(cd)
            ?: parseContentDisposition(probe?.contentDisposition)

        if (name == null) {
            name = reliableUrlName(url) ?: probe?.let { reliableUrlName(it.finalUrl) }
        }

        if (name == null) {
            val seg = nameFromUrl(probe?.finalUrl ?: url) ?: nameFromUrl(url)
            name = if (seg == null) {
                "download"
            } else {
                val ext = extensionOf(seg)
                val base = if (ext != null) seg.substring(0, seg.length - ext.length - 1) else seg
                if (base.isBlank() || isHashLike(base)) "download" else base
            }
        }

        var ext = extensionOf(name)
        if (ext == null || ext == "bin") {
            val realMime = when {
                !isGenericMime(probe?.mimeType) -> probe?.mimeType
                !isGenericMime(mime) -> mime
                else -> null
            }
            val fromMime = realMime?.let {
                MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(it.substringBefore(';').trim().lowercase(Locale.ROOT))
            }
            val newExt = fromMime ?: probe?.sniffedExt
            if (newExt != null) {
                val base = if (ext == "bin") name!!.substring(0, name.length - 4) else name!!
                name = "$base.$newExt"
            } else if (ext == "bin") {
                // servidor nao disse nada melhor: mantem o .bin so se veio assim
            }
        }

        val cleaned = sanitize(name!!)
        return if (cleaned.isBlank()) "download" else cleaned
    }

    // ------------------------------------------------------------------
    // Sonda de rede
    // ------------------------------------------------------------------

    private fun probe(startUrl: String, userAgent: String?): Probe? {
        var current = startUrl
        try {
            var redirects = 0
            while (redirects <= MAX_REDIRECTS) {
                val conn = URL(current).openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.requestMethod = "GET"
                    if (!userAgent.isNullOrEmpty()) conn.setRequestProperty("User-Agent", userAgent)
                    val cookie = CookieManager.getInstance().getCookie(current)
                    if (cookie != null) conn.setRequestProperty("Cookie", cookie)
                    conn.setRequestProperty("Range", "bytes=0-15")
                    conn.setRequestProperty("Accept-Encoding", "identity")

                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: return null
                        current = URL(URL(current), loc).toString()
                        redirects++
                        continue
                    }
                    if (code !in 200..299) return null

                    val cd = conn.getHeaderField("Content-Disposition")
                    val mime = conn.contentType
                    val buf = ByteArray(16)
                    var n = 0
                    try {
                        conn.inputStream.use { ins ->
                            while (n < buf.size) {
                                val r = ins.read(buf, n, buf.size - n)
                                if (r < 0) break
                                n += r
                            }
                        }
                    } catch (e: Exception) {
                        // sem corpo legivel: so os cabecalhos ja servem
                    }
                    return Probe(cd, mime, current, sniffExt(buf, n))
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            // rede falhou: o chamador cai para o nome offline
        }
        return null
    }

    private fun sniffExt(b: ByteArray, n: Int): String? {
        fun at(i: Int): Int = if (i < n) (b[i].toInt() and 0xFF) else -1
        return when {
            at(0) == 0x50 && at(1) == 0x4B && (at(2) == 0x03 || at(2) == 0x05) -> "zip"
            at(0) == 0x1F && at(1) == 0x8B -> "gz"
            at(0) == 0x37 && at(1) == 0x7A && at(2) == 0xBC && at(3) == 0xAF -> "7z"
            at(0) == 0x52 && at(1) == 0x61 && at(2) == 0x72 && at(3) == 0x21 -> "rar"
            at(0) == 0x25 && at(1) == 0x50 && at(2) == 0x44 && at(3) == 0x46 -> "pdf"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 0x42 && at(1) == 0x5A && at(2) == 0x68 -> "bz2"
            at(0) == 0xFD && at(1) == 0x37 && at(2) == 0x7A && at(3) == 0x58 && at(4) == 0x5A -> "xz"
            else -> null
        }
    }
}
'''

# ----------------------------------------------------------------------
# 2) Strings
# ----------------------------------------------------------------------
STRINGS_BLOCK = '''    <!-- XAULINXS_DL_NAME_V1 -->
    <string name="download_confirm_title">Baixar arquivo</string>
    <string name="download_confirm_ok">Confirmar</string>
    <string name="download_confirm_name_hint">Nome do arquivo</string>
    <string name="download_confirm_empty_name">Digite um nome para o arquivo</string>
'''

# ----------------------------------------------------------------------
# 3) MainActivity: novo handleDownloadRequest + dialogo
# ----------------------------------------------------------------------
NEW_HANDLER = r'''    private fun handleDownloadRequest(
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

'''


def patch_resolver():
    path = os.path.join(JAVA, "funcoes", "download", "DownloadFileNameResolver.kt")
    if os.path.isfile(path) and MARK in read(path):
        print("= DownloadFileNameResolver.kt ja existe")
        return
    write(path, RESOLVER_KT)
    print("+ DownloadFileNameResolver.kt criado")


def patch_strings():
    path = os.path.join(MAIN, "res", "values", "strings_funcoes.xml")
    need(path)
    s = read(path)
    if MARK in s:
        print("= strings_funcoes.xml ja atualizado")
        return
    idx = s.rfind("</resources>")
    if idx < 0:
        sys.exit("ERRO: </resources> nao encontrado em strings_funcoes.xml")
    write(path, s[:idx] + STRINGS_BLOCK + s[idx:])
    print("+ strings_funcoes.xml atualizado")


def patch_manager():
    path = os.path.join(JAVA, "funcoes", "download", "BrowserDownloadManager.kt")
    need(path)
    s = read(path)
    if MARK in s:
        print("= BrowserDownloadManager.kt ja atualizado")
        return

    old_sig = """    fun startDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {"""
    new_sig = """    @JvmOverloads
    fun startDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        fileNameOverride: String? = null
    ) {"""
    if old_sig not in s:
        sys.exit("ERRO: assinatura de startDownload nao encontrada (BrowserDownloadManager.kt mudou?)")
    s = s.replace(old_sig, new_sig, 1)

    old_name = """        val resolvedMimeType = resolveMimeType(url, contentDisposition, mimeType)
        val fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMimeType)
"""
    new_name = """        // XAULINXS_DL_NAME_V1: nome vem do popup de confirmacao (ou do resolvedor).
        val baseMimeType = resolveMimeType(url, contentDisposition, mimeType)
        val chosenName = DownloadFileNameResolver.sanitize(
            if (!fileNameOverride.isNullOrBlank()) fileNameOverride
            else DownloadFileNameResolver.resolveOffline(url, contentDisposition, baseMimeType)
        ).ifBlank { "download" }
        val fileName = DownloadFileNameResolver.makeUnique(chosenName)
        // Se o servidor mandou MIME generico, deduz o tipo pela extensao do nome final.
        val resolvedMimeType = if (DownloadFileNameResolver.isGenericMime(baseMimeType)) {
            DownloadFileNameResolver.extensionOf(fileName)?.let {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            } ?: baseMimeType
        } else {
            baseMimeType
        }
"""
    if old_name not in s:
        sys.exit("ERRO: bloco de nome do arquivo nao encontrado (BrowserDownloadManager.kt mudou?)")
    s = s.replace(old_name, new_name, 1)
    write(path, s)
    print("+ BrowserDownloadManager.kt atualizado")


def patch_handler_retry():
    path = os.path.join(JAVA, "funcoes", "DownloadHandler.java")
    need(path)
    s = read(path)
    if MARK in s:
        print("= DownloadHandler.java ja atualizado")
        return
    old = """        BrowserDownloadManager.INSTANCE.startDownload(context, entry.originalUrl, null, null, entry.mimeType);
        deleteDownload(context, entry);"""
    new = """        // XAULINXS_DL_NAME_V1: remove o registro antigo primeiro (libera o nome do
        // arquivo parcial) e reaproveita o nome ja confirmado pelo usuario.
        deleteDownload(context, entry);
        BrowserDownloadManager.INSTANCE.startDownload(context, entry.originalUrl, null, null, entry.mimeType, entry.title);"""
    if old not in s:
        sys.exit("ERRO: retryDownload nao encontrado em DownloadHandler.java (mudou?)")
    write(path, s.replace(old, new, 1))
    print("+ DownloadHandler.java atualizado")


def patch_main():
    path = os.path.join(JAVA, "aosp", "browser", "MainActivity.kt")
    need(path)
    s = read(path)
    if MARK in s:
        print("= MainActivity.kt ja atualizado")
        return

    # import
    imp_anchor = "import com.xaulinxs.funcoes.download.BlobDownloadBridge\n"
    if imp_anchor not in s:
        sys.exit("ERRO: import do BlobDownloadBridge nao encontrado na MainActivity")
    s = s.replace(
        imp_anchor,
        imp_anchor + "import com.xaulinxs.funcoes.download.DownloadFileNameResolver\n",
        1,
    )

    # troca o corpo inteiro de handleDownloadRequest
    start = s.find("    private fun handleDownloadRequest(")
    if start < 0:
        sys.exit("ERRO: handleDownloadRequest nao encontrada na MainActivity")
    end_anchor = "    /**\n     * Força a seleção manual de texto"
    end = s.find(end_anchor, start)
    if end < 0:
        sys.exit("ERRO: fim de handleDownloadRequest nao localizado (MainActivity mudou?)")
    s = s[:start] + NEW_HANDLER + s[end:]
    write(path, s)
    print("+ MainActivity.kt atualizado")


def main():
    for p in (
        os.path.join(MAIN, "res", "values", "strings_funcoes.xml"),
        os.path.join(JAVA, "funcoes", "download", "BrowserDownloadManager.kt"),
        os.path.join(JAVA, "funcoes", "DownloadHandler.java"),
        os.path.join(JAVA, "aosp", "browser", "MainActivity.kt"),
    ):
        need(p)
    patch_resolver()
    patch_strings()
    patch_manager()
    patch_handler_retry()
    patch_main()
    print("\nPronto. Agora: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
