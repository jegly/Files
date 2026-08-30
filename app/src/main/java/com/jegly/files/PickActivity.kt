package com.jegly.files

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jegly.files.model.FileEntry
import com.jegly.files.model.PickRequest
import com.jegly.files.ops.Opener
import com.jegly.files.ui.AppRoot
import com.jegly.files.ui.theme.FilesTheme
import com.jegly.files.vm.BrowserViewModel
import java.io.File

/**
 * Serves ACTION_GET_CONTENT for other apps, so Files shows up in "choose a file" sheets.
 *
 * Separate from MainActivity rather than a second intent-filter on it, because a picker must
 * not adopt or resurrect the browsing task — the caller expects an activity that returns a
 * result and disappears, not one that leaves a file browser sitting in their back stack.
 *
 * The gates in AppRoot apply here too, which is the point: an app that asks for a file still
 * has to get past the biometric lock, and it only ever receives a scoped content:// grant for
 * the one file the user chose.
 */
class PickActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val request = PickRequest.from(intent)
        /*
         * Destination requests are refused here even though PickRequest can parse them.
         *
         * This activity is exported so it can serve ACTION_GET_CONTENT, and an exported component
         * can be started by any app via an explicit ComponentName — intent filters do not gate
         * explicit intents. A destination request carries arbitrary source paths and ends in a
         * copy or move performed with this app's MANAGE_EXTERNAL_STORAGE, so honouring one from
         * an untrusted caller would make this a confused deputy. That flow lives in the
         * unexported DestinationActivity instead.
         */
        if (request == null || request.mode != PickRequest.Mode.File) {
            // Launched with an action we don't serve; don't pretend to be a browser.
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // If the caller goes away, a result nobody collects is better than a crash.
        setResult(RESULT_CANCELED)

        setContent {
            FilesTheme {
                val vm: BrowserViewModel = viewModel()
                AppRoot(
                    vm = vm,
                    pick = request,
                    onPicked = ::deliver,
                )
            }
        }
    }

    private fun deliver(entries: List<FileEntry>) {
        // Folders are navigable in the picker but never a result: a GET_CONTENT caller expects a
        // stream it can open, and FileProvider has no meaningful URI for a directory. Belt and
        // braces with the tap handling in BrowserScreen, since a selection can also be built by
        // "Select all".
        val uris = entries.filterNot(FileEntry::isDirectory).mapNotNull { entry ->
            runCatching { Opener.uriFor(this, entry.file) }.getOrNull()
        }
        if (uris.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val result = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) {
            result.data = uris.first()
        } else {
            // Multi-select is carried in ClipData; EXTRA_STREAM is for ACTION_SEND, not results.
            result.clipData = ClipData.newUri(contentResolver, "files", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
