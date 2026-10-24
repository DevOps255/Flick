package com.flick.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flick.app.core.TransferStatus
import com.flick.app.data.IncomingTransfer
import com.flick.app.data.ReceiveCoordinator
import com.flick.app.data.TransferManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive, with a notification, while a file is sent or received.
 *
 * It starts when a transfer begins, shows the progress, and stops by itself a
 * moment after nothing is moving anymore. Android requires the notification.
 */
@AndroidEntryPoint
class TransferService : Service() {
    @Inject lateinit var sending: TransferManager

    @Inject lateinit var receiving: ReceiveCoordinator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Flick", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        watcher?.cancel()
        watcher = scope.launch {
            combine(sending.state, receiving.incoming) { out, incoming -> describe(out?.status, out?.peer?.name, out?.progress, incoming) }
                .collectLatest { line ->
                    if (line == null) {
                        // Wait a moment: a new transfer may start right after the last one.
                        delay(IDLE_MS)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(line.first, line.second))
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Title and progress (0 to 1) of what is moving, or null when nothing is. */
    private fun describe(status: TransferStatus?, peer: String?, progress: Float?, incoming: IncomingTransfer?): Pair<String, Float?>? = when {
        status == TransferStatus.Preparing -> "Connecting to $peer" to null
        status == TransferStatus.InProgress -> "Sending to $peer" to progress
        incoming != null && !incoming.finished -> "Receiving from ${incoming.sender}" to incoming.progress
        else -> null
    }

    private fun notification(title: String, progress: Float?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PERCENT, ((progress ?: 0f) * PERCENT).toInt(), progress == null)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1
        private const val PERCENT = 100
        private const val IDLE_MS = 1_500L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
