package com.bgoom.app.notify

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.bgoom.app.MainActivity

/**
 * Ultra-lightweight top-bar Dynamic Island / 灵动胶囊 floating overlay.
 * - Works on ALL Android 8-16 devices including Honor MagicOS 8/9/10 where OEM native capsules
 *   restrict third-party apps.
 * - Zero idle memory: view is only attached during active transfer/arrival and completely removed
 *   after auto-hide or tap.
 * - Safely no-ops if SYSTEM_ALERT_WINDOW permission has not been granted yet.
 */
object DynamicCapsuleOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var currentWm: WindowManager? = null
    private var progressTitleView: TextView? = null
    private var progressSubView: TextView? = null
    private var progressBarView: ProgressBar? = null
    private var isProgressMode = false
    private var autoHideRunnable: Runnable? = null

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun showProgress(
        context: Context,
        title: String,
        subtitle: String,
        progressPercent: Int,
    ) {
        val appCtx = context.applicationContext
        if (!canDrawOverlays(appCtx)) return
        mainHandler.post {
            cancelAutoHide()
            if (isProgressMode && currentView != null && progressTitleView != null && progressBarView != null) {
                progressTitleView?.text = title
                progressSubView?.text = subtitle
                progressBarView?.progress = progressPercent.coerceIn(0, 100)
                scheduleAutoHide(appCtx, 12_000L)
                return@post
            }

            removeImmediate()
            val d = appCtx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * d + 0.5f).toInt()

            val root = buildPillContainer(appCtx, dp(24), dp(14), dp(10))
            val topRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dot = buildAccentDot(appCtx, dp(8), Color.parseColor("#4D8DFF"))
            val titleTv = TextView(appCtx).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }
            val subTv = TextView(appCtx).apply {
                text = subtitle
                setTextColor(Color.parseColor("#A9B4C8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                maxLines = 1
            }
            topRow.addView(dot)
            topRow.addView(titleTv)
            topRow.addView(subTv)

            val bar = ProgressBar(appCtx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = progressPercent.coerceIn(0, 100)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
                    topMargin = dp(7)
                }
                progressDrawable.setTint(Color.parseColor("#4D8DFF"))
            }

            root.addView(topRow)
            root.addView(bar)
            root.setOnClickListener {
                dismiss(appCtx)
                val openApp = Intent(appCtx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { appCtx.startActivity(openApp) }
            }

            progressTitleView = titleTv
            progressSubView = subTv
            progressBarView = bar
            isProgressMode = true
            attachCapsule(appCtx, root, minWidthDp = 248, maxWidthDp = 340)
            scheduleAutoHide(appCtx, 12_000L)
        }
    }

    fun showFileArrived(
        context: Context,
        fileName: String,
        deviceName: String,
        contentUri: Uri,
        mimeType: String,
    ) {
        val appCtx = context.applicationContext
        if (!canDrawOverlays(appCtx)) return
        mainHandler.post {
            showActionCapsule(
                appCtx = appCtx,
                accentColor = Color.parseColor("#34C759"),
                title = "收到文件 · $fileName",
                subtitle = "来自 $deviceName · 已存入下载/BGOOM",
                primaryButtonLabel = "直接打开",
                autoHideMs = 8_500L,
                onPrimaryClick = {
                    BgoomNotifier.cancelTransferCapsule(appCtx)
                    val openIntent = Intent(appCtx, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_OPEN_RECEIVED_FILE
                        putExtra(MainActivity.EXTRA_OPEN_FILE_URI, contentUri.toString())
                        putExtra(MainActivity.EXTRA_OPEN_FILE_MIME, mimeType)
                        putExtra(MainActivity.EXTRA_OPEN_FILE_NAME, fileName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    runCatching { appCtx.startActivity(openIntent) }
                },
            )
        }
    }

    fun showNoteArrived(
        context: Context,
        deviceName: String,
        preview: String,
        copiedToClipboard: Boolean,
    ) {
        val appCtx = context.applicationContext
        if (!canDrawOverlays(appCtx)) return
        val suffix = if (copiedToClipboard) " · 已复制剪贴板" else ""
        mainHandler.post {
            showActionCapsule(
                appCtx = appCtx,
                accentColor = Color.parseColor("#4D8DFF"),
                title = "收到便签 · $deviceName$suffix",
                subtitle = preview.replace('\n', ' ').take(48),
                primaryButtonLabel = "查看便签",
                autoHideMs = 6_500L,
                onPrimaryClick = {
                    BgoomNotifier.cancelTransferCapsule(appCtx)
                    val openIntent = Intent(appCtx, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_OPEN_NOTES
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    runCatching { appCtx.startActivity(openIntent) }
                },
            )
        }
    }

    fun showTestDemo(context: Context) {
        val appCtx = context.applicationContext
        if (!canDrawOverlays(appCtx)) return
        mainHandler.post {
            showActionCapsule(
                appCtx = appCtx,
                accentColor = Color.parseColor("#34C759"),
                title = "收到文件 · 演示文档.pdf",
                subtitle = "大爆顶栏灵动胶囊已就绪 · 点击可直接打开文件",
                primaryButtonLabel = "知道了",
                autoHideMs = 5_000L,
                onPrimaryClick = {},
            )
        }
    }

    fun dismiss(context: Context? = null) {
        mainHandler.post {
            cancelAutoHide()
            val v = currentView ?: return@post
            v.animate()
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.82f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { removeImmediate() }
                .start()
        }
    }

    private fun showActionCapsule(
        appCtx: Context,
        accentColor: Int,
        title: String,
        subtitle: String,
        primaryButtonLabel: String,
        autoHideMs: Long,
        onPrimaryClick: () -> Unit,
    ) {
        cancelAutoHide()
        removeImmediate()
        isProgressMode = false
        progressTitleView = null
        progressSubView = null
        progressBarView = null

        val d = appCtx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d + 0.5f).toInt()

        val root = buildPillContainer(appCtx, dp(26), dp(16), dp(12))

        val row = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = buildAccentDot(appCtx, dp(10), accentColor)

        val textCol = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        }
        val titleTv = TextView(appCtx).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val subTv = TextView(appCtx).apply {
            text = subtitle
            setTextColor(Color.parseColor("#A9B4C8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleTv)
        textCol.addView(subTv)

        val openBtn = TextView(appCtx).apply {
            text = primaryButtonLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setOnClickListener {
                dismiss(appCtx)
                onPrimaryClick()
            }
        }

        val closeBtn = TextView(appCtx).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(4), dp(2), dp(4))
            setOnClickListener { dismiss(appCtx) }
        }

        row.addView(dot)
        row.addView(textCol)
        row.addView(openBtn)
        row.addView(closeBtn)
        root.addView(row)

        root.setOnClickListener {
            dismiss(appCtx)
            onPrimaryClick()
        }

        attachCapsule(appCtx, root, minWidthDp = 296, maxWidthDp = 364)
        scheduleAutoHide(appCtx, autoHideMs)
    }

    private fun buildPillContainer(context: Context, radiusPx: Int, padH: Int, padV: Int): LinearLayout {
        val d = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            elevation = 14f * d
            background = GradientDrawable().apply {
                cornerRadius = radiusPx.toFloat()
                setColor(Color.parseColor("#F5141821"))
                setStroke((1f * d).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
            }
        }
    }

    private fun buildAccentDot(context: Context, sizePx: Int, color: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }

    private fun attachCapsule(appCtx: Context, view: LinearLayout, minWidthDp: Int, maxWidthDp: Int) {
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val dm = appCtx.resources.displayMetrics
        val d = dm.density
        val targetWidth = minOf((maxWidthDp * d).toInt(), (dm.widthPixels - (24 * d).toInt()).coerceAtLeast((minWidthDp * d).toInt()))

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            targetWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (10 * d).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view.alpha = 0f
        view.scaleX = 0.72f
        view.scaleY = 0.65f
        view.translationY = -18f * d

        runCatching {
            wm.addView(view, lp)
            currentView = view
            currentWm = wm
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    private fun scheduleAutoHide(appCtx: Context, delayMs: Long) {
        cancelAutoHide()
        val r = Runnable { dismiss(appCtx) }
        autoHideRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        autoHideRunnable = null
    }

    private fun removeImmediate() {
        val v = currentView
        val wm = currentWm
        currentView = null
        currentWm = null
        isProgressMode = false
        progressTitleView = null
        progressSubView = null
        progressBarView = null
        if (v != null && wm != null) {
            runCatching { wm.removeViewImmediate(v) }
        }
    }
}
