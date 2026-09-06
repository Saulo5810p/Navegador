package com.xaulinxs.funcoes;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.xaulinxs.aosp.browser.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Lógica de download via DownloadManager, portada de
 * com.xaulinxs.webviewshell.WebViewBrowserActivity (métodos do
 * DownloadListener, resolveMimeType, queryDownloads, openDownload e
 * toShareableUri) sem alterações de lógica, só extraída pra um utilitário
 * estático reaproveitável a partir do com.xaulinxs.aosp.browser.MainActivity
 * do projeto Navegador.
 *
 * Uso típico dentro do WebView.setDownloadListener():
 *   webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
 *       DownloadHandler.startDownload(context, url, userAgent, contentDisposition, mimeType));
 */
public final class DownloadHandler {

    private DownloadHandler() {}

    /** Enfileira um download via DownloadManager, resolvendo mimetype/nome de arquivo corretamente. */
    public static void startDownload(Context context, String url, String userAgent,
            String contentDisposition, String mimeType) {
        String resolvedMimeType = resolveMimeType(url, contentDisposition, mimeType);
        String fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMimeType);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(resolvedMimeType);
        request.addRequestHeader("User-Agent", userAgent);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) {
            request.addRequestHeader("Cookie", cookie);
        }
        request.setDescription(context.getString(R.string.funcoes_download_description));
        request.setTitle(fileName);
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        dm.enqueue(request);
        Toast.makeText(context, context.getString(R.string.funcoes_download_started, fileName),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Descobre a extensão/mimetype de verdade do arquivo baixado. Ordem de
     * prioridade: 1) nome de arquivo explícito no Content-Disposition (com
     * sua própria extensão), 2) extensão presente na própria URL, 3) o
     * mimeType reportado pelo servidor — só usado por último porque muita
     * gente manda "application/octet-stream" genérico, que é justamente o
     * que gera o nome "download.bin".
     */
    public static String resolveMimeType(String url, String contentDisposition, String mimeType) {
        boolean isGeneric = mimeType == null || mimeType.isEmpty()
                || mimeType.equalsIgnoreCase("application/octet-stream")
                || mimeType.equalsIgnoreCase("binary/octet-stream");

        if (!isGeneric) {
            return mimeType;
        }

        String nameFromDisposition = null;
        if (contentDisposition != null) {
            int idx = contentDisposition.indexOf("filename=");
            if (idx >= 0) {
                nameFromDisposition = contentDisposition.substring(idx + 9)
                        .replace("\"", "").replace(";", "").trim();
            }
        }

        String candidate = !TextUtils.isEmpty(nameFromDisposition) ? nameFromDisposition : url;
        String ext = MimeTypeMap.getFileExtensionFromUrl(candidate);
        if (ext == null || ext.isEmpty()) {
            int dot = candidate.lastIndexOf('.');
            if (dot >= 0 && dot < candidate.length() - 1) {
                ext = candidate.substring(dot + 1).replaceAll("[^a-zA-Z0-9]", "");
            }
        }

        if (ext != null && !ext.isEmpty()) {
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (guessed != null) {
                return guessed;
            }
        }
        return mimeType;
    }

    /** Lê a lista de downloads feitos por este app via DownloadManager. */
    public static List<DownloadEntry> queryDownloads(Context context) {
        List<DownloadEntry> list = new ArrayList<>();
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        // Sem filtro: a partir do Android 10 o DownloadManager só devolve
        // os downloads feitos pelo próprio app que consulta, então já vem
        // isolado sem precisar de permissão extra.
        try (Cursor cursor = dm.query(new DownloadManager.Query())) {
            if (cursor == null) return list;
            int idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
            int titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
            int uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int sizeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int mimeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
            int timeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
            while (cursor.moveToNext()) {
                if (cursor.getInt(statusIdx) != DownloadManager.STATUS_SUCCESSFUL) continue;
                String localUri = cursor.getString(uriIdx);
                if (localUri == null) continue;
                list.add(new DownloadEntry(
                        cursor.getLong(idIdx),
                        cursor.getString(titleIdx),
                        Uri.parse(localUri),
                        cursor.getString(mimeIdx),
                        cursor.getLong(sizeIdx),
                        cursor.getLong(timeIdx)));
            }
        }
        return list;
    }

    /** Abre um download já concluído, resolvendo o mimetype e protegendo contra FileUriExposedException. */
    public static void openDownload(Context context, DownloadEntry entry) {
        try {
            Uri safeUri = toShareableUri(context, entry.uri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String mime = entry.mimeType != null ? entry.mimeType : "*/*";
            intent.setDataAndType(safeUri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            // Arquivos .apk caem aqui também: o próprio Android mostra a
            // tela de instalação, pedindo a permissão de "instalar apps
            // desconhecidos" pra este app se ainda não tiver sido concedida.
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.funcoes_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** Remove o registro do download E apaga o arquivo físico da pasta Downloads. */
    public static void deleteDownload(Context context, DownloadEntry entry) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        dm.remove(entry.id);
    }

    /**
     * Em algumas ROMs/versões do Android, o DownloadManager devolve a URI
     * local como file:// direto em vez de content://. Repassar um file://
     * via Intent pra outro app derruba o app com FileUriExposedException a
     * partir do Android 7 — precisa passar pelo FileProvider antes.
     */
    private static Uri toShareableUri(Context context, Uri uri) {
        if (!"file".equals(uri.getScheme())) {
            return uri; // já é content:// (ou outro esquema seguro), usa direto
        }
        File file = new File(uri.getPath());
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }
}
