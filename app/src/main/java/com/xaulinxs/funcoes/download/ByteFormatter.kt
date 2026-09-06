package com.xaulinxs.funcoes.download

import java.util.Locale
import kotlin.math.min

/** Converte bytes brutos em strings legíveis para o usuário. */
object ByteFormatter {

    /** Ex: 4404019 -> "4.2 MB" */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        val pattern = if (unitIndex == 0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.getDefault(), pattern, size, units[unitIndex])
    }

    /**
     * Ex: (4404019, 15728640) -> "4.2 MB / 15.0 MB"
     * Se o total for desconhecido (<= 0), retorna só o valor baixado até
     * agora (ex: "4.2 MB baixados").
     */
    fun formatProgress(downloadedBytes: Long, totalBytes: Long): String {
        return if (totalBytes > 0) {
            "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
        } else {
            "${formatBytes(downloadedBytes)} baixados"
        }
    }

    /** Percentual 0-100 para setProgress(); retorna -1 se o total for desconhecido (indeterminado). */
    fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0) return -1
        val percent = ((downloadedBytes * 100L) / totalBytes).toInt()
        return min(percent, 100)
    }
}
