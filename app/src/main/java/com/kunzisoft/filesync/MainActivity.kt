package com.kunzisoft.filesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.kunzisoft.filesync.ui.ConflictScreen
import com.kunzisoft.filesync.ui.ProviderViewModel
import com.kunzisoft.filesync.ui.SetupScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: ProviderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val initialTab = intent.getIntExtra(NotificationHelper.EXTRA_TAB, 0)

        setContent {
            MaterialTheme {
                var selectedTab by remember { mutableIntStateOf(initialTab) }
                val conflicts by viewModel.conflicts.collectAsState()

                // Update tab if intent changes (e.g. notification clicked while app is running)
                LaunchedEffect(intent) {
                    val tab = intent.getIntExtra(NotificationHelper.EXTRA_TAB, -1)
                    if (tab != -1) {
                        selectedTab = tab
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(painterResource(R.drawable.ic_settings_24px), contentDescription = "Setup") },
                                label = { Text("Setup") },
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 }
                            )
                            NavigationBarItem(
                                icon = {
                                    BadgedBox(badge = {
                                        if (conflicts.isNotEmpty()) {
                                            Badge { Text(conflicts.size.toString()) }
                                        }
                                    }) {
                                        Icon(painterResource(R.drawable.ic_list_24px), contentDescription = "Conflicts")
                                    }
                                },
                                label = { Text("Conflicts") },
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 }
                            )
                        }
                    }
                ) { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (selectedTab) {
                            0 -> SetupScreen(viewModel)
                            1 -> ConflictScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}
