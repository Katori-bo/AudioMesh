package com.audiomesh.app.ui.screens
import androidx.compose.foundation.lazy.itemsIndexed
import com.audiomesh.app.ui.components.DevicesSheet
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.lazy.LazyColumn
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.audiomesh.app.NativeEngine
import com.audiomesh.app.SenderService
import com.audiomesh.app.data.Song
import com.audiomesh.app.data.SongRepository
import com.audiomesh.app.ui.components.SenderRole
import com.audiomesh.app.ui.components.SongArtwork
import com.audiomesh.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SenderMode { LOCAL, MESH }

private enum class SenderScreenState { READY, LIVE }

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun SenderMainScreen(
    songUri   : String = "",
    roleArg   : String = "FULL",
    onBack    : () -> Unit = {},
    nowPlaying: NowPlayingViewModel = viewModel(),
) {
    val song by nowPlaying.song.collectAsState()
    val role by nowPlaying.role.collectAsState()
    val mode by nowPlaying.mode.collectAsState()

    if (song == null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0D0D0D)),
            contentAlignment = Alignment.Center,
        ) { Text("No track selected", color = Color(0xFF555555)) }
        return
    }

    SenderScreenContent(
        song         = song!!,
        role         = role,
        mode         = mode,
        onBack       = onBack,
        onRoleChange = { nowPlaying.clearRole(it) },
        nowPlaying   = nowPlaying,
    )
}

// ── Main content ──────────────────────────────────────────────────────────────

