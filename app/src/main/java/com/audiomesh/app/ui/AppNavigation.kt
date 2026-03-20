package com.audiomesh.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.audiomesh.app.SenderService
import com.audiomesh.app.ui.components.MiniPlayerBar
import com.audiomesh.app.ui.screens.LibraryScreen
import com.audiomesh.app.ui.screens.NowPlayingViewModel
import com.audiomesh.app.ui.screens.RolePickerScreen
import com.audiomesh.app.ui.screens.SenderMainScreen
import com.audiomesh.app.ui.screens.ReceiverMainScreen

// ─────────────────────────────────────────────────────────────────────────────
//  AppNavigation
//
//  Session lifecycle contract:
//
//  SENDER session:
//    • Started when user taps GO LIVE in SenderMainScreen
//    • Back button → Library, MiniPlayerBar persists, audio keeps playing
//    • Stop button (■) → engine stops, MiniPlayerBar disappears
//
//  RECEIVER session:
//    • Started when user taps JOIN in LibraryScreen or NearbyMeshBanner
//    • Back button → Library, MiniPlayerBar persists, audio keeps playing
//      (isReceiverActive stays true — MiniPlayerBar taps back to receiver screen)
//    • LEAVE MESH → stopAndLeave() on binder + clearReceiver() on ViewModel
//      (isReceiverActive becomes false — MiniPlayerBar disappears)
//
//  Key invariant: clearReceiver() is ONLY called from the LEAVE MESH path.
//  Never call it on back-press. This keeps the MiniPlayerBar alive as a
//  persistent beacon back to the receiver screen, matching Spotify behaviour.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AppNavigation(
    navController: NavHostController,
    nowPlaying   : NowPlayingViewModel = viewModel(),
) {
    val song             by nowPlaying.song.collectAsState()
    val progressMs       by nowPlaying.progressMs.collectAsState()
    val isReceiverActive by nowPlaying.isReceiverActive.collectAsState()
    val receiverSenderIp by nowPlaying.receiverSenderIp.collectAsState()
    val receiverRole     by nowPlaying.receiverRole.collectAsState()

    var isPlaying  by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }

    // Poll binder for isPlaying + duration — works for both sender and receiver
    LaunchedEffect(song, isReceiverActive) {
        while (true) {
            if (!isReceiverActive) {
                val b = SenderService.getBinder()
                if (b != null) {
                    isPlaying  = !b.isPaused
                    durationMs = b.durationMs
                }
            } else {
                val b = com.audiomesh.app.ReceiverService.getBinder()
                if (b != null) {
                    isPlaying  = b.isConnected()
                    durationMs = b.durationMs
                }
            }
            kotlinx.coroutines.delay(500)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Show MiniPlayerBar on Library screen when:
    //   • Sender has a song selected (song != null), OR
    //   • Receiver session is active (isReceiverActive)
    val showMiniPlayer = (song != null || isReceiverActive) && currentRoute == Routes.LIBRARY

    Column(modifier = Modifier.fillMaxSize()) {

        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController    = navController,
                startDestination = Routes.LIBRARY,
            ) {

                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        onSendToMesh = { song, role ->
                            nowPlaying.select(song, role)
                            navController.navigate(Routes.SENDER_MAIN) {
                                popUpTo(Routes.SENDER_MAIN) { inclusive = true }
                            }
                        },
                        onPlayLocally = { song ->
                            nowPlaying.select(
                                song,
                                com.audiomesh.app.ui.components.SenderRole.FULL,
                                com.audiomesh.app.ui.screens.SenderMode.LOCAL,
                            )
                            navController.navigate(Routes.SENDER_MAIN) {
                                popUpTo(Routes.SENDER_MAIN) { inclusive = true }
                            }
                        },
                        onJoinMesh = { senderIp, role ->
                            // Don't allow joining as receiver while acting as sender
                            if (com.audiomesh.app.SenderService.getBinder() != null) return@LibraryScreen
                            nowPlaying.setReceiverActive(senderIp, role)
                            navController.navigate("${Routes.RECEIVER_MAIN}/$senderIp/$role")
                        },
                        nowPlaying = nowPlaying,
                    )
                }

                composable(Routes.SENDER_MAIN) {
                    SenderMainScreen(
                        onBack     = { navController.popBackStack() },
                        nowPlaying = nowPlaying,
                    )
                }

                composable("${Routes.RECEIVER_MAIN}/{senderIp}/{role}") { backStackEntry ->
                    val senderIp = backStackEntry.arguments?.getString("senderIp") ?: ""
                    val role     = backStackEntry.arguments?.getString("role") ?: "FULL"
                    ReceiverMainScreen(
                        onBack = {
                            // ── Back button: keep audio playing, go to Library ─
                            // Do NOT call nowPlaying.clearReceiver() here.
                            // isReceiverActive stays true → MiniPlayerBar shows
                            // → user can tap it to come back to this screen.
                            navController.popBackStack()
                        },
                        onLeave = {
                            // ── LEAVE MESH: stop engine, clear state, go to Library ──
                            // This is the only path that calls clearReceiver().
                            // The binder.stopAndLeave() call is made inside
                            // ReceiverMainScreen before invoking this lambda.
                            nowPlaying.clearReceiver()
                            navController.popBackStack()
                        },
                        senderIp = senderIp,
                        role     = role,
                    )
                }

                composable(Routes.ROLE_PICKER) {
                    RolePickerScreen(
                        onSenderSelected = {
                            navController.navigate(Routes.SENDER_MAIN) {
                                popUpTo(Routes.ROLE_PICKER) { inclusive = true }
                            }
                        },
                        onReceiverSelected = {
                            navController.navigate(Routes.RECEIVER_MAIN) {
                                popUpTo(Routes.ROLE_PICKER) { inclusive = true }
                            }
                        }
                    )
                }
            }
        }

        // ── MiniPlayerBar ──────────────────────────────────────────────────────
        if (showMiniPlayer) {
            // In receiver mode song is null — synthesise a placeholder so the bar
            // renders with the sender's IP as the subtitle.
            val displaySong = song ?: com.audiomesh.app.data.Song(
                id          = -1L,
                title       = "AudioMesh",
                artist      = receiverSenderIp ?: "Receiver",
                album       = "",
                durationMs  = 0L,
                uri         = android.net.Uri.EMPTY,
                albumArtUri = null,
            )
            MiniPlayerBar(
                song       = displaySong,
                progressMs = progressMs,
                durationMs = durationMs,
                isPlaying  = isPlaying,
                onTap = {
                    if (isReceiverActive && receiverSenderIp != null) {
                        // Navigate back to receiver screen — service is still running
                        navController.navigate(
                            "${Routes.RECEIVER_MAIN}/$receiverSenderIp/$receiverRole"
                        )
                    } else {
                        // Navigate back to sender screen — pop to existing instance
                        // so we don't create a duplicate and re-trigger swapTrack
                        val popped = navController.popBackStack(
                            Routes.SENDER_MAIN, inclusive = false
                        )
                        if (!popped) {
                            nowPlaying.lastSwappedSongId = nowPlaying.song.value?.id ?: -1L
                            navController.navigate(Routes.SENDER_MAIN)
                        }
                    }
                },
                onPlayPause = {
                    if (!isReceiverActive) {
                        SenderService.getBinder()?.let { b ->
                            if (b.isPaused) b.resume() else b.pause()
                        }
                    }
                    // Receiver cannot pause independently — sender drives it
                },
            )
        }
    }
}