package com.jegly.files

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jegly.files.model.PickRequest
import com.jegly.files.ops.FileOperationService
import com.jegly.files.ops.OpKind
import com.jegly.files.ui.AppRoot
import com.jegly.files.ui.theme.FilesTheme
import com.jegly.files.vm.BrowserViewModel
import java.io.File

/**
 * The destination picker behind "Copy to…" / "Move to…", mirroring AOSP's
 * DirectoryFragment.transferDocuments(): browse to a folder, confirm, and the transfer starts
 * immediately.
 *
 * SEPARATE FROM [PickActivity], AND android:exported="false", DELIBERATELY.
 *
 * This flow takes a list of absolute source paths from its Intent and then copies or moves them
 * using this app's MANAGE_EXTERNAL_STORAGE. Hosting it on the exported PickActivity — which must
 * be exported to serve ACTION_GET_CONTENT — would have made it a confused deputy: any installed
 * app, holding no storage permission at all, could start an exported component with an explicit
 * ComponentName (intent filters do not restrict explicit intents) and hand us paths it cannot
 * read itself. The user would see an ordinary-looking folder picker, choose a folder the caller
 * can read, and this app would move the data there on the attacker's behalf. The Move variant
 * could also destroy files outright.
 *
 * Keeping it unexported means the platform refuses the launch, rather than us trying to detect a
 * hostile caller at runtime — `callingPackage` is null for a plain startActivity, so there is
 * nothing reliable to check against anyway.
 */
class DestinationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val request = PickRequest.from(intent)
        if (request == null || request.mode != PickRequest.Mode.Destination) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setResult(RESULT_CANCELED)

        setContent {
            FilesTheme {
                val vm: BrowserViewModel = viewModel()
                AppRoot(
                    vm = vm,
                    pick = request,
                    onDestinationChosen = { destination ->
                        FileOperationService.start(
                            context = this,
                            kind = if (request.isMove) OpKind.Move else OpKind.Copy,
                            sources = request.sources.map(::File),
                            destination = destination,
                        )
                        setResult(RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }
}
