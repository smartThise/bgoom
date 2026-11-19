package com.bgoom.app.ssh

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
 * Persistent background foreground service (`remoteMessaging` on Android 14+ to avoid 6h dataSync timeout).
 * - Stays alive after swiping app away (`stopWithTask="false"` + `START_STICKY`)
 * - Auto-resumes after reboot / app upgrade via BgoomBootReceiver
 * - Listens to ConnectivityManager so Wi-Fi <-> 5G transitions immediately refresh the SSH tunnel
 * - Adapts poll interval (2.5s screen-on, 6s screen-off) for near-zero battery drain
 */
class BgoomSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastNetworkChangeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BgoomApp
        if (intent?.action == ACTION_STOP) {
            app.deviceStore.updateSettings { it.copy(keepAliveReceiveEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        val active = app.deviceStore.getActiveDevice()
        if (active == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        app.deviceStore.updateSettings { it.copy(keepAliveReceiveEnabled = true) }

        val notif = BgoomNotifier.buildListeningNotification(this, active.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14/15/16: remoteMessaging has no 6-hour timeout cap
            startForeground(
                BgoomNotifier.NOTIF_ID_SERVICE,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                BgoomNotifier.NOTIF_ID_SERVICE,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(BgoomNotifier.NOTIF_ID_SERVICE, notif)
        }

        _isRunning.value = true
        registerNetworkWatcher(app)
        startPollingLoop(app)
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Gracefully handle any OS foreground timeout without crashing
        stopSelf(startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = application as? BgoomApp ?: return
        if (app.deviceStore.settings.value.keepAliveReceiveEnabled && app.deviceStore.getActiveDevice() != null) {
            // Keep service alive even when user swipes the UI card away from Recents
            runCatching { start(applicationContext) }
        }
    }

    private fun registerNetworkWatcher(app: BgoomApp) {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                if (now - lastNetworkChangeMs > 2000L) {
                    lastNetworkChangeMs = now
                    // Drop potentially half-open socket from old interface so next poll reconnects instantly
                    app.sshPool.disconnectAll()
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun startPollingLoop(app: BgoomApp) {
        if (pollJob?.isActive == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        pollJob = serviceScope.launch {
            var errorBackoffMs = 0L
            while (isActive) {
                val device = app.deviceStore.getActiveDevice()
                if (device == null) {
                    delay(5000L)
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
                    errorBackoffMs = 0L
                    if (events.isNotEmpty()) {
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
                    errorBackoffMs = minOf(errorBackoffMs + 3000L, 15_000L)
                }

                val screenOn = powerManager?.isInteractive != false
                val baseInterval = if (screenOn) 2500L else 6000L
                delay(baseInterval + errorBackoffMs)
            }
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        pollJob?.cancel()
        networkCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
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
