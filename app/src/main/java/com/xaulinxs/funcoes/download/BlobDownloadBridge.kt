package com.xaulinxs.funcoes.download

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
