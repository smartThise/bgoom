package com.bgoom.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bgoom.app.MainActivity
import com.bgoom.app.R

/**
 * Pure OS-level notification & Android 16+ Live Updates (系统级实况胶囊) manager.
 *
 * Key design rules for genuine OS-level Live Updates (API 36 / MagicOS 10 / ColorOS 16 / HyperOS 3):
 * 1. The idle 24/7 foreground service runs on a quiet IMPORTANCE_LOW channel WITHOUT promoted flags,
 *    so it never hogs or throttles the app's Live Capsule slot.
 * 2. Active transfers & incoming arrivals use a dedicated IMPORTANCE_HIGH channel (`bgoom_live_capsule_v2`)
 *    with `setOngoing(true)`, `android.requestPromotedOngoing = true`, explicit `ProgressStyle` segment,
 *    `setShortCriticalText(...)`, and a monochrome 24dp status-bar icon (`ic_stat_bgoom`).
 * 3. Arrival notifications set `setOnlyAlertOnce(false)` so the system expands/pops the capsule on arrival,
 *    with direct tap-to-open, a "关闭" action, and a 45s auto-timeout.
 */
object BgoomNotifier {

    const val CHANNEL_SERVICE = "bgoom_bg_service"
    const val CHANNEL_LIVE_CAPSULE = "bgoom_live_capsule_v2"
    const val CHANNEL_ALERTS = "bgoom_incoming_alerts"

    const val NOTIF_ID_SERVICE = 1001
    const val NOTIF_ID_TRANSFER_CAPSULE = 1002
    private var alertSeq = 2000

    private const val ACCENT_BLUE = 0xFF0B57D0.toInt()
    private const val ACCENT_GREEN = 0xFF188038.toInt()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val bgService = NotificationChannel(
            CHANNEL_SERVICE,
            "后台常驻连接状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持手机与电脑的加密连接在线（静默常驻，不占用实况胶囊）"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val liveCapsule = NotificationChannel(
            CHANNEL_LIVE_CAPSULE,
            "大爆系统实况胶囊",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "收发电脑文件与便签时触发系统级实况胶囊 / 实时更新，点击直接打开"
            enableVibration(true)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "收到电脑文件与便签提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "电脑发送文件或便签到手机时弹出系统高优提醒，点击可直接打开"
            enableVibration(true)
        }

        nm.createNotificationChannel(bgService)
        nm.createNotificationChannel(liveCapsule)
        nm.createNotificationChannel(alerts)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun canPostPromotedNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
    }

