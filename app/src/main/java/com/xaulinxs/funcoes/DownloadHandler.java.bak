package com.xaulinxs.funcoes;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.xaulinxs.aosp.browser.R;
import com.xaulinxs.funcoes.download.BrowserDownloadManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê/gerencia a lista de downloads do DownloadManager pra tela de
 * Downloads (DownloadsActivity/DownloadsAdapter). O download em si é
 * iniciado por BrowserDownloadManager.startDownload() (chamado direto do
 * WebView.setDownloadListener() em MainActivity) - esta classe só lida
 * com o que já foi enfileirado: consultar status, abrir, excluir/cancelar
 * e reenfileirar em caso de falha.
 */
public final class DownloadHandler {

    private DownloadHandler() {}

    /** Lê a lista de downloads feitos por este app via DownloadManager (todos os status, mais recente primeiro). */
    public static List<DownloadEntry> queryDownloads(Context context) {
        List<DownloadEntry> list = new ArrayList<>();
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        // Sem filtro de status: a partir do Android 10 o DownloadManager só
        // devolve os downloads feitos pelo próprio app que consulta, então
        // já vem isolado sem precisar de permissão extra. Ordenado pelo
        // mais recente primeiro, pra downloads ativos aparecerem no topo.
        DownloadManager.Query query = new DownloadManager.Query();
        query.orderBy(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP, DownloadManager.Query.ORDER_DESCENDING);
        try (Cursor cursor = dm.query(query)) {
            if (cursor == null) return list;
            int idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
            int titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
            int uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            int originalUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
            int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
            int sizeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int mimeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
            int timeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
            while (cursor.moveToNext()) {
                String localUri = uriIdx >= 0 ? cursor.getString(uriIdx) : null;
                list.add(new DownloadEntry(
                        cursor.getLong(idIdx),
                        cursor.getString(titleIdx),
                        localUri != null ? Uri.parse(localUri) : null,
                        originalUriIdx >= 0 ? cursor.getString(originalUriIdx) : null,
                        cursor.getString(mimeIdx),
                        sizeIdx >= 0 ? cursor.getLong(sizeIdx) : -1L,
                        downloadedIdx >= 0 ? cursor.getLong(downloadedIdx) : 0L,
                        cursor.getLong(timeIdx),
                        statusIdx >= 0 ? cursor.getInt(statusIdx) : DownloadManager.STATUS_FAILED,
                        reasonIdx >= 0 ? cursor.getInt(reasonIdx) : 0));
            }
        }
        return list;
    }

    /** Abre um download já concluído, resolvendo o mimetype e protegendo contra FileUriExposedException. */
    public static void openDownload(Context context, DownloadEntry entry) {
        if (entry.uri == null) return;
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

    /**
     * Remove o registro do download E apaga o arquivo físico da pasta
     * Downloads. É a mesma API usada tanto pra "excluir" (download já
     * concluído/com falha) quanto pra "cancelar" (download ainda
     * RUNNING/PENDING/PAUSED) - o DownloadManager não distingue os dois
     * casos, dm.remove() cobre ambos.
     */
    public static void deleteDownload(Context context, DownloadEntry entry) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        dm.remove(entry.id);
    }

    /** Reenfileira um download que falhou (COLUMN_URI guarda a URL original) e remove o registro antigo. */
    public static void retryDownload(Context context, DownloadEntry entry) {
        if (entry.originalUrl == null) return;
        BrowserDownloadManager.INSTANCE.startDownload(context, entry.originalUrl, null, null, entry.mimeType);
        deleteDownload(context, entry);
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
