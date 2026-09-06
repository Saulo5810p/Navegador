package com.xaulinxs.funcoes.download

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xaulinxs.aosp.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground Service que monitora um download em andamento no
 * DownloadManager e mantém uma notificação persistente com progresso real
 * (bytes baixados / total). Garante que o Android não mate o processo de
 * monitoramento enquanto o download está rodando.
 *
 * Suporta apenas um download por vez de forma simples: se um novo download
 * começar enquanto outro está sendo monitorado, o service passa a
 * monitorar o mais recente (o antigo continua no DownloadManager em
 * segundo plano - o sistema garante a persistência dele mesmo sem o
 * Foreground Service, que serve só para a notificação com progresso).
 */
class DownloadForegroundService : Service() {

    companion object {
        const val ACTION_TRACK_DOWNLOAD = "com.xaulinxs.funcoes.download.action.TRACK_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_FILE_NAME = "extra_file_name"

        private const val CHANNEL_ID = "downloads_channel"
        private const val NOTIFICATION_ID = 4200

        // Intervalo do loop de monitoramento. 500ms é responsivo o
        // suficiente pra parecer "tempo real" sem sobrecarregar o
        // ContentResolver do DownloadManager com consultas excessivas.
        private const val POLL_INTERVAL_MS = 500L
    }

    private var serviceScope: CoroutineScope? = null
    private var monitorJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        serviceScope = CoroutineScope(Dispatchers.IO + Job())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRACK_DOWNLOAD) {
            val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
            val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.download_generic_file)
            if (downloadId != -1L) {
                // Cancela o monitoramento anterior, se houver, antes de
                // começar um novo - evita duas coroutines escrevendo na
                // mesma notificação ao mesmo tempo.
                monitorJob?.cancel()
                // startForeground precisa ser chamado logo no início do
                // onStartCommand (dentro de poucos segundos), com uma
                // notificação inicial - mesmo antes da primeira consulta
                // ao DownloadManager terminar.
                startForeground(NOTIFICATION_ID, buildProgressNotification(fileName, 0L, -1L, indeterminate = true))
                monitorJob = serviceScope?.launch { monitorDownload(downloadId, fileName) }
            }
        }
        // START_NOT_STICKY: se o sistema matar o service, não recriar
        // automaticamente sem intent - o download em si continua no
        // DownloadManager (que é o ponto de usá-lo), só a notificação de
        // progresso pararia de atualizar até o próximo download.
        return START_NOT_STICKY
    }

    /**
     * Loop de monitoramento: consulta periodicamente o status do download
     * no banco de dados do DownloadManager via DownloadManager.Query, até
     * ele terminar (sucesso ou falha) ou a coroutine ser cancelada.
     */
    private suspend fun monitorDownload(downloadId: Long, fileName: String) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        while (true) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            var shouldStop = false

            downloadManager.query(query)?.use { cursor: Cursor ->
                if (!cursor.moveToFirst()) {
                    // Registro sumiu do DownloadManager (ex: usuário
                    // excluiu manualmente) - encerra o monitoramento.
                    shouldStop = true
                    return@use
                }

                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                val status = cursor.getInt(statusIdx)
                val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0
                val downloadedBytes = if (downloadedIdx >= 0) cursor.getLong(downloadedIdx) else 0L
                // COLUMN_TOTAL_SIZE_BYTES vem -1 (ou 0) quando o servidor
                // não informou Content-Length - trata-se como "desconhecido",
                // exibindo progresso indeterminado em vez de travar em 0%.
                val totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L

                when (status) {
                    DownloadManager.STATUS_RUNNING -> {
                        updateNotification(
                            buildProgressNotification(
                                fileName, downloadedBytes, totalBytes,
                                indeterminate = totalBytes <= 0
                            )
                        )
                    }

                    DownloadManager.STATUS_PAUSED -> {
                        updateNotification(buildPausedNotification(fileName, reason, downloadedBytes, totalBytes))
                    }

                    DownloadManager.STATUS_PENDING -> {
                        updateNotification(buildProgressNotification(fileName, 0L, -1L, indeterminate = true))
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val localUri = if (localUriIdx >= 0) cursor.getString(localUriIdx) else null
                        updateNotification(buildFinishedNotification(fileName, success = true, localUri = localUri))
                        shouldStop = true
                    }

                    DownloadManager.STATUS_FAILED -> {
                        updateNotification(buildFailedNotification(fileName, reason))
                        shouldStop = true
                    }
                }
            } ?: run {
                // cursor nulo: DownloadManager indisponível/erro transitório.
                shouldStop = true
            }

            if (shouldStop) break
            delay(POLL_INTERVAL_MS)
        }

        stopSelf()
    }

    private fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildProgressNotification(
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long,
        indeterminate: Boolean
    ): Notification {
        val progressText = ByteFormatter.formatProgress(downloadedBytes, totalBytes)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSubText(progressText)

        if (indeterminate) {
            // Tamanho total desconhecido (-1 ou 0 vindo do servidor):
            // barra de progresso indeterminada em vez de travar em 0%.
            builder.setProgress(0, 0, true)
            builder.setContentText(getString(R.string.download_status_running))
        } else {
            val percent = ByteFormatter.progressPercent(downloadedBytes, totalBytes)
            builder.setProgress(100, percent, false)
            builder.setContentText(progressText)
        }
        return builder.build()
    }

    private fun buildPausedNotification(
        fileName: String,
        reason: Int,
        downloadedBytes: Long,
        totalBytes: Long
    ): Notification {
        // Motivos de pausa documentados em DownloadManager: espera por
        // Wi-Fi, espera por conexão de rede em geral, espera para tentar
        // de novo após uma falha recuperável, ou pausa manual pelo app.
        val reasonText = when (reason) {
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> getString(R.string.download_paused_waiting_network)
            DownloadManager.PAUSED_WAITING_TO_RETRY -> getString(R.string.download_paused_waiting_retry)
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> getString(R.string.download_paused_waiting_wifi)
            DownloadManager.PAUSED_UNKNOWN -> getString(R.string.download_paused_unknown)
            else -> getString(R.string.download_paused_unknown)
        }
        val progressText = ByteFormatter.formatProgress(downloadedBytes, totalBytes)
        val percent = ByteFormatter.progressPercent(downloadedBytes, totalBytes)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(fileName)
            .setContentText(reasonText)
            .setSubText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true)
            }
            .build()
    }

    private fun buildFinishedNotification(fileName: String, success: Boolean, localUri: String?): Notification {
        val openIntent = if (success && localUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(localUri), "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else null

        val pendingIntent = openIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(fileName)
            .setContentText(getString(R.string.download_status_completed))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
    }

    private fun buildFailedNotification(fileName: String, reason: Int): Notification {
        // Motivos de falha documentados em DownloadManager (ERROR_*).
        val reasonText = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> getString(R.string.download_error_insufficient_space)
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> getString(R.string.download_error_device_not_found)
            DownloadManager.ERROR_CANNOT_RESUME -> getString(R.string.download_error_cannot_resume)
            DownloadManager.ERROR_HTTP_DATA_ERROR -> getString(R.string.download_error_http)
            else -> getString(R.string.download_error_generic)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(fileName)
            .setContentText(reasonText)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW // baixa: progresso não precisa de som/vibração repetidos
            ).apply {
                description = getString(R.string.download_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
