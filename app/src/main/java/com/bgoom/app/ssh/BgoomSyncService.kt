package com.bgoom.app.ssh

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.bgoom.app.BgoomApp
import com.bgoom.app.notify.BgoomNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight background foreground-service reusing the warm SSH session to receive
 * Mac Finder right-click files & selected-text notes in real time (1-2s latency),
 * triggering Android 16 Live Capsule / 灵动岛 progress and high-priority arrival alerts.
 */
class BgoomSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as BgoomApp
        val active = app.deviceStore.getActiveDevice()
        if (active == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = BgoomNotifier.buildListeningNotification(this, active.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                BgoomNotifier.NOTIF_ID_SERVICE,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(BgoomNotifier.NOTIF_ID_SERVICE, notif)
        }

        _isRunning.value = true
        startPollingLoop(app)
        return START_STICKY
    }

    private fun startPollingLoop(app: BgoomApp) {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            var backoffMs = 2500L
            while (isActive) {
                val device = app.deviceStore.getActiveDevice()
                if (device == null) {
                    delay(4000L)
                    continue
                }
                val settings = app.deviceStore.settings.value
                val res = app.sshRepo.pollAndConsumeOutbox(
                    context = applicationContext,
                    device = device,
                    autoCopyText = settings.autoCopyIncomingText,
                    notifyDesktopReceipt = settings.notifyDesktopOnSend,
                    onFileProgress = { fileName, p ->
                        val pct = (p * 100).toInt().coerceIn(0, 100)
                        BgoomNotifier.showTransferCapsule(
                            context = applicationContext,
                            title = "正在接收 · $fileName",
                            subtitle = "来自 ${device.name} ($pct%)",
                            shortChip = "接收 $pct%",
                            progressPercent = pct,
                            ongoing = true,
                        )
                    }
                )
                res.onSuccess { events ->
                    backoffMs = 2500L
                    if (events.isNotEmpty()) {
                        BgoomNotifier.cancelTransferCapsule(applicationContext)
                        for (ev in events) {
                            if (ev.isFile && ev.savedFile != null) {
                                BgoomNotifier.showIncomingFileAlert(
                                    context = applicationContext,
                                    fileName = ev.savedFile.fileName,
                                    deviceName = device.name,
                                    contentUri = ev.savedFile.uri,
                                    mimeType = ev.savedFile.mimeType,
                                )
                            } else if (!ev.isFile) {
                                BgoomNotifier.showIncomingNoteAlert(
                                    context = applicationContext,
                                    title = ev.title,
                                    preview = ev.previewOrPath,
                                    deviceName = device.name,
                                    copiedToClipboard = settings.autoCopyIncomingText,
                                )
                            }
                        }
                    }
                }.onFailure {
                    BgoomNotifier.cancelTransferCapsule(applicationContext)
                    backoffMs = minOf(backoffMs + 2500L, 12_000L)
                }
                delay(backoffMs)
            }
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        pollJob?.cancel()
        BgoomNotifier.cancelTransferCapsule(applicationContext)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.bgoom.app.action.START_SYNC"
        private const val ACTION_STOP = "com.bgoom.app.action.STOP_SYNC"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, BgoomSyncService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BgoomSyncService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
