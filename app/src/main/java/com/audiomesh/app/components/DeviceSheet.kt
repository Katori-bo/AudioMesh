package com.audiomesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiomesh.app.NativeEngine
import com.audiomesh.app.SenderService
import com.audiomesh.app.ui.theme.*

// ── Data model ────────────────────────────────────────────────────────────────

data class ConnectedDevice(
    val addr: String,
    val role: String,   // "full", "bass", "mid", "treble"
    val gain: Float,
)

// Parse "addr|role|gain\naddr|role|gain\n..." from binder.clientStats
fun parseClientStats(raw: String): List<ConnectedDevice> {
    if (raw.isBlank()) return emptyList()
    return raw.trim().split("\n")
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 3) {
                ConnectedDevice(
                    addr = parts[0].trim(),
                    role = parts[1].trim().lowercase(),
                    gain = parts[2].trim().toFloatOrNull() ?: 1f,
                )
            } else null
        }
}

// ── Devices bottom sheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    nativeHandle: Long,         // SenderEngine handle for role/gain calls
    vibrantColor: Color,
    onDismiss   : () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Poll clientStats every second while sheet is open
    var devices by remember { mutableStateOf<List<ConnectedDevice>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            val raw = SenderService.getBinder()?.clientStats ?: ""
            devices = parseClientStats(raw)
            kotlinx.coroutines.delay(1000)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Color(0xFF141414),
        dragHandle       = null,
        shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // ── Handle ────────────────────────────────────────────────────────
            Box(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF2A2A2A))
                )
            }

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (devices.isNotEmpty()) vibrantColor else Color(0xFF444444))
                )
                Text(
                    text          = if (devices.isEmpty()) "NO DEVICES CONNECTED"
                    else "${devices.size} DEVICE${if (devices.size != 1) "S" else ""} CONNECTED",
                    style         = MaterialTheme.typography.labelSmall,
                    color         = if (devices.isNotEmpty()) Color.White else Color(0xFF555555),
                    fontSize      = 11.sp,
                    letterSpacing = 2.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            if (devices.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Waiting for receivers to connect...",
                            color    = Color(0xFF444444),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Open AudioMesh on another device",
                            color    = Color(0xFF333333),
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding        = PaddingValues(horizontal = 12.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                ) {
                    items(devices, key = { it.addr }) { device ->
                        DeviceCard(
                            device       = device,
                            nativeHandle = nativeHandle,
                            vibrantColor = vibrantColor,
                            onRoleChanged = { addr, newRole ->
                                // Optimistic update — binder call is async
                                devices = devices.map {
                                    if (it.addr == addr) it.copy(role = newRole) else it
                                }
                            },
                            onGainChanged = { addr, newGain ->
                                devices = devices.map {
                                    if (it.addr == addr) it.copy(gain = newGain) else it
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Device card ───────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    device       : ConnectedDevice,
    nativeHandle : Long,
    vibrantColor : Color,
    onRoleChanged: (String, String) -> Unit,
    onGainChanged: (String, Float) -> Unit,) {
    // Local gain state — slider value
    var gain    by remember(device.addr) { mutableFloatStateOf(device.gain) }
    var muted   by remember(device.addr) { mutableStateOf(false) }
    val gainBeforeMute = remember(device.addr) { mutableFloatStateOf(device.gain) }

    val roleColor = roleColorFor(device.role)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color(0xFF252525), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        // ── Top row: IP + role badge ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text      = device.addr,
                    color     = Color.White,
                    fontSize  = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                // Ping placeholder — shows "--" until backend exposes it
                // TODO: wire to NativeEngine.senderGetClientPingMs() when added
                Text(
                    text    = "-- ms",
                    color   = Color(0xFF444444),
                    fontSize = 11.sp,
                )
            }

            // Role badge — tappable to cycle
            RoleBadgeCycleable(
                role         = device.role,
                nativeHandle = nativeHandle,
                addr         = device.addr,
                onRoleChanged = onRoleChanged,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── Gain row: mute button + slider ───────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Mute toggle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (muted) Color(0xFF2A1A1A)
                        else Color(0xFF1F1F1F)
                    )
                    .border(
                        1.dp,
                        if (muted) Color(0xFF663333) else Color(0xFF2A2A2A),
                        CircleShape,
                    )
                    .clickable {
                        if (muted) {
                            // Unmute — restore previous gain
                            gain  = gainBeforeMute.floatValue
                            muted = false
                            NativeEngine.senderSetClientGain(nativeHandle, device.addr, gain)
                        } else {
                            // Mute — save current gain, set to 0
                            gainBeforeMute.floatValue = gain
                            gain  = 0f
                            muted = true
                            NativeEngine.senderSetClientGain(nativeHandle, device.addr, 0f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text     = if (muted) "✕" else "♪",
                    color    = if (muted) Color(0xFF884444) else Color(0xFF888888),
                    fontSize = 12.sp,
                )
            }

            // Gain slider 0.0 → 2.0
            Slider(
                value    = gain,
                onValueChange = { v ->
                    gain  = v
                    muted = v == 0f
                    NativeEngine.senderSetClientGain(nativeHandle, device.addr, v)
                },
                valueRange = 0f..2f,
                modifier   = Modifier.weight(1f),
                colors     = SliderDefaults.colors(
                    thumbColor         = Color.White,
                    activeTrackColor   = vibrantColor,
                    inactiveTrackColor = Color(0xFF2A2A2A),
                ),
            )

            // Gain label
            Text(
                text     = "${(gain * 100).toInt()}%",
                color    = Color(0xFF666666),
                fontSize = 11.sp,
                modifier = Modifier.width(36.dp),
            )
        }
    }
}

// ── Role badge — tappable to cycle ───────────────────────────────────────────

@Composable
private fun RoleBadgeCycleable(
    role        : String,
    nativeHandle: Long,
    addr        : String,
    onRoleChanged: (String, String) -> Unit,
) {
    val roles    = listOf("full", "bass", "mid", "treble")
    val current  = roles.indexOf(role).coerceAtLeast(0)

    val accent  = roleAccentFor(role)
    val surface = roleSurfaceFor(role)
    val onColor = roleOnColorFor(role)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(surface)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable {
                val next = roles[(current + 1) % roles.size]
                NativeEngine.senderSetClientRole(nativeHandle, addr, next)
                onRoleChanged(addr, next)
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Text(
            text          = role.uppercase(),
            color         = onColor,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Text("↕", color = onColor.copy(alpha = 0.4f), fontSize = 9.sp)
    }
}

// ── Role colour helpers ───────────────────────────────────────────────────────

private fun roleColorFor(role: String): Color = when (role) {
    "bass"   -> RoleBass
    "mid"    -> RoleMid
    "treble" -> RoleTreble
    else     -> RoleFull
}

private fun roleAccentFor(role: String): Color = when (role) {
    "bass"   -> RoleBass
    "mid"    -> RoleMid
    "treble" -> RoleTreble
    else     -> RoleFull
}

private fun roleSurfaceFor(role: String): Color = when (role) {
    "bass"   -> RoleBassSurface
    "mid"    -> RoleMidSurface
    "treble" -> RoleTrebleSurface
    else     -> RoleFullSurface
}

private fun roleOnColorFor(role: String): Color = when (role) {
    "bass"   -> RoleBassOn
    "mid"    -> RoleMidOn
    "treble" -> RoleTrebleOn
    else     -> RoleFullOn
}