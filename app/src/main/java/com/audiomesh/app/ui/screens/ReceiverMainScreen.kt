package com.audiomesh.app.ui.screens
import androidx.compose.foundation.layout.Box
import com.audiomesh.app.R
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import android.content.Intent
import com.audiomesh.app.data.SongRepository
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
//  ReceiverMainScreen
//
//  Two exit paths, clearly separated:
//
//  onBack  — back button (‹). Pops nav stack. Service keeps running.
//            Audio continues. MiniPlayerBar appears in Library.
//            Does NOT call clearReceiver() — AppNavigation handles that
//            contract by leaving isReceiverActive = true on this path.
//
//  onLeave — LEAVE MESH button. Called AFTER binder.stopAndLeave() has
//            been called inside ReceiverControlsSheet. AppNavigation calls
//            clearReceiver() when this lambda fires.
//
//  Role change — uses binder.switchSender("") which triggers rehandshakeInPlace_
//                in ReceiverEngine. No service stop/restart. ~1–2 s of silence
//                during re-handshake, then audio resumes seamlessly.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ReceiverMainScreen(
    onBack       : () -> Unit = {},
    onLeave      : () -> Unit = {},   // called only from LEAVE MESH
    senderIp     : String     = "",
    role         : String     = "FULL",
    dominantColor: Color      = Color(0xFF1A0A3A),
    vibrantColor : Color      = Color(0xFF7C3AED),
) {
    val context       = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape   = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Start the service only if it isn't already running.
    // Keyed on senderIp so navigating back and returning doesn't restart it.
    LaunchedEffect(senderIp) {
        val alreadyRunning = com.audiomesh.app.ReceiverService.getBinder() != null
        if (!alreadyRunning) {
            val intent = Intent(context, com.audiomesh.app.ReceiverService::class.java).apply {
                putExtra("role", role)
                putExtra("latencyNs", 0L)
            }
            context.startForegroundService(intent)
        }
    }

    var trackTitle       by remember { mutableStateOf("Connecting…") }
    var trackArtist      by remember { mutableStateOf(senderIp) }
    var isPlaying        by remember { mutableStateOf(false) }
    var receiverRole     by remember { mutableStateOf(role.uppercase()) }
    var connectionStatus by remember { mutableStateOf("") }
    var emaDriftMs       by remember { mutableLongStateOf(0L) }
    var dominantColorState by remember { mutableStateOf(dominantColor) }
    var vibrantColorState  by remember { mutableStateOf(vibrantColor) }
    var positionMs       by remember { mutableLongStateOf(0L) }
    var durationMs       by remember { mutableLongStateOf(0L) }

    fun parseHexColor(hex: String, fallback: Color): Color {
        return try { Color(android.graphics.Color.parseColor(hex)) }
        catch (_: Exception) { fallback }
    }

    var binder by remember { mutableStateOf<com.audiomesh.app.ReceiverService.LocalBinder?>(null) }

    // Bind to service for binder API access
    DisposableEffect(Unit) {
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(
                name: android.content.ComponentName,
                service: android.os.IBinder,
            ) {
                binder = service as? com.audiomesh.app.ReceiverService.LocalBinder
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {
                binder = null
            }
        }
        val bindIntent = Intent(context, com.audiomesh.app.ReceiverService::class.java)
        context.bindService(bindIntent, conn, 0)
        onDispose { context.unbindService(conn) }
    }

    // Poll binder for live state every 500 ms
    LaunchedEffect(Unit) {
        while (true) {
            binder?.let { b ->
                val status = b.connectionStatus ?: ""
                connectionStatus = status
                isPlaying = status.startsWith("HANDSHAKE_OK", ignoreCase = true)
                        || status.startsWith("PLAYING", ignoreCase = true)
                val r = b.assignedRole ?: "full"
                receiverRole = r.uppercase()
                emaDriftMs   = b.emaDriftMs
                positionMs   = b.positionMs
                durationMs   = b.durationMs

                val title = b.trackTitle?.takeIf { it.isNotBlank() }
                if (title != null) {
                    trackTitle  = title
                    trackArtist = b.trackArtist?.takeIf { it.isNotBlank() } ?: ""
                } else if (isPlaying && trackTitle == "Connecting…") {
                    trackTitle  = "AudioMesh"
                    trackArtist = b.senderIP?.takeIf { it.isNotBlank() } ?: senderIp
                }

                val hex1 = b.paletteHex1?.takeIf { it.isNotBlank() }
                val hex2 = b.paletteHex2?.takeIf { it.isNotBlank() }
                if (hex1 != null) dominantColorState = parseHexColor(hex1, dominantColorState)
                if (hex2 != null) vibrantColorState  = parseHexColor(hex2, vibrantColorState)
            }
            kotlinx.coroutines.delay(500)
        }
    }

    var showControlsSheet by remember { mutableStateOf(false) }
    val liveDriftMs = emaDriftMs

    Box(modifier = Modifier.fillMaxSize()) {

        Image(
            painter            = painterResource(R.drawable.cassetteimage),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize().blur(40.dp),
            colorFilter        = ColorFilter.tint(
                vibrantColorState.copy(alpha = 0.55f),
                BlendMode.Hardlight,
            ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.55f),
                            0.35f to dominantColorState.copy(alpha = 0.45f),
                            1.00f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
                )
        )

        if (isLandscape) {
            LandscapeContent(
                trackTitle    = trackTitle,
                trackArtist   = trackArtist,
                isPlaying     = isPlaying,
                receiverRole  = receiverRole,
                dominantColor = dominantColorState,
                vibrantColor  = vibrantColorState,
                positionMs    = positionMs,
                durationMs    = durationMs,
                onBack        = onBack,
                onControls    = { showControlsSheet = true },
            )
        } else {
            PortraitContent(
                trackTitle    = trackTitle,
                trackArtist   = trackArtist,
                isPlaying     = isPlaying,
                receiverRole  = receiverRole,
                dominantColor = dominantColorState,
                vibrantColor  = vibrantColorState,
                positionMs    = positionMs,
                durationMs    = durationMs,
                onBack        = onBack,
                onControls    = { showControlsSheet = true },
            )
        }
    }

    if (showControlsSheet) {
        ReceiverControlsSheet(
            vibrantColor = vibrantColorState,
            binder       = binder,
            emaDriftMs   = liveDriftMs,
            senderIp     = trackArtist,
            assignedRole = receiverRole,
            onDismiss    = { showControlsSheet = false },
            onLeave      = {
                // ── LEAVE MESH ────────────────────────────────────────────────
                // 1. Tell the engine to stop cleanly via the binder
                // 2. Invoke onLeave() which tells AppNavigation to call
                //    clearReceiver() and pop the nav stack
                // We do NOT call context.stopService() — the engine stops itself
                // via stopAndLeave() → stopSelf() in ReceiverService.
                binder?.stopAndLeave()
                    ?: context.stopService(
                        Intent(context, com.audiomesh.app.ReceiverService::class.java)
                    )
                showControlsSheet = false
                onLeave()
            },
            onRoleChange = { newRole ->
                // ── Role change: seamless via switchSender ────────────────────
                // binder.switchSender("") triggers rehandshakeInPlace_ in the C++
                // engine — it keeps the TCP connection and AAudio stream alive,
                // just re-sends ROLE: on the existing socket. ~1-2s of silence,
                // then audio resumes with the new filter applied.
                //
                // We also restart the service with the new role so that if the
                // service is killed and restarted by the system, it comes back
                // with the correct role. The service ignores duplicate starts
                // when nativeHandle is non-zero (engine already running).
                receiverRole = newRole
                binder?.switchSender("")
                // Update the service intent role for crash-restart resilience
                val intent = Intent(context, com.audiomesh.app.ReceiverService::class.java).apply {
                    putExtra("role", newRole.lowercase())
                    putExtra("latencyNs", 0L)
                }
                context.startForegroundService(intent)
            },
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Portrait layout
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PortraitContent(
    trackTitle    : String,
    trackArtist   : String,
    isPlaying     : Boolean,
    receiverRole  : String,
    dominantColor : Color,
    vibrantColor  : Color,
    positionMs    : Long,
    durationMs    : Long,
    onBack        : () -> Unit,
    onControls    : () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                SyncStatusPill(vibrantColor = vibrantColor, isPlaying = isPlaying)
                ReceiverRoleBadge(
                    role         = receiverRole,
                    vibrantColor = vibrantColor,
                    onClick      = onControls,
                )
            }

            Spacer(Modifier.weight(0.3f))

            AnimatedCassette(
                isPlaying    = isPlaying,
                vibrantColor = vibrantColor,
                trackTitle   = trackTitle,
                modifier     = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.55f),
            )

            Spacer(Modifier.weight(0.4f))

            TrackInfo(
                trackTitle  = trackTitle,
                trackArtist = trackArtist,
                vibrantColor = vibrantColor,
            )

            Spacer(Modifier.weight(0.3f))

            if (isPlaying) {
                ReceiverProgressPill(
                    positionMs   = positionMs,
                    durationMs   = durationMs,
                    vibrantColor = vibrantColor,
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.weight(0.3f))
            Spacer(Modifier.height(80.dp))
        }

        PullUpHandle(
            vibrantColor = vibrantColor,
            isPlaying    = isPlaying,
            modifier     = Modifier.align(Alignment.BottomCenter),
            onPull       = onControls,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Landscape layout
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LandscapeContent(
    trackTitle    : String,
    trackArtist   : String,
    isPlaying     : Boolean,
    receiverRole  : String,
    dominantColor : Color,
    vibrantColor  : Color,
    positionMs    : Long,
    durationMs    : Long,
    onBack        : () -> Unit,
    onControls    : () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedCassette(
            isPlaying    = isPlaying,
            vibrantColor = vibrantColor,
            trackTitle   = trackTitle,
            modifier     = Modifier
                .fillMaxHeight()
                .aspectRatio(1.55f),
        )

        Spacer(Modifier.width(24.dp))

        Column(
            modifier            = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                ReceiverRoleBadge(
                    role         = receiverRole,
                    vibrantColor = vibrantColor,
                    onClick      = onControls,
                )
            }

            TrackInfo(
                trackTitle   = trackTitle,
                trackArtist  = trackArtist,
                vibrantColor = vibrantColor,
            )

            if (isPlaying) {
                ReceiverProgressPill(
                    positionMs   = positionMs,
                    durationMs   = durationMs,
                    vibrantColor = vibrantColor,
                )
            }

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                SyncStatusPill(vibrantColor = vibrantColor, isPlaying = isPlaying)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.30f))
                        .clickable(onClick = onControls)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        "SYNC CONTROLS",
                        style         = MaterialTheme.typography.labelSmall,
                        color         = Color.White.copy(alpha = 0.40f),
                        fontSize      = 9.sp,
                        letterSpacing = 2.sp,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun TrackInfo(trackTitle: String, trackArtist: String, vibrantColor: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text       = trackTitle,
            style      = MaterialTheme.typography.headlineLarge,
            color      = Color.White,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center,
            modifier   = Modifier.fillMaxWidth(),
        )
        if (trackArtist.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text      = trackArtist,
                style     = MaterialTheme.typography.headlineMedium,
                color     = Color.White.copy(alpha = 0.55f),
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Progress pill (replaces ReceiverMiniPlayer — cleaner name)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReceiverProgressPill(
    positionMs  : Long,
    durationMs  : Long,
    vibrantColor: Color,
) {
    val progress = if (durationMs > 0)
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, vibrantColor.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text     = SongRepository.formatDuration(positionMs),
                style    = MaterialTheme.typography.labelSmall,
                color    = Color.White.copy(alpha = 0.50f),
                fontSize = 10.sp,
            )
            Text(
                text     = "/",
                style    = MaterialTheme.typography.labelSmall,
                color    = Color.White.copy(alpha = 0.25f),
                fontSize = 10.sp,
            )
            Text(
                text     = SongRepository.formatDuration(durationMs),
                style    = MaterialTheme.typography.labelSmall,
                color    = Color.White.copy(alpha = 0.35f),
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(vibrantColor.copy(alpha = 0.7f), vibrantColor)
                        )
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AnimatedCassette
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AnimatedCassette(
    isPlaying    : Boolean,
    vibrantColor : Color,
    trackTitle   : String,
    modifier     : Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reels")
    val reelAngle by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "reelAngle",
    )

    var frozenAngle by remember { mutableFloatStateOf(0f) }
    val displayAngle = if (isPlaying) reelAngle else frozenAngle
    LaunchedEffect(isPlaying) {
        if (!isPlaying) frozenAngle = reelAngle
    }

    val tintColor = vibrantColor.copy(alpha = 0.72f)

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {

        Image(
            painter            = painterResource(R.drawable.cassetteimage),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
            colorFilter        = ColorFilter.tint(tintColor, BlendMode.Hardlight),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        radius = 800f,
                    )
                )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val W = size.width
            val H = size.height
            val leftCx    = W * 0.281f
            val rightCx   = W * 0.729f
            val reelCy    = H * 0.47f
            val hubOuterR = W * 0.089f
            val spindleR  = W * 0.035f
            for (cx in listOf(leftCx, rightCx)) {
                drawSpinningHub(cx, reelCy, hubOuterR, spindleR, 6, displayAngle, vibrantColor)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = Dp(0f))
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.fillMaxHeight(0.67f))
            if (trackTitle.isNotBlank() && trackTitle != "Connecting…") {
                Text(
                    text       = trackTitle,
                    color      = Color.White,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.fillMaxWidth(),
                    style      = MaterialTheme.typography.labelSmall.copy(
                        shadow = Shadow(
                            color      = Color.Black.copy(alpha = 0.8f),
                            offset     = Offset(0f, 1f),
                            blurRadius = 4f,
                        )
                    ),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun DrawScope.drawSpinningHub(
    cx         : Float,
    cy         : Float,
    hubOuterR  : Float,
    spindleR   : Float,
    spokeCount : Int,
    angleDeg   : Float,
    hubColor   : Color,
) {
    drawCircle(
        color  = Color.Black.copy(alpha = 0.55f),
        radius = hubOuterR * 1.02f,
        center = Offset(cx, cy),
    )
    val angleStep = 360f / spokeCount
    for (i in 0 until spokeCount) {
        val rad = Math.toRadians((angleDeg + angleStep * i).toDouble()).toFloat()
        drawLine(
            color       = hubColor.copy(alpha = 0.75f),
            start       = Offset(cx + cos(rad) * spindleR * 1.1f, cy + sin(rad) * spindleR * 1.1f),
            end         = Offset(cx + cos(rad) * hubOuterR * 0.85f, cy + sin(rad) * hubOuterR * 0.85f),
            strokeWidth = hubOuterR * 0.12f,
            cap         = StrokeCap.Round,
        )
    }
    drawCircle(color = hubColor.copy(alpha = 0.30f), radius = hubOuterR,        center = Offset(cx, cy), style = Stroke(width = hubOuterR * 0.15f))
    drawCircle(color = hubColor.copy(alpha = 0.20f), radius = hubOuterR * 0.55f, center = Offset(cx, cy), style = Stroke(width = hubOuterR * 0.08f))
    drawCircle(color = Color(0xFF1A1A1A),            radius = spindleR,          center = Offset(cx, cy))
    drawCircle(color = Color.White.copy(alpha = 0.25f), radius = spindleR,       center = Offset(cx, cy), style = Stroke(width = 1.5f))
    val notchRad = Math.toRadians((angleDeg + 90.0)).toFloat()
    drawCircle(
        color  = hubColor.copy(alpha = 0.70f),
        radius = spindleR * 0.30f,
        center = Offset(cx + cos(notchRad) * spindleR * 0.65f, cy + sin(notchRad) * spindleR * 0.65f),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Role badge, sync pill, pull handle
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReceiverRoleBadge(
    role        : String,
    vibrantColor: Color,
    onClick     : () -> Unit = {},
) {
    val (accent, surface, onAccent, label) = when (role.uppercase()) {
        "BASS"   -> Quadruple(Color(0xFFC2410C), Color(0xFF431407), Color(0xFFFDBA74), "BASS")
        "MID"    -> Quadruple(Color(0xFF166534), Color(0xFF052E16), Color(0xFF86EFAC), "MID")
        "TREBLE" -> Quadruple(Color(0xFF1D4ED8), Color(0xFF1E3A5F), Color(0xFF93C5FD), "TREBLE")
        else     -> Quadruple(Color(0xFF7C3AED), Color(0xFF3B0764), Color(0xFFC4B5FD), "FULL")
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(surface.copy(alpha = 0.80f))
            .border(1.dp, accent.copy(alpha = 0.50f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        Text(
            text          = label,
            style         = MaterialTheme.typography.labelSmall,
            color         = onAccent,
            fontSize      = 11.sp,
            letterSpacing = 2.sp,
        )
        Text(
            text     = "· RX",
            color    = onAccent.copy(alpha = 0.45f),
            fontSize = 9.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun SyncStatusPill(vibrantColor: Color, isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val dotColor = if (isPlaying) vibrantColor else Color(0xFF444444)
    val dotAlpha = if (isPlaying) 1f else pulseAlpha
    val label    = if (isPlaying) "SYNCED" else "CONNECTING"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.40f))
            .border(1.dp, dotColor.copy(alpha = 0.30f), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor.copy(alpha = dotAlpha)))
        Text(
            text          = label,
            style         = MaterialTheme.typography.labelSmall,
            color         = if (isPlaying) Color.White else Color(0xFF666666),
            fontSize      = 11.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

@Composable
private fun PullUpHandle(
    vibrantColor: Color,
    isPlaying   : Boolean,
    onPull      : () -> Unit,
    modifier    : Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.50f))
                .border(1.dp, vibrantColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clickable(onClick = onPull)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text          = "⚙  SYNC CONTROLS",
                style         = MaterialTheme.typography.labelSmall,
                color         = Color.White.copy(alpha = 0.70f),
                fontSize      = 11.sp,
                letterSpacing = 2.sp,
            )
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(vibrantColor.copy(alpha = 0.50f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Controls sheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiverControlsSheet(
    vibrantColor : Color,
    binder       : com.audiomesh.app.ReceiverService.LocalBinder?,
    emaDriftMs   : Long,
    senderIp     : String,
    assignedRole : String,
    onDismiss    : () -> Unit,
    onLeave      : () -> Unit,
    onRoleChange : (String) -> Unit,
) {
    var latencyTrimMs    by remember { mutableFloatStateOf(0f) }
    val connectionStatus = binder?.connectionStatus ?: "—"
    val liveSenderIp     = binder?.senderIP?.takeIf { it.isNotBlank() } ?: senderIp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111),
        contentColor     = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(vibrantColor.copy(alpha = 0.50f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "SYNC CONTROLS",
                style         = MaterialTheme.typography.labelSmall,
                color         = Color.White.copy(alpha = 0.40f),
                fontSize      = 10.sp,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(20.dp))

            SheetRow(label = "Sender") {
                Text(
                    liveSenderIp,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.70f),
                )
            }
            SheetRow(label = "Status") {
                Text(
                    connectionStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = vibrantColor.copy(alpha = 0.85f),
                )
            }
            SheetRow(label = "Role") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("FULL", "BASS", "MID", "TREBLE").forEach { r ->
                        val selected = assignedRole.uppercase() == r
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) vibrantColor.copy(alpha = 0.85f)
                                    else Color.White.copy(alpha = 0.08f)
                                )
                                .border(
                                    1.dp,
                                    if (selected) vibrantColor else Color.White.copy(alpha = 0.15f),
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onRoleChange(r) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                r,
                                fontSize      = 10.sp,
                                color         = if (selected) Color.White else Color.White.copy(alpha = 0.50f),
                                letterSpacing = 1.sp,
                                fontWeight    = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            SheetRow(label = "EMA drift") {
                Text(
                    "$emaDriftMs ms",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = if (abs(emaDriftMs) < 5) Color(0xFF86EFAC) else Color(0xFFFDBA74),
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    "Latency trim",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    "${latencyTrimMs.toInt()} ms",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
            Slider(
                value                 = latencyTrimMs,
                onValueChange         = { latencyTrimMs = it },
                onValueChangeFinished = { binder?.setLatencyMs(latencyTrimMs.toInt()) },
                valueRange            = -200f..200f,
                modifier              = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor         = Color.White,
                    activeTrackColor   = vibrantColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                ),
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SheetButton(
                    label     = "SWITCH SENDER",
                    color     = Color.White.copy(alpha = 0.12f),
                    textColor = Color.White.copy(alpha = 0.70f),
                    modifier  = Modifier.weight(1f),
                    onClick   = { binder?.switchSender("") },
                )
                SheetButton(
                    label     = "LEAVE MESH",
                    color     = Color(0xFF3A0A0A),
                    textColor = Color(0xFFFF6B6B),
                    modifier  = Modifier.weight(1f),
                    onClick   = onLeave,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SheetRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.40f))
        content()
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
}

@Composable
private fun SheetButton(
    label    : String,
    color    : Color,
    modifier : Modifier = Modifier,
    textColor: Color    = Color.White,
    onClick  : () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style         = MaterialTheme.typography.labelSmall,
            color         = textColor,
            fontSize      = 11.sp,
            letterSpacing = 2.sp,
            fontWeight    = FontWeight.Bold,
        )
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D,
)