package com.medicabinet.app

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
    private val viewModel: BootstrapViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BootstrapViewModel((application as MediCabinetApplication).bootstrap::run) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MediCabinetTheme { RootScreen(viewModel) } }
    }
}
