#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_browser_blob_cleartext_selection_video.py

Uso (dentro do repo clonado no Termux):
    cd ~/Navegador
    python3 fix_browser_blob_cleartext_selection_video.py
  ou:
    python3 fix_browser_blob_cleartext_selection_video.py /caminho/do/Navegador

Corrige:
  1. Crash "Can only download HTTP/HTTPS URIs: blob:..." (e data:)
     -> novo BlobDownloadBridge.kt: baixa blob:/data: via JS (fetch + FileReader
        em pedacos) e salva em Downloads (MediaStore no Android 10+).
     -> guarda de esquema em BrowserDownloadManager (nunca mais crasha).
  2. "Error clear text not permitted" em redirects http (mediafire etc.)
     -> android:usesCleartextTraffic="true" + mixed content liberado.
  3. Videos: tela cheia (onShowCustomView/onHideCustomView), poster padrao
     (evita NPE), cookies de terceiros, mixed content, autoplay, DRM (EME).
  4. Selecao manual de texto: o long-press nao e mais consumido; o menu de
     midia so aparece se houver imagem/video sob o dedo; CSS/JS injetado
     destrava user-select em sites que bloqueiam.

Idempotente: pode rodar varias vezes.
"""
import os
import re
import sys

MARK = "XAULINXS_FIX_V2"

root = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
MAIN = os.path.join(root, "app/src/main")
KT_MAIN = os.path.join(MAIN, "java/com/xaulinxs/aosp/browser/MainActivity.kt")
KT_DL = os.path.join(MAIN, "java/com/xaulinxs/funcoes/download/BrowserDownloadManager.kt")
KT_BLOB = os.path.join(MAIN, "java/com/xaulinxs/funcoes/download/BlobDownloadBridge.kt")
MANIFEST = os.path.join(MAIN, "AndroidManifest.xml")
STRINGS = os.path.join(MAIN, "res/values/strings.xml")

for p in (KT_MAIN, KT_DL, MANIFEST, STRINGS):
    if not os.path.isfile(p):
        sys.exit("ERRO: arquivo nao encontrado: %s\nRode dentro da raiz do repo Navegador." % p)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def must_replace(src, old, new, label, count=1):
    if old not in src:
        sys.exit("ERRO: ancora nao encontrada para: %s" % label)
    return src.replace(old, new, count)


# ---------------------------------------------------------------------------
# 1) Manifest: cleartext
# ---------------------------------------------------------------------------
m = read(MANIFEST)
if 'android:usesCleartextTraffic="true"' in m:
    print("[skip] Manifest ja com cleartext liberado")
else:
    m = must_replace(
        m,
        'android:usesCleartextTraffic="false"',
        'android:usesCleartextTraffic="true"',
        "usesCleartextTraffic",
    )
    write(MANIFEST, m)
    print("[ok] Manifest: usesCleartextTraffic=true")

# ---------------------------------------------------------------------------
# 2) Strings
# ---------------------------------------------------------------------------
s = read(STRINGS)
if "blob_download_saved" in s:
    print("[skip] strings ja adicionadas")
else:
    add = (
        '    <!-- %s -->\n'
        '    <string name="blob_download_started">Salvando %%1$s…</string>\n'
        '    <string name="blob_download_saved">Salvo em Downloads: %%1$s</string>\n'
        '    <string name="blob_download_failed">Falha ao salvar o arquivo</string>\n'
        '    <string name="download_unsupported_scheme">Esse tipo de link não pode ser baixado</string>\n'
    ) % MARK
    s = must_replace(s, "</resources>", add + "</resources>", "strings </resources>")
    write(STRINGS, s)
    print("[ok] strings adicionadas")

# ---------------------------------------------------------------------------
# 3) BlobDownloadBridge.kt (arquivo novo)
# ---------------------------------------------------------------------------
BLOB_KT = r'''package com.xaulinxs.funcoes.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.xaulinxs.aosp.browser.R
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * XAULINXS_FIX_V2
 *
 * O DownloadManager do sistema só aceita http/https, então links "blob:"
 * (gerados pelo próprio JavaScript da página) e "data:" precisam ser lidos
 * dentro da página e gravados aqui. Fluxo:
 *   1) MainActivity injeta buildFetchScript() na página (fetch do blob).
 *   2) O JS lê o blob em pedaços de ~768 KB (múltiplo de 3 -> base64 sem
 *      padding no meio) e manda cada pedaço para chunk().
 *   3) end() finaliza o arquivo (Android 10+: MediaStore.Downloads).
 *
 * Todas as chamadas exigem o token gerado por instância, para que uma
 * página qualquer não consiga usar esta ponte para gravar arquivos.
 */
class BlobDownloadBridge(context: Context) {

    private val appContext = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private val token: String = UUID.randomUUID().toString()

    private class Session(
        val name: String,
        val out: OutputStream,
        val uri: Uri?,
        val file: File?
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    // ------------------------------------------------------------------
    // API usada pela MainActivity
    // ------------------------------------------------------------------

    /** Script que busca o blob dentro da página e envia para esta ponte. */
    fun buildFetchScript(blobUrl: String, fileName: String): String {
        val id = UUID.randomUUID().toString()
        return """
            (function() {
                var T = ${JSONObject.quote(token)};
                var ID = ${JSONObject.quote(id)};
                var URL_ = ${JSONObject.quote(blobUrl)};
                var NAME = ${JSONObject.quote(fileName)};
                var B = window.XaulinXsBlobBridge;
                if (!B) return;
                fetch(URL_).then(function(r) { return r.blob(); }).then(function(blob) {
                    var mime = blob.type || 'application/octet-stream';
                    if (!B.begin(T, ID, NAME, mime)) return;
                    var CH = 786432, off = 0;
                    function next() {
                        if (off >= blob.size) { B.end(T, ID); return; }
                        var fr = new FileReader();
                        fr.onload = function() {
                            var s = String(fr.result);
                            B.chunk(T, ID, s.substring(s.indexOf(',') + 1));
                            off += CH;
                            next();
                        };
                        fr.onerror = function() { B.fail(T, ID); };
                        fr.readAsDataURL(blob.slice(off, off + CH));
                    }
                    next();
                }).catch(function() { B.fail(T, ID); });
            })();
        """.trimIndent()
    }

    /** Salva um link data: (base64 ou percent-encoded) direto em Downloads. */
    fun saveDataUri(dataUri: String, fileName: String) {
        Thread {
            try {
                val comma = dataUri.indexOf(',')
                if (!dataUri.startsWith("data:", true) || comma < 0) {
                    toast(R.string.blob_download_failed)
                    return@Thread
                }
                val meta = dataUri.substring(5, comma)
                val payload = dataUri.substring(comma + 1)
                val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
                val bytes = if (meta.endsWith(";base64", true)) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray(Charsets.UTF_8)
                }
                val session = open(fileName, mime) ?: run {
                    toast(R.string.blob_download_failed)
                    return@Thread
                }
                session.out.write(bytes)
                finish(session, mime)
            } catch (e: Exception) {
                toast(R.string.blob_download_failed)
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // API chamada pelo JavaScript (thread "JavaBridge", em sequência)
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun begin(t: String, id: String, fileName: String, mime: String): Boolean {
        if (t != token) return false
        return try {
            val session = open(fileName, mime) ?: run {
                toast(R.string.blob_download_failed)
                return false
            }
            sessions[id] = session
            true
        } catch (e: Exception) {
            toast(R.string.blob_download_failed)
            false
        }
    }

    @JavascriptInterface
    fun chunk(t: String, id: String, base64: String) {
        if (t != token) return
        val session = sessions[id] ?: return
        try {
            session.out.write(Base64.decode(base64, Base64.DEFAULT))
        } catch (e: Exception) {
            abort(id)
        }
    }

    @JavascriptInterface
    fun end(t: String, id: String) {
        if (t != token) return
        val session = sessions.remove(id) ?: return
        try {
            finish(session, null)
        } catch (e: Exception) {
            toast(R.string.blob_download_failed)
        }
    }

    @JavascriptInterface
    fun fail(t: String, id: String) {
        if (t != token) return
        abort(id)
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private fun abort(id: String) {
        val session = sessions.remove(id)
        if (session != null) {
            try { session.out.close() } catch (ignored: Exception) {}
            try {
                if (session.uri != null) appContext.contentResolver.delete(session.uri, null, null)
                session.file?.delete()
            } catch (ignored: Exception) {}
        }
        toast(R.string.blob_download_failed)
    }

    private fun resolveName(fileName: String, mime: String): String {
        var name = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }
        val generic = mime.equals("application/octet-stream", true) ||
            mime.equals("binary/octet-stream", true)
        val ext = if (generic) null else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (ext != null) {
            val dot = name.lastIndexOf('.')
            val cur = if (dot > 0) name.substring(dot + 1) else ""
            if (cur.isEmpty() || cur.equals("bin", true)) {
                name = (if (dot > 0) name.substring(0, dot) else name) + "." + ext
            }
        }
        return name
    }

    private fun open(fileName: String, mime: String): Session? {
        val name = resolveName(fileName, mime)
        ui.post {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.blob_download_started, name),
                Toast.LENGTH_SHORT
            ).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = resolver.openOutputStream(uri) ?: return null
            return Session(name, out, uri, null)
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        var file = File(dir, name)
        var n = 1
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        while (file.exists()) {
            file = File(dir, "$base ($n)$ext")
            n++
        }
        return Session(file.name, file.outputStream(), null, file)
    }

    private fun finish(session: Session, mime: String?) {
        session.out.flush()
        session.out.close()
        if (session.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            appContext.contentResolver.update(session.uri, values, null, null)
        } else if (session.file != null) {
            MediaScannerConnection.scanFile(appContext, arrayOf(session.file.absolutePath), null, null)
        }
        ui.post {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.blob_download_saved, session.name),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun toast(resId: Int) {
        ui.post { Toast.makeText(appContext, appContext.getString(resId), Toast.LENGTH_SHORT).show() }
    }
}
'''

if os.path.isfile(KT_BLOB) and MARK in read(KT_BLOB):
    print("[skip] BlobDownloadBridge.kt ja existe")
else:
    write(KT_BLOB, BLOB_KT)
    print("[ok] BlobDownloadBridge.kt criado")

# ---------------------------------------------------------------------------
# 4) BrowserDownloadManager: guarda de esquema
# ---------------------------------------------------------------------------
d = read(KT_DL)
if MARK in d:
    print("[skip] BrowserDownloadManager ja com guarda")
else:
    guard = (
        "        // %s: DownloadManager.Request so aceita http/https - qualquer\n"
        "        // outro esquema (blob:, data:, ftp:) lancava IllegalArgumentException\n"
        "        // e derrubava o app. Agora so avisa; blob:/data: sao tratados na\n"
        "        // MainActivity via BlobDownloadBridge.\n"
        "        val scheme = Uri.parse(url).scheme?.lowercase()\n"
        "        if (scheme != \"http\" && scheme != \"https\") {\n"
        "            Toast.makeText(\n"
        "                context,\n"
        "                context.getString(R.string.download_unsupported_scheme),\n"
        "                Toast.LENGTH_SHORT\n"
        "            ).show()\n"
        "            return\n"
        "        }\n\n"
    ) % MARK
    d = must_replace(
        d,
        "        val resolvedMimeType = resolveMimeType(url, contentDisposition, mimeType)\n",
        guard + "        val resolvedMimeType = resolveMimeType(url, contentDisposition, mimeType)\n",
        "BrowserDownloadManager.startDownload",
    )
    write(KT_DL, d)
    print("[ok] BrowserDownloadManager: guarda de esquema")

# ---------------------------------------------------------------------------
# 5) MainActivity
# ---------------------------------------------------------------------------
k = read(KT_MAIN)
if MARK in k:
    print("[skip] MainActivity ja corrigida")
else:
    # 5.1 import
    k = must_replace(
        k,
        "import com.xaulinxs.funcoes.download.BrowserDownloadManager\n",
        "import com.xaulinxs.funcoes.download.BrowserDownloadManager\n"
        "import com.xaulinxs.funcoes.download.BlobDownloadBridge\n",
        "import BlobDownloadBridge",
    )

    # 5.2 campos (tela cheia de video + ponte de blob)
    k = must_replace(
        k,
        "    private var webView: WebView? = null\n",
        "    private var webView: WebView? = null\n\n"
        "    // %s: tela cheia de video (WebChromeClient.onShowCustomView)\n"
        "    private var customView: View? = null\n"
        "    private var customViewCallback: WebChromeClient.CustomViewCallback? = null\n"
        "    private var previousUiVisibility: Int = 0\n" % MARK,
        "campos customView",
    )
    k = must_replace(
        k,
        "    private val mediaMenuHandler = Handler(Looper.getMainLooper())\n",
        "    private val mediaMenuHandler = Handler(Looper.getMainLooper())\n"
        "    private val blobBridge by lazy { BlobDownloadBridge(this) }\n",
        "campo blobBridge",
    )

    # 5.3 settings
    k = must_replace(
        k,
        "        newWebView.settings.allowContentAccess = true\n",
        "        newWebView.settings.allowContentAccess = true\n"
        "        // %s: videos/segmentos http dentro de pagina https eram\n"
        "        // bloqueados (padrao NEVER_ALLOW); cookies de terceiros sao\n"
        "        // necessarios para varios players/CDNs; autoplay sem gesto\n"
        "        // evita players que chamam play() por script e falham.\n"
        "        newWebView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW\n"
        "        newWebView.settings.mediaPlaybackRequiresUserGesture = false\n"
        "        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)\n" % MARK,
        "settings",
    )

    # 5.4 ponte de blob
    k = must_replace(
        k,
        '        newWebView.addJavascriptInterface(MediaLongPressBridge(), "XaulinXsMediaBridge")\n',
        '        newWebView.addJavascriptInterface(MediaLongPressBridge(), "XaulinXsMediaBridge")\n'
        '        newWebView.addJavascriptInterface(blobBridge, "XaulinXsBlobBridge")\n',
        "addJavascriptInterface blob",
    )

    # 5.5 long press: NAO consumir (restaura selecao manual de texto)
    old_lp = (
        "            // Consome o long-press: em vez do menu nativo de seleção de\n"
        "            // texto do WebView, este app decide mostrar (ou não) o\n"
        "            // popup compacto assim que o JS acima responder de forma\n"
        "            // assíncrona via MediaLongPressBridge.onMediaFound().\n"
        "            true\n"
    )
    new_lp = (
        "            // %s: NAO consome mais o long-press (return false). Antes\n"
        "            // ele era sempre consumido e isso matava a selecao manual de\n"
        "            // texto do WebView em qualquer pagina. Agora o JS acima roda\n"
        "            // em paralelo e so abre o popup de midia se houver imagem/video\n"
        "            // sob o dedo; sobre texto, a selecao nativa funciona normal.\n"
        "            false\n" % MARK
    )
    k = must_replace(k, old_lp, new_lp, "long press consumido")

    # 5.6 onMediaFound: limpa selecao quando abre o menu de midia
    k = must_replace(
        k,
        "            mediaMenuHandler.post { showMediaContextMenu(type, src) }\n",
        "            mediaMenuHandler.post {\n"
        "                webView?.evaluateJavascript(\"window.getSelection().removeAllRanges();\", null)\n"
        "                showMediaContextMenu(type, src)\n"
        "            }\n",
        "onMediaFound",
    )

    # 5.7 background-image so conta se o elemento nao tem texto (senao o
    # body com background-image roubava o long-press em cima de texto)
    k = must_replace(
        k,
        "if (bg && bg !== 'none') {",
        "if (bg && bg !== 'none' && !(el.innerText || '').trim()) {",
        "bg-image sem texto",
    )

    # 5.8 selecao de texto forcada: onPageCommitVisible + onPageFinished
    k = must_replace(
        k,
        "            override fun onPageFinished(view: WebView?, url: String?) {\n"
        "                super.onPageFinished(view, url)\n",
        "            override fun onPageCommitVisible(view: WebView?, url: String?) {\n"
        "                super.onPageCommitVisible(view, url)\n"
        "                enableTextSelection(view)\n"
        "            }\n\n"
        "            override fun onPageFinished(view: WebView?, url: String?) {\n"
        "                super.onPageFinished(view, url)\n"
        "                enableTextSelection(view)\n",
        "onPageFinished",
    )

    # 5.9 WebChromeClient: video em tela cheia, poster, DRM
    chrome_extra = (
        "            // %s ---- video em tela cheia / poster / DRM ----\n"
        "            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {\n"
        "                if (view == null || customView != null) {\n"
        "                    callback?.onCustomViewHidden()\n"
        "                    return\n"
        "                }\n"
        "                customView = view\n"
        "                customViewCallback = callback\n"
        "                val decor = window.decorView as FrameLayout\n"
        "                previousUiVisibility = decor.systemUiVisibility\n"
        "                decor.addView(\n"
        "                    view,\n"
        "                    FrameLayout.LayoutParams(\n"
        "                        FrameLayout.LayoutParams.MATCH_PARENT,\n"
        "                        FrameLayout.LayoutParams.MATCH_PARENT\n"
        "                    )\n"
        "                )\n"
        "                decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or\n"
        "                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or\n"
        "                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or\n"
        "                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or\n"
        "                    View.SYSTEM_UI_FLAG_FULLSCREEN or\n"
        "                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)\n"
        "            }\n\n"
        "            override fun onHideCustomView() {\n"
        "                exitCustomView()\n"
        "            }\n\n"
        "            // Sem poster padrao, alguns WebViews lancam NPE ao preparar\n"
        "            // <video> sem atributo poster.\n"
        "            override fun getDefaultVideoPoster(): android.graphics.Bitmap? {\n"
        "                return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)\n"
        "            }\n\n"
        "            // Conteudo protegido (EME/DRM): so libera o ID de midia\n"
        "            // protegida; camera/microfone continuam negados.\n"
        "            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {\n"
        "                if (request == null) return\n"
        "                val allowed = request.resources\n"
        "                    .filter { it == android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }\n"
        "                    .toTypedArray()\n"
        "                if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()\n"
        "            }\n\n"
    ) % MARK
    k = must_replace(
        k,
        "            override fun onShowFileChooser(\n",
        chrome_extra + "            override fun onShowFileChooser(\n",
        "onShowFileChooser",
    )

    # 5.10 download listener
    pat = re.compile(
        r"newWebView\.setDownloadListener \{ url, userAgent, contentDisposition, mimeType, _ ->\s*\n"
        r"\s*BrowserDownloadManager\.startDownload\(this, url, userAgent, contentDisposition, mimeType\)\s*\n"
        r"\s*\}"
    )
    if not pat.search(k):
        sys.exit("ERRO: setDownloadListener nao encontrado")
    k = pat.sub(
        "newWebView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->\n"
        "            handleDownloadRequest(url, userAgent, contentDisposition, mimeType)\n"
        "        }",
        k,
        count=1,
    )

    # 5.11 download direto de midia (video pode ser blob:)
    pat2 = re.compile(
        r"BrowserDownloadManager\.startDownload\(\s*this,\s*url,\s*webView\?\.settings\?\.userAgentString,\s*null,\s*null\s*\)"
    )
    if not pat2.search(k):
        sys.exit("ERRO: downloadMediaDirect nao encontrado")
    k = pat2.sub(
        "handleDownloadRequest(url, webView?.settings?.userAgentString, null, null)",
        k,
        count=1,
    )

    # 5.12 novas funcoes
    funcs = r'''    // %s
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
        try {
            val lower = url.lowercase()
            when {
                lower.startsWith("blob:") -> {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    webView?.evaluateJavascript(blobBridge.buildFetchScript(url, fileName), null)
                }
                lower.startsWith("data:") -> {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    blobBridge.saveDataUri(url, fileName)
                }
                else -> BrowserDownloadManager.startDownload(
                    this, url, userAgent, contentDisposition, mimeType
                )
            }
        } catch (e: Exception) {
            Log.e("BrowserDownload", "Falha ao iniciar download: $url", e)
            Toast.makeText(this, R.string.blob_download_failed, Toast.LENGTH_SHORT).show()
        }
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

''' % MARK
    k = must_replace(
        k,
        "\n    // XAULINXS_MEDIA_LONGPRESS_MENU\n",
        "\n" + funcs + "    // XAULINXS_MEDIA_LONGPRESS_MENU\n",
        "ancora MediaLongPressBridge",
    )

    # 5.13 Voltar fecha o video em tela cheia primeiro
    k = must_replace(
        k,
        "            sidebarExpanded -> collapseSidebar()\n",
        "            customView != null -> exitCustomView()\n"
        "            sidebarExpanded -> collapseSidebar()\n",
        "onBackPressed",
    )

    # 5.14 onDestroy
    k = must_replace(
        k,
        "        WebViewUpgrade.removeUpgradeCallback(upgradeCallback)\n        super.onDestroy()\n",
        "        exitCustomView()\n"
        "        WebViewUpgrade.removeUpgradeCallback(upgradeCallback)\n        super.onDestroy()\n",
        "onDestroy",
    )

    write(KT_MAIN, k)
    print("[ok] MainActivity corrigida")

print("\nPronto. Compile com: ./gradlew assembleDebug")
