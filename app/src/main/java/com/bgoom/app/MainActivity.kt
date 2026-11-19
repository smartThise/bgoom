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
import com.bgoom.app.ui.IncomingSharePayload

class MainActivity : ComponentActivity() {

    private val incomingShareState = mutableStateOf<IncomingSharePayload?>(null)

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
}
