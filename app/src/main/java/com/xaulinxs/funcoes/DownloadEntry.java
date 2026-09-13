package com.xaulinxs.funcoes;

import android.app.DownloadManager;
import android.net.Uri;

/**
 * Representa uma linha da tela de Downloads, lida via DownloadManager.query()
 * - agora sem filtro de status: além dos concluídos (SUCCESSFUL), também
 * traz os que estão em andamento (RUNNING/PENDING), pausados pelo próprio
 * sistema (PAUSED, com um motivo/REASON) e os que falharam (FAILED), pra
 * exibir tudo na mesma lista com o estado real de cada um.
 */
public class DownloadEntry {
    public final long id;
    public final String title;
    public final Uri uri; // COLUMN_LOCAL_URI - só é válido quando o download está concluído
    public final String originalUrl; // COLUMN_URI - usado pra "Baixar novamente" quando FAILED
    public final String mimeType;
    public final long sizeBytes; // COLUMN_TOTAL_SIZE_BYTES
    public final long downloadedBytes; // COLUMN_BYTES_DOWNLOADED_SO_FAR
    public final long timestamp;
    public final int status; // DownloadManager.STATUS_*
    public final int reason; // DownloadManager.PAUSED_*/ERROR_*, só relevante em PAUSED/FAILED

    public DownloadEntry(long id, String title, Uri uri, String originalUrl, String mimeType,
            long sizeBytes, long downloadedBytes, long timestamp, int status, int reason) {
        this.id = id;
        this.title = title;
        this.uri = uri;
        this.originalUrl = originalUrl;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.downloadedBytes = downloadedBytes;
        this.timestamp = timestamp;
        this.status = status;
        this.reason = reason;
    }

    public boolean isApk() {
        return "application/vnd.android.package-archive".equals(mimeType)
                || (title != null && title.toLowerCase().endsWith(".apk"));
    }

    /** RUNNING, PENDING ou PAUSED - ainda não terminou (nem com sucesso, nem com falha). */
    public boolean isActive() {
        return status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_PAUSED;
    }

    /** A linha pisca só quando algo está de fato acontecendo agora (baixando/na fila). */
    public boolean isBlinking() {
        return status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING;
    }

    public String readableSize() {
        return readableBytes(sizeBytes);
    }

    public static String readableBytes(long bytes) {
        if (bytes <= 0) return "";
        String[] units = {"B", "KB", "MB", "GB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.getDefault(), "%.1f %s", size, units[unit]);
    }
}
