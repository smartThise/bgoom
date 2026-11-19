package com.bgoom.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.bgoom.app.notify.BgoomNotifier
import com.bgoom.app.ui.BgoomRoot
import com.bgoom.app.ui.BgoomRoute
import com.bgoom.app.ui.IncomingSharePayload
import com.bgoom.app.util.AndroidFileHelper

class MainActivity : ComponentActivity() {

    private val incomingShareState = mutableStateOf<IncomingSharePayload?>(null)
    private val requestedRouteState = mutableStateOf<BgoomRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BgoomNotifier.ensureChannels(this)
        parseIncomingIntent(intent)

        val bgoomApp = application as BgoomApp
        setContent {
            BgoomRoot(
                app = bgoomApp,
                incomingShare = incomingShareState.value,
                onClearIncomingShare = { incomingShareState.value = null },
                requestedRoute = requestedRouteState.value,
                onConsumeRequestedRoute = { requestedRouteState.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIncomingIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun parseIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        // Direct open file when user taps an incoming file notification or system Live Update capsule
        if (action == ACTION_OPEN_RECEIVED_FILE) {
            BgoomNotifier.cancelTransferCapsule(this)
            val uriStr = intent.getStringExtra(EXTRA_OPEN_FILE_URI).orEmpty()
            val mime = intent.getStringExtra(EXTRA_OPEN_FILE_MIME).orEmpty()
            val name = intent.getStringExtra(EXTRA_OPEN_FILE_NAME).orEmpty()
            if (uriStr.isNotBlank()) {
                AndroidFileHelper.openFile(
                    context = this,
                    uriString = uriStr,
                    fallbackLocalPath = name,
                    mimeType = mime.ifBlank { AndroidFileHelper.guessMimeType(name) },
                )
            }
            requestedRouteState.value = BgoomRoute.TRANSFER
            return
        }

        if (action == ACTION_OPEN_NOTES) {
            BgoomNotifier.cancelTransferCapsule(this)
            requestedRouteState.value = BgoomRoute.NOTES
            return
        }

        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uriSet = LinkedHashSet<Uri>()

        if (action == Intent.ACTION_SEND) {
            val stream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (stream != null) uriSet.add(stream)
        } else {
            val streams: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            uriSet.addAll(streams)
        }

        // Also inspect ClipData (Honor Gallery & MagicOS Files often populate ClipData instead of EXTRA_STREAM)
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uriSet.add(it) }
            }
        }

        if (!text.isNullOrBlank() || uriSet.isNotEmpty()) {
            incomingShareState.value = IncomingSharePayload(
                text = text,
                uris = uriSet.toList(),
                mimeType = intent.type,
            )
        }
    }

    companion object {
        const val ACTION_OPEN_RECEIVED_FILE = "com.bgoom.app.action.OPEN_RECEIVED_FILE"
        const val ACTION_OPEN_NOTES = "com.bgoom.app.action.OPEN_NOTES"
        const val EXTRA_OPEN_FILE_URI = "extra_open_file_uri"
        const val EXTRA_OPEN_FILE_MIME = "extra_open_file_mime"
        const val EXTRA_OPEN_FILE_NAME = "extra_open_file_name"
    }
}
