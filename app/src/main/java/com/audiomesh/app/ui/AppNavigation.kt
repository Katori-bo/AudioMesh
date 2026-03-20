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
//  Bug fixed here:
//    currentRoute was read from navController.currentBackStackEntry?.destination?.route
//    which is NOT reactive — it doesn't recompose when the back stack changes.
//    This caused showMiniPlayer to stay false even after navigating back to
//    LIBRARY, hiding the mini-player permanently until a full recompose.
//
//  Fix: use currentBackStackEntryAsState() which returns a State<NavBackStackEntry?>
//    and triggers recomposition whenever the back stack changes.
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

    // Poll binder for isPlaying + duration
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

    // FIX: use currentBackStackEntryAsState() so currentRoute is reactive
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Show mini-player only on the library screen
    val showMiniPlayer = song != null && currentRoute == Routes.LIBRARY

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
                        onBack   = { navController.popBackStack() },
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

        // MiniPlayerBar — shown at the bottom of the Library screen only
        if (showMiniPlayer && song != null) {
            MiniPlayerBar(
                song       = song!!,
                progressMs = progressMs,
                durationMs = durationMs,
                isPlaying  = isPlaying,
                onTap = {
                    if (isReceiverActive && receiverSenderIp != null) {
                        navController.navigate(
                            "${Routes.RECEIVER_MAIN}/$receiverSenderIp/$receiverRole"
                        )
                    } else {
                        // Always pop back to the existing SenderMainScreen instance.
                        // If it's not on the stack, push it — but mark that we are
                        // returning to an already-playing session so swapTrack is skipped.
                        val popped = navController.popBackStack(Routes.SENDER_MAIN, inclusive = false)
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