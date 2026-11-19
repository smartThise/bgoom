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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bgoom.app.MainActivity
import com.bgoom.app.R

/**
 * System-level notifications + Android 16+ Live Updates (实况胶囊 / 灵动岛) manager.
 * - Uses official Android 16 (API 36) `setRequestPromotedOngoing(true)` + `setShortCriticalText(...)`
 *   so transfers and incoming pushes promote to the system status-bar live capsule.
 * - Also delivers high-priority heads-up floating banners with direct "打开文件" / "查看便签" actions
 *   on Android 13/14/15/16 (MagicOS & generic Android).
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

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openFilePi = PendingIntent.getActivity(
            context,
            ++alertSeq,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("收到文件 · $fileName")
                .setContentText("来自 $deviceName · 已存入「下载/BGOOM」，点击直接打开")
                .setAutoCancel(true)
                .setContentIntent(openFilePi)
                .addExtras(promotedExtras())
                .setShortCriticalText("收到文件")
                .addAction(
                    Notification.Action.Builder(null, "直接打开", openFilePi).build()
                )
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("收到文件 · $fileName")
                .setContentText("来自 $deviceName · 已存入「下载/BGOOM」，点击直接打开")
                .setAutoCancel(true)
                .setContentIntent(openFilePi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .addAction(0, "直接打开", openFilePi)
                .build()
        }
        nm.notify(++alertSeq, notif)
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
        val openApp = pendingOpenApp(context)
        val suffix = if (copiedToClipboard) "（已同步到剪贴板）" else ""

        val notif = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("收到便签 · $deviceName$suffix")
                .setContentText(preview.take(120))
                .setStyle(Notification.BigTextStyle().bigText(preview.take(500)))
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .addExtras(promotedExtras())
                .setShortCriticalText("收到便签")
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("收到便签 · $deviceName$suffix")
                .setContentText(preview.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(500)))
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
        }
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