@Composable
private fun SenderScreenContent(
    song        : Song,
    role        : SenderRole,
    mode        : SenderMode,
    onBack      : () -> Unit,
    onRoleChange: (SenderRole) -> Unit,
    nowPlaying  : NowPlayingViewModel,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var screenState by remember {
        mutableStateOf(
            if (SenderService.getBinder() != null) SenderScreenState.LIVE
            else if (mode == SenderMode.LOCAL) SenderScreenState.LIVE
            else SenderScreenState.READY
        )
    }

    var positionMs    by remember { mutableLongStateOf(0L) }
    var durationMs    by remember { mutableLongStateOf(0L) }
    var isPaused      by remember { mutableStateOf(false) }
    var deviceCount   by remember { mutableIntStateOf(0) }
    var seekDragging  by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var showDevicesSheet by remember { mutableStateOf(false) }
    var showQueueSheet   by remember { mutableStateOf(false) }

    var vibrantColor by remember { mutableStateOf(RoleFull) }
    var palHex1      by remember { mutableStateOf("") }
    var palHex2      by remember { mutableStateOf("") }

    // ── LOCAL mode: start service immediately ─────────────────────────────────
    LaunchedEffect(Unit) {
        if (mode == SenderMode.LOCAL && SenderService.getBinder() == null) {
            startSenderService(context, song, palHex1, palHex2, localOnly = true)
            scope.launch {
                var waited = 0
                while (waited < 3000) {
                    val b = SenderService.getBinder()
                    if (b != null) {
                        b.setLocalRole(role.name.lowercase(), 250f, 4000f)
                        break
                    }
                    delay(100)
                    waited += 100
                }
            }
        }
    }

    // ── Track swap — the one LaunchedEffect that owns swapTrack ──────────────
    //
    // BUG HISTORY:
    // Previously there were TWO LaunchedEffect blocks that both touched
    // lastSwappedSongId:
    //   (a) LaunchedEffect(Unit) — pre-armed the guard to song.id if binder != null
    //   (b) LaunchedEffect(song.id) — called swapTrack if song.id != lastSwappedSongId
    //
    // These two effects raced on composition. On fresh entry (navigating from
    // Library), both fire nearly simultaneously. If (a) ran first, it set
    // lastSwappedSongId = song.id, so (b) saw them equal and skipped swapTrack.
    // Result: new track never started playing. The user had to stop and re-select.
    //
    // FIX: one single LaunchedEffect keyed on song.id. The guard logic is:
    //   • If service is running AND this is the same song that's already
    //     streaming (lastSwappedSongId == song.id) → skip (returning via miniplayer)
    //   • Otherwise → call swapTrack, update guard
    //
    // lastSwappedSongId is reset to -1L in NowPlayingViewModel.select() every
    // time the user picks a new song from the Library, guaranteeing the first
    // play always gets through.
    LaunchedEffect(song.id) {
        val binder = SenderService.getBinder()
        if (binder != null && screenState == SenderScreenState.LIVE) {
            if (song.id != nowPlaying.lastSwappedSongId) {
                // New song — swap the track on the running engine
                nowPlaying.lastSwappedSongId = song.id
                binder.swapTrack(
                    song.uri.toString(),
                    song.title,
                    song.artist,
                    palHex1,
                    palHex2,
                )
            }
            // else: same song already playing (returned via miniplayer) — no-op
        } else if (binder != null) {
            // Service running but screen is in READY state (shouldn't normally
            // happen, but guard anyway). Mark as pre-armed so GO LIVE doesn't
            // double-swap.
            nowPlaying.lastSwappedSongId = song.id
        }

        // Extract album art palette for the current song regardless of swap
        val artSource = song.remoteArtUrl ?: song.albumArtUri ?: return@LaunchedEffect
        try {
            val loader  = ImageLoader(context)
            val req     = ImageRequest.Builder(context).data(artSource).build()
            val result  = loader.execute(req)
            if (result is SuccessResult) {
                val bmp    = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
                val scaled = Bitmap.createScaledBitmap(bmp, 100, 100, false)
                Palette.from(scaled).generate { palette ->
                    palette?.vibrantSwatch?.rgb?.let {
                        vibrantColor = Color(it)
                        palHex1      = "#%06X".format(it and 0xFFFFFF)
                    }
                    palette?.dominantSwatch?.rgb?.let {
                        if (vibrantColor == RoleFull) vibrantColor = Color(it)
                        palHex2 = "#%06X".format(it and 0xFFFFFF)
                    }
                    SenderService.getBinder()?.let { b ->
                        NativeEngine.senderSetPaletteHex(b.nativeHandle, palHex1, palHex2)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    // ── Polling loop ──────────────────────────────────────────────────────────
    LaunchedEffect(screenState) {
        if (screenState != SenderScreenState.LIVE) return@LaunchedEffect
        while (isActive) {
            SenderService.getBinder()?.let { b ->
                if (!seekDragging) {
                    positionMs = b.positionMs
                    nowPlaying.updateProgress(context, b.positionMs)
                }
                durationMs  = b.durationMs
                isPaused    = b.isPaused
                val stats   = b.clientStats ?: ""
                deviceCount = if (stats.isBlank()) 0
                else stats.trim().split("\n").count { it.isNotBlank() }
            }
            delay(250)
        }
    }

    // ── Full-bleed layout ─────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {

        val artSource = song.remoteArtUrl ?: song.albumArtUri
        if (artSource != null) {
            AsyncImage(
                model              = artSource,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize().blur(20.dp),
            )
        } else {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(SongRepository.fallbackColor(song.title)))
            )
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.50f),
                        Color.Black.copy(alpha = 0.70f),
                        Color.Black.copy(alpha = 0.90f),
                    )
                )
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Spacer(Modifier.height(16.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                BackButton(onClick = onBack)

                RoleBadge(
                    role    = role,
                    enabled = screenState == SenderScreenState.READY,
                    onCycle = {
                        onRoleChange(when (role) {
                            SenderRole.FULL   -> SenderRole.BASS
                            SenderRole.BASS   -> SenderRole.MID
                            SenderRole.MID    -> SenderRole.TREBLE
                            SenderRole.TREBLE -> SenderRole.FULL
                        })
                    }
                )
            }

            Spacer(Modifier.weight(0.6f))

            SongArtwork(
                song     = song,
                size     = 240,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, vibrantColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            )

            Spacer(Modifier.weight(0.4f))

            Text(
                text       = song.title,
                style      = MaterialTheme.typography.headlineLarge,
                color      = Color.White,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text     = song.artist,
                style    = MaterialTheme.typography.headlineMedium,
                color    = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(24.dp))

            AnimatedVisibility(visible = screenState == SenderScreenState.LIVE) {
                SeekSection(
                    positionMs    = positionMs,
                    durationMs    = durationMs,
                    seekDragging  = seekDragging,
                    seekPreviewMs = seekPreviewMs,
                    vibrantColor  = vibrantColor,
                    onDrag        = { v ->
                        seekDragging  = true
                        seekPreviewMs = (v * durationMs).toLong()
                    },
                    onDragEnd = {
                        SenderService.getBinder()?.seekToMs(seekPreviewMs)
                        seekDragging = false
                    },
                )
            }

            when (screenState) {
                SenderScreenState.READY -> {
                    GoLiveButton(
                        label       = if (mode == SenderMode.LOCAL) "START PLAYING" else "GO LIVE",
                        accentColor = vibrantColor,
                    ) {
                        screenState = SenderScreenState.LIVE
                        // Mark this song as swapped BEFORE starting the service
                        // so the LaunchedEffect(song.id) above doesn't double-fire
                        nowPlaying.lastSwappedSongId = song.id
                        startSenderService(context, song, palHex1, palHex2, localOnly = mode == SenderMode.LOCAL)
                        scope.launch {
                            var waited = 0
                            while (waited < 3000) {
                                val b = SenderService.getBinder()
                                if (b != null) {
                                    b.setLocalRole(role.name.lowercase(), 250f, 4000f)
                                    break
                                }
                                delay(100)
                                waited += 100
                            }
                        }
                    }
                }

                SenderScreenState.LIVE -> {
                    LiveControls(
                        deviceCount  = deviceCount,
                        isPaused     = isPaused,
                        vibrantColor = vibrantColor,
                        showDevices  = mode == SenderMode.MESH,
                        onDevicesTap = { showDevicesSheet = true },
                        onQueueTap   = { showQueueSheet = true },
                        onPrev       = { nowPlaying.skipPrev(context) },
                        onPlayPause  = {
                            SenderService.getBinder()?.let { b ->
                                if (isPaused) b.resume() else b.pause()
                            }
                        },
                        onNext = { nowPlaying.skipNext(context) },
                        onStop = {
                            context.stopService(Intent(context, SenderService::class.java))
                            nowPlaying.clearNowPlaying()
                            onBack()
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showDevicesSheet) {
        val handle = SenderService.getBinder()?.nativeHandle ?: 0L
        DevicesSheet(
            nativeHandle = handle,
            vibrantColor = vibrantColor,
            onDismiss    = { showDevicesSheet = false },
        )
    }
    if (showQueueSheet) {
        QueueSheet(
            nowPlaying = nowPlaying,
            onDismiss  = { showQueueSheet = false },
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun RoleBadge(role: SenderRole, enabled: Boolean, onCycle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(role.surface.copy(alpha = 0.8f))
            .border(1.dp, role.accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .then(if (enabled) Modifier.clickable(onClick = onCycle) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(role.accent))
        Text(
            role.label,
            style         = MaterialTheme.typography.labelSmall,
            color         = role.onAccent,
            fontSize      = 11.sp,
            letterSpacing = 2.sp,
        )
        if (enabled) Text("↕", color = role.onAccent.copy(alpha = 0.5f), fontSize = 10.sp)
        else Text("· TX", color = role.onAccent.copy(alpha = 0.45f), fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun SeekSection(
    positionMs   : Long,
    durationMs   : Long,
    seekDragging : Boolean,
    seekPreviewMs: Long,
    vibrantColor : Color,
    onDrag       : (Float) -> Unit,
    onDragEnd    : () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                SongRepository.formatDuration(if (seekDragging) seekPreviewMs else positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
            )
            Text(
                SongRepository.formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = if (seekDragging)
                (seekPreviewMs.toFloat() / durationMs.coerceAtLeast(1L))
            else
                (positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
            onValueChange         = onDrag,
            onValueChangeFinished = onDragEnd,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor         = Color.White,
                activeTrackColor   = vibrantColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GoLiveButton(accentColor: Color, label: String = "GO LIVE", onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accentColor.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style      = MaterialTheme.typography.labelSmall,
            color      = Color.White,
            fontSize   = 14.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    nowPlaying: NowPlayingViewModel,
    onDismiss : () -> Unit,
) {
    val queue by nowPlaying.queue.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111),
        contentColor     = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    "UP NEXT",
                    style         = MaterialTheme.typography.labelSmall,
                    color         = Color.White.copy(alpha = 0.40f),
                    fontSize      = 10.sp,
                    letterSpacing = 3.sp,
                )
                if (queue.isNotEmpty()) {
                    Text(
                        "CLEAR ALL",
                        style         = MaterialTheme.typography.labelSmall,
                        color         = Color(0xFF555555),
                        fontSize      = 10.sp,
                        letterSpacing = 1.5.sp,
                        modifier      = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { nowPlaying.clearQueue() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (queue.isEmpty()) {
                Text(
                    "Queue is empty",
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = Color(0xFF444444),
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn {
                    itemsIndexed(queue) { index: Int, song: Song ->
                        Row(
                            modifier              = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "${index + 1}",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = Color(0xFF444444),
                                fontSize = 11.sp,
                                modifier = Modifier.width(20.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    song.title,
                                    style      = MaterialTheme.typography.bodyMedium,
                                    color      = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines   = 1,
                                    overflow   = TextOverflow.Ellipsis,
                                    fontSize   = 13.sp,
                                )
                                Text(
                                    song.artist,
                                    style    = MaterialTheme.typography.bodySmall,
                                    color    = Color(0xFF555555),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp,
                                )
                            }
                            Text(
                                "×",
                                color    = Color(0xFF444444),
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .clickable { nowPlaying.removeFromQueue(index) }
                                    .padding(4.dp),
                            )
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LiveControls(
    deviceCount  : Int,
    isPaused     : Boolean,
    vibrantColor : Color,
    showDevices  : Boolean,
    onDevicesTap : () -> Unit,
    onQueueTap   : () -> Unit,
    onPrev       : () -> Unit,
    onPlayPause  : () -> Unit,
    onNext       : () -> Unit,
    onStop       : () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .clickable(onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) { Text("⏮", color = Color.White, fontSize = 18.sp) }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(vibrantColor.copy(alpha = 0.9f))
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (isPaused) {
                    Text("▶", color = Color.White, fontSize = 24.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .clickable(onClick = onNext),
                contentAlignment = Alignment.Center,
            ) { Text("⏭", color = Color.White, fontSize = 18.sp) }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            if (showDevices) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(1.dp, vibrantColor.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .clickable(onClick = onDevicesTap)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (deviceCount > 0) vibrantColor else Color(0xFF444444))
                    )
                    Text(
                        text      = if (deviceCount == 0) "NO DEVICES"
                        else "$deviceCount DEVICE${if (deviceCount != 1) "S" else ""}",
                        style     = MaterialTheme.typography.labelSmall,
                        color     = if (deviceCount > 0) Color.White else Color(0xFF555555),
                        fontSize  = 11.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .clickable(onClick = onQueueTap),
                contentAlignment = Alignment.Center,
            ) { Text("≡", color = Color.White, fontSize = 18.sp) }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.8f))
                )
            }
        }
    }
}

// ── Start service ─────────────────────────────────────────────────────────────

private fun startSenderService(
    context  : Context,
    song     : Song,
    palHex1  : String  = "",
    palHex2  : String  = "",
    localOnly: Boolean = false,
) {
    context.startForegroundService(
        Intent(context, SenderService::class.java).apply {
            putExtra("mp3Uri",    song.uri.toString())
            putExtra("title",     song.title)
            putExtra("artist",    song.artist)
            putExtra("palHex1",   palHex1)
            putExtra("palHex2",   palHex2)
            putExtra("localOnly", localOnly)
        }
    )
}