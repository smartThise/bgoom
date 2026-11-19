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
 * System-level notifications + Android 16+ Live Updates (实况胶囊 / 灵动岛) manager.
 * - Uses official Android 16 (API 36) `android.requestPromotedOngoing` + `setOngoing(true)` +
 *   `Notification.ProgressStyle` + `setShortCriticalText(...)` so BOTH live progress AND file/note
 *   arrivals satisfy `hasPromotableCharacteristics()` and promote to the status-bar live capsule.
 * - Pairs with `DynamicCapsuleOverlay` for guaranteed top-bar capsule pop-ups across MagicOS 8/9/10
 *   and all Android versions.
 */
object BgoomNotifier {

    const val CHANNEL_LIVE_CAPSULE = "bgoom_live_capsule"
    const val CHANNEL_ALERTS = "bgoom_incoming_alerts"

    const val NOTIF_ID_SERVICE = 1001
    const val NOTIF_ID_TRANSFER_CAPSULE = 1002
    private var alertSeq = 2000

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val capsule = NotificationChannel(
            CHANNEL_LIVE_CAPSULE,
            "大爆实时传输胶囊",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "实时监听电脑推送、传输进度与状态栏实况胶囊"
            setSound(null, null)
            enableVibration(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "收到电脑文件与便签提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "电脑发送文件或便签到手机时弹出系统通知，点击可直接打开"
            enableVibration(true)
        }
        nm.createNotificationChannel(capsule)
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

    fun buildListeningNotification(context: Context, deviceName: String): Notification {
        ensureChannels(context)
        val openApp = pendingOpenApp(context)
        if (Build.VERSION.SDK_INT >= 36) {
            return Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("大爆已连线 · $deviceName")
                .setContentText("实时等待电脑右键推送文件或便签")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp)
                .addExtras(promotedExtras())
                .setShortCriticalText("大爆在线")
                .build()
        }
        return NotificationCompat.Builder(context, CHANNEL_LIVE_CAPSULE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("大爆已连线 · $deviceName")
            .setContentText("实时等待电脑右键推送文件或便签")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
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

        val notif = if (Build.VERSION.SDK_INT >= 36) {
            val builder = Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp)
                .addExtras(promotedExtras())
                .setShortCriticalText(shortChip.take(10))
            if (progressPercent in 0..100) {
                builder.setStyle(
                    Notification.ProgressStyle()
                        .setProgress(progressPercent)
                        .setStyledByProgress(true)
                )
            }
            builder.build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(100, progressPercent.coerceIn(0, 100), progressPercent < 0)
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

        // 1. On Android 16 (API 36+), morph the Live Capsule into a promotable 100% ongoing arrival pill
        //    that satisfies hasPromotableCharacteristics() and auto-clears after 18 seconds (or on tap).
        if (Build.VERSION.SDK_INT >= 36) {
            val capsuleNotif = Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("收到文件 · $fileName")
                .setContentText("来自 $deviceName · 点击直接打开")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(18_000L)
                .setContentIntent(openFilePi)
                .addExtras(promotedExtras())
                .setShortCriticalText("收到文件")
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgress(100)
                        .setStyledByProgress(true)
                )
                .addAction(Notification.Action.Builder(null, "直接打开", openFilePi).build())
                .build()
            nm.notify(NOTIF_ID_TRANSFER_CAPSULE, capsuleNotif)
        } else {
            nm.cancel(NOTIF_ID_TRANSFER_CAPSULE)
        }

        // 2. Persistent heads-up notification in shade so user never misses files even after unlock.
        val shadeNotif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("收到文件 · $fileName")
            .setContentText("来自 $deviceName · 已存入「下载/BGOOM」，点击直接打开")
            .setAutoCancel(true)
            .setContentIntent(openFilePi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(0, "直接打开", openFilePi)
            .build()
        nm.notify(++alertSeq, shadeNotif)
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
        val suffix = if (copiedToClipboard) "（已同步到剪贴板）" else ""

        if (Build.VERSION.SDK_INT >= 36) {
            val capsuleNotif = Notification.Builder(context, CHANNEL_LIVE_CAPSULE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("收到便签 · $deviceName$suffix")
                .setContentText(preview.take(80))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(15_000L)
                .setContentIntent(openNotesPi)
                .addExtras(promotedExtras())
                .setShortCriticalText("收到便签")
                .setStyle(Notification.BigTextStyle().bigText(preview.take(300)))
                .build()
            nm.notify(NOTIF_ID_TRANSFER_CAPSULE, capsuleNotif)
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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

    fun showUpdateAlert(context: Context, versionTag: String, summary: String) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
