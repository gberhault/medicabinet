package com.medicabinet.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.medicabinet.app.app.BootstrapViewModel
import com.medicabinet.app.app.RootScreen
import com.medicabinet.app.ui.theme.MediCabinetTheme

class MainActivity : ComponentActivity() {
    private val app: MediCabinetApplication get() = application as MediCabinetApplication

    private val viewModel: BootstrapViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BootstrapViewModel(app::openCabinet) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity (rotation) must not replay the tap or the file that started it.
        if (savedInstanceState == null) route(intent)
        setContent {
            MediCabinetTheme {
                RootScreen(viewModel, tabRouter = app.tabRouter, importCoordinator = app.importCoordinator)
            }
        }
    }

    /** The digest was tapped, or a file opened with the app, while it was open (the activity is single-top). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    /** A notification tap switches tab; an archive opened or shared with the app is read now (its grant may end). */
    private fun route(intent: Intent?) {
        if (!app.importCoordinator.receive(intent)) app.tabRouter.handle(intent)
    }
}