    fun openPromotedNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(intent) }
    }

    private fun promotedExtras(): Bundle = Bundle().apply {
        putBoolean("android.requestPromotedOngoing", true)
    }

    /**
     * Quiet foreground service notification — intentionally NOT promoted so it never occupies
     * the system Live Capsule slot while idle.
     */
    fun buildListeningNotification(context: Context, deviceName: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bgoom)
            .setContentTitle("大爆已连线 · $deviceName")
            .setContentText("后台静默待命，电脑发来文件自动接收")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingOpenApp(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showTransferCapsule(
        context: Context,
        title: String,
        subtitle: String,
        shortChip: String,
        progressPercent: Int,
        ongoing: Boolean = true,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = pendingOpenApp(context)
        val pct = progressPercent.coerceIn(0, 100)

        val notif = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_stat_bgoom)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(openApp)
                .addExtras(promotedExtras())
                .setShortCriticalText(shortChip.take(7))
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(
                            listOf(Notification.ProgressStyle.Segment(100).setColor(ACCENT_BLUE))
                        )
                        .setProgress(pct)
                        .setStyledByProgress(true)
                )
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_stat_bgoom)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, progressPercent < 0)
                .setContentIntent(openApp)
                .build()
        }
        nm.notify(NOTIF_ID_TRANSFER_CAPSULE, notif)
    }

    fun cancelTransferCapsule(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_TRANSFER_CAPSULE)
    }

    fun showIncomingFileAlert(
        context: Context,
        fileName: String,
        deviceName: String,
        contentUri: Uri,
        mimeType: String,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openFilePi = pendingOpenFile(context, fileName, contentUri, mimeType)
        val dismissPi = pendingDismissCapsule(context)

        if (Build.VERSION.SDK_INT >= 36 && canPostPromotedNotifications(context)) {
            // Genuine Android 16 promoted Live Update capsule on IMPORTANCE_HIGH channel.
            // Cancel first + setOnlyAlertOnce(false) so the OS pops/expands the capsule on arrival!
            nm.cancel(NOTIF_ID_TRANSFER_CAPSULE)
            val capsuleNotif = Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_stat_bgoom)
                .setContentTitle("收到文件 · $fileName")
                .setContentText("来自 $deviceName · 已存至「下载/BGOOM」，点击直接打开")
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setTimeoutAfter(45_000L)
                .setContentIntent(openFilePi)
                .addExtras(promotedExtras())
                .setShortCriticalText("收到文件")
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(
                            listOf(Notification.ProgressStyle.Segment(100).setColor(ACCENT_GREEN))
                        )
                        .setProgress(100)
                        .setStyledByProgress(true)
                )
                .addAction(Notification.Action.Builder(null, "直接打开", openFilePi).build())
                .addAction(Notification.Action.Builder(null, "关闭", dismissPi).build())
                .build()
            nm.notify(NOTIF_ID_TRANSFER_CAPSULE, capsuleNotif)
            return
        }

        nm.cancel(NOTIF_ID_TRANSFER_CAPSULE)
        val alertNotif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bgoom)
            .setContentTitle("收到文件 · $fileName")
            .setContentText("来自 $deviceName · 已存入「下载/BGOOM」，点击直接打开")
            .setAutoCancel(true)
            .setContentIntent(openFilePi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(0, "直接打开", openFilePi)
            .build()
        nm.notify(++alertSeq, alertNotif)
    }

    fun showIncomingNoteAlert(
        context: Context,
        title: String,
        preview: String,
        deviceName: String,
        copiedToClipboard: Boolean,
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openNotesPi = PendingIntent.getActivity(
            context,
            ++alertSeq,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_NOTES
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissPi = pendingDismissCapsule(context)
        val suffix = if (copiedToClipboard) "（已复制剪贴板）" else ""

        if (Build.VERSION.SDK_INT >= 36 && canPostPromotedNotifications(context)) {
            nm.cancel(NOTIF_ID_TRANSFER_CAPSULE)
            val capsuleNotif = Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_stat_bgoom)
                .setContentTitle("收到便签 · $deviceName$suffix")
                .setContentText(preview.take(100))
                .setStyle(Notification.BigTextStyle().bigText(preview.take(400)))
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setTimeoutAfter(30_000L)
                .setContentIntent(openNotesPi)
                .addExtras(promotedExtras())
                .setShortCriticalText("收到便签")
                .addAction(Notification.Action.Builder(null, "查看", openNotesPi).build())
                .addAction(Notification.Action.Builder(null, "关闭", dismissPi).build())
                .build()
            nm.notify(NOTIF_ID_TRANSFER_CAPSULE, capsuleNotif)
            return
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bgoom)
            .setContentTitle("收到便签 · $deviceName$suffix")
            .setContentText(preview.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(500)))
            .setAutoCancel(true)
            .setContentIntent(openNotesPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        nm.notify(++alertSeq, notif)
    }

    /**
     * Sends a genuine OS-level Android 16 promoted Live Update test notification and reports
     * exact OS capability/permission diagnostics (`canPostPromotedNotifications` + `hasPromotableCharacteristics`).
     */
    fun postTestSystemCapsule(context: Context): String {
        if (!hasNotificationPermission(context)) {
            return "未开启通知权限，请先允许大爆发送通知"
        }
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 36) {
            return "当前为 Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})，系统原生实况胶囊需 Android 16+"
        }
        val canPost = canPostPromotedNotifications(context)
        nm.cancel(NOTIF_ID_TRANSFER_CAPSULE)
        val notif = Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
            .setSmallIcon(R.drawable.ic_stat_bgoom)
            .setContentTitle("收到文件 · 测试文档.pdf")
            .setContentText("系统级实况胶囊测试（15 秒后自动收回）")
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setTimeoutAfter(15_000L)
            .setContentIntent(pendingOpenApp(context))
            .addExtras(promotedExtras())
            .setShortCriticalText("收到文件")
            .setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(
                        listOf(Notification.ProgressStyle.Segment(100).setColor(ACCENT_GREEN))
                    )
                    .setProgress(100)
                    .setStyledByProgress(true)
            )
            .addAction(Notification.Action.Builder(null, "关闭", pendingDismissCapsule(context)).build())
            .build()
        val promotable = runCatching { notif.hasPromotableCharacteristics() }.getOrDefault(false)
        nm.notify(NOTIF_ID_TRANSFER_CAPSULE, notif)
        return if (canPost && promotable) {
            "已触发系统级实况胶囊（API ${Build.VERSION.SDK_INT} · 授权=是 · 合规=是）"
        } else if (!canPost) {
            "系统未授权实况通知（canPostPromoted=false），请先点「开启系统实况胶囊」授权"
        } else {
            "已发送通知（授权=$canPost · 合规=$promotable）"
        }
    }

    fun showUpdateAlert(context: Context, versionTag: String, summary: String) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bgoom)
            .setContentTitle("大爆有新版本 $versionTag")
            .setContentText(summary.ifBlank { "点击打开应用内一键下载并安装更新" })
            .setAutoCancel(true)
            .setContentIntent(pendingOpenApp(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(++alertSeq, notif)
    }

    private fun pendingOpenFile(
        context: Context,
        fileName: String,
        contentUri: Uri,
        mimeType: String,
    ): PendingIntent {
        val openViaTrampoline = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_RECEIVED_FILE
            putExtra(MainActivity.EXTRA_OPEN_FILE_URI, contentUri.toString())
            putExtra(MainActivity.EXTRA_OPEN_FILE_MIME, mimeType)
            putExtra(MainActivity.EXTRA_OPEN_FILE_NAME, fileName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            ++alertSeq,
            openViaTrampoline,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingDismissCapsule(context: Context): PendingIntent {
        val intent = Intent(context, BgoomNotifActionReceiver::class.java).apply {
            action = BgoomNotifActionReceiver.ACTION_DISMISS_CAPSULE
        }
        return PendingIntent.getBroadcast(
            context,
            NOTIF_ID_TRANSFER_CAPSULE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingOpenApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
