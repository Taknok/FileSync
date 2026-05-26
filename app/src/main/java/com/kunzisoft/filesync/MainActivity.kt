package com.kunzisoft.filesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint

import androidx.activity.viewModels
import com.kunzisoft.filesync.ui.ProviderViewModel
import com.kunzisoft.filesync.ui.SetupScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: ProviderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupScreen(viewModel)
                }
            }
        }
    }
}
