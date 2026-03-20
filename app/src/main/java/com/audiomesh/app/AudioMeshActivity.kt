package com.audiomesh.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.audiomesh.app.ui.navigation.AppNavigation
import com.audiomesh.app.ui.screens.LibraryViewModel
import com.audiomesh.app.ui.theme.AudioMeshTheme

class AudioMeshActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start beacon listener — runs in background while app is open
        startForegroundService(Intent(this, BeaconListenerService::class.java))

        setContent {
            AudioMeshTheme(darkTheme = true) {
                val navController = rememberNavController()

                // Initialize the ViewModel at the Activity level to sync state
                val libViewModel: LibraryViewModel = viewModel()

                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE

                var permissionRequested by remember { mutableStateOf(false) }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    // Objective 2: Update VM so LibraryScreen shows/hides the lock UI
                    libViewModel.updatePermissionStatus(isGranted)
                }

                LaunchedEffect(Unit) {
                    if (!permissionRequested) {
                        permissionRequested = true

                        val isGranted = ContextCompat.checkSelfPermission(
                            this@AudioMeshActivity, permission
                        ) == PackageManager.PERMISSION_GRANTED

                        if (isGranted) {
                            libViewModel.updatePermissionStatus(true)
                        } else {
                            launcher.launch(permission)
                        }
                    }
                }

                AppNavigation(navController = navController)
            }
        }
    } // onCreate ends here

    override fun onDestroy() {
        super.onDestroy()

        // Cleanup Beacon Listener
        stopService(Intent(this, BeaconListenerService::class.java))

        // Objective 4: Housekeeping - Stop audio engines on actual app finish
        if (isFinishing) {
            stopService(Intent(this, SenderService::class.java))
            stopService(Intent(this, ReceiverService::class.java))
        }
    }
}