package com.jegly.files

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jegly.files.ui.AppRoot
import com.jegly.files.ui.theme.FilesTheme
import com.jegly.files.vm.BrowserViewModel

/**
 * A plain ComponentActivity. The biometric lock uses the platform BiometricPrompt rather than
 * androidx.biometric, so nothing here needs to be a FragmentActivity.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FilesTheme {
                val vm: BrowserViewModel = viewModel()
                AppRoot(vm)
            }
        }
    }
}
