package com.xaulinxs.funcoes;

import android.net.Uri;

/**
 * Representa um arquivo baixado, lido via DownloadManager.query(). Portado
 * de com.xaulinxs.webviewshell.DownloadEntry sem alterações de lógica.
 */
public class DownloadEntry {
    public final long id;
    public final String title;
    public final Uri uri;
    public final String mimeType;
    public final long sizeBytes;
    public final long timestamp;

    public DownloadEntry(long id, String title, Uri uri, String mimeType, long sizeBytes, long timestamp) {
        this.id = id;
        this.title = title;
        this.uri = uri;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.timestamp = timestamp;
    }

    public boolean isApk() {
        return "application/vnd.android.package-archive".equals(mimeType)
                || (title != null && title.toLowerCase().endsWith(".apk"));
    }

    public String readableSize() {
        if (sizeBytes <= 0) return "";
        String[] units = {"B", "KB", "MB", "GB"};
        double size = sizeBytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.getDefault(), "%.1f %s", size, units[unit]);
    }
}
