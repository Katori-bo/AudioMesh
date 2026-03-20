package com.audiomesh.app.ui.components
import coil.compose.rememberAsyncImagePainter
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.audiomesh.app.data.Song
import com.audiomesh.app.data.SongRepository
import com.audiomesh.app.ui.theme.*

// ── Sender role model ─────────────────────────────────────────────────────────

enum class SenderRole(
    val label      : String,
    val description: String,
    val accent     : Color,
    val surface    : Color,
    val onAccent   : Color,
) {
    FULL   ("FULL",   "All frequencies · no filter", RoleFull,   RoleFullSurface,   RoleFullOn),
    BASS   ("BASS",   "Low end · sub & bass",        RoleBass,   RoleBassSurface,   RoleBassOn),
    MID    ("MID",    "Midrange · vocals & guitars",  RoleMid,    RoleMidSurface,    RoleMidOn),
    TREBLE ("TREBLE", "High end · air & detail",      RoleTreble, RoleTrebleSurface, RoleTrebleOn),
}

// ── Sheet tab ─────────────────────────────────────────────────────────────────

private enum class SheetTab { SENDER, RECEIVER }

@Composable
fun PlaybackSheetContent(
    song              : Song,
    onSendToMesh      : (SenderRole) -> Unit,
    onPlayLocally     : () -> Unit,
    onJoinAsMesh      : (role: String, senderIp: String) -> Unit = { _, _ -> },
    onDismiss         : () -> Unit,
    activeReceiverIp  : String = "",
    startOnReceiverTab: Boolean = false,
) {
    var activeTab by remember { mutableStateOf(if (startOnReceiverTab) SheetTab.RECEIVER else SheetTab.SENDER) }
    var selectedRole by remember { mutableStateOf<SenderRole?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF141414),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            )
            .padding(bottom = 32.dp)
    ) {

        // ── Drag handle ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF2A2A2A))
            )
        }

        // ── Tab bar ───────────────────────────────────────────────────────────
        SheetTabBar(
            activeTab = activeTab,
            onTabSelect = { activeTab = it },
        )

        Spacer(Modifier.height(16.dp))

        // ── Track info row (shared — always visible) ──────────────────────────
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SongArtwork(song = song, size = 52)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = song.title,
                    style      = MaterialTheme.typography.bodyLarge,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = "${song.artist} · ${SongRepository.formatDuration(song.durationMs)}",
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = Color(0xFF666666),
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Tab content ───────────────────────────────────────────────────────
        AnimatedContent(
            targetState   = activeTab,
            transitionSpec = {
                val dir = if (targetState == SheetTab.RECEIVER) 1 else -1
                slideInHorizontally(tween(220)) { it * dir } + fadeIn(tween(220)) togetherWith
                        slideOutHorizontally(tween(220)) { it * -dir } + fadeOut(tween(220))
            },
            label = "sheetTab",
        ) { tab ->
            when (tab) {
                SheetTab.SENDER -> SenderTabContent(
                    selectedRole = selectedRole,
                    onRoleSelect = { selectedRole = it },
                    onSendToMesh = onSendToMesh,
                    onPlayLocally = onPlayLocally,
                )
                SheetTab.RECEIVER -> ReceiverTabContent(
                    onJoinAsMesh     = onJoinAsMesh,
                    activeReceiverIp = activeReceiverIp,
                )
            }
        }
    }
}

// ── Tab bar ───────────────────────────────────────────────────────────────────

@Composable
private fun SheetTabBar(
    activeTab   : SheetTab,
    onTabSelect : (SheetTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A)),
    ) {
        SheetTab.entries.forEach { tab ->
            val isActive = tab == activeTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) Color(0xFF2A2A2A) else Color.Transparent
                    )
                    .clickable { onTabSelect(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Tab indicator dot
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !isActive          -> Color(0xFF333333)
                                    tab == SheetTab.SENDER   -> RoleFull
                                    else               -> Color(0xFF00C896)
                                }
                            )
                    )
                    Text(
                        text     = when (tab) {
                            SheetTab.SENDER   -> "SENDER"
                            SheetTab.RECEIVER -> "RECEIVER"
                        },
                        style         = MaterialTheme.typography.labelSmall,
                        color         = if (isActive) Color.White else Color(0xFF444444),
                        fontSize      = 10.sp,
                        letterSpacing = 2.sp,
                        fontWeight    = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Sender tab (existing content, untouched) ──────────────────────────────────

@Composable
private fun SenderTabContent(
    selectedRole : SenderRole?,
    onRoleSelect : (SenderRole) -> Unit,
    onSendToMesh : (SenderRole) -> Unit,
    onPlayLocally: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        Text(
            text     = "SEND TO MESH AS",
            style    = MaterialTheme.typography.labelSmall,
            color    = Color(0xFF555555),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(10.dp))

        Column(
            modifier            = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoleCard(
                role       = SenderRole.FULL,
                isSelected = selectedRole == SenderRole.FULL,
                onClick    = { onRoleSelect(SenderRole.FULL) },
                modifier   = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SenderRole.BASS, SenderRole.MID, SenderRole.TREBLE).forEach { role ->
                    RoleCard(
                        role       = role,
                        isSelected = selectedRole == role,
                        onClick    = { onRoleSelect(role) },
                        modifier   = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        val canSend = selectedRole != null
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (canSend) RoleFull.copy(alpha = 0.9f) else Color(0xFF1A1A1A)
                )
                .then(
                    if (!canSend) Modifier.border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
                    else Modifier
                )
                .clickable(enabled = canSend) { selectedRole?.let { onSendToMesh(it) } }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text          = if (canSend) "SEND TO MESH" else "PICK A ROLE FIRST",
                style         = MaterialTheme.typography.labelSmall,
                color         = if (canSend) Color.White else Color(0xFF444444),
                fontSize      = 12.sp,
                letterSpacing = 2.sp,
                fontWeight    = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161616))
                .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
                .clickable { onPlayLocally() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text          = "PLAY LOCALLY",
                style         = MaterialTheme.typography.labelSmall,
                color         = Color(0xFF777777),
                fontSize      = 12.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

// ── Receiver tab ──────────────────────────────────────────────────────────────

@Composable
private fun ReceiverTabContent(
    onJoinAsMesh    : (role: String, senderIp: String) -> Unit,
    activeReceiverIp: String,
) {
    // Role selection — mirrors sender style but with green accent for receiver
    var selectedRole by remember { mutableStateOf("FULL") }
    var manualIp     by remember { mutableStateOf("") }
    var ipFieldFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    val isAlreadyConnected = activeReceiverIp.isNotBlank()

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Already connected banner ──────────────────────────────────────────
        if (isAlreadyConnected) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0A1F14))
                    .border(1.dp, Color(0xFF1A4A2A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00C896))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CONNECTED TO MESH",
                        style         = MaterialTheme.typography.labelSmall,
                        color         = Color(0xFF00C896),
                        fontSize      = 9.sp,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        activeReceiverIp,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = Color.White.copy(alpha = 0.60f),
                        fontSize = 12.sp,
                    )
                }
                // Tap to go back to cassette screen
                Text(
                    "VIEW →",
                    style         = MaterialTheme.typography.labelSmall,
                    color         = Color(0xFF00C896),
                    fontSize      = 9.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight    = FontWeight.Bold,
                    modifier      = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onJoinAsMesh(selectedRole, activeReceiverIp) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Role picker label ─────────────────────────────────────────────────
        Text(
            text     = "JOIN MESH AS",
            style    = MaterialTheme.typography.labelSmall,
            color    = Color(0xFF555555),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(10.dp))

        // ── Role grid (4 equal pills) ─────────────────────────────────────────
        Row(
            modifier            = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("FULL", "BASS", "MID", "TREBLE").forEach { role ->
                val roleEnum = SenderRole.valueOf(role)
                val isSelected = selectedRole == role
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) roleEnum.surface else Color(0xFF161616)
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) roleEnum.accent.copy(alpha = 0.7f)
                            else Color(0xFF222222),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { selectedRole = role }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) roleEnum.accent else Color(0xFF333333)
                                )
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text          = role,
                            style         = MaterialTheme.typography.labelSmall,
                            color         = if (isSelected) roleEnum.onAccent else Color(0xFF555555),
                            fontSize      = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Manual IP entry ───────────────────────────────────────────────────
        Text(
            text     = "SENDER IP  (leave blank to auto-discover)",
            style    = MaterialTheme.typography.labelSmall,
            color    = Color(0xFF444444),
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(6.dp))

        TextField(
            value         = manualIp,
            onValueChange = { manualIp = it.filter { c -> c.isDigit() || c == '.' } },
            placeholder   = {
                Text(
                    "192.168.x.x",
                    color    = Color(0xFF333333),
                    fontSize = 13.sp,
                )
            },
            singleLine    = true,
            modifier      = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .onFocusChanged { ipFieldFocused = it.isFocused },
            colors        = TextFieldDefaults.colors(
                focusedContainerColor   = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF181818),
                focusedTextColor        = Color.White,
                unfocusedTextColor      = Color.White.copy(alpha = 0.70f),
                cursorColor             = RoleFull,
                focusedIndicatorColor   = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedLabelColor       = Color.Transparent,
            ),
            shape         = RoundedCornerShape(12.dp),
            textStyle     = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction    = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { keyboard?.hide() }
            ),
        )

        Spacer(Modifier.height(16.dp))

        // ── Join button ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D2B1F))
                .border(1.dp, Color(0xFF1A5C38), RoundedCornerShape(16.dp))
                .clickable {
                    keyboard?.hide()
                    onJoinAsMesh(selectedRole, manualIp.trim())
                }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text          = if (manualIp.isBlank()) "JOIN MESH  ·  AUTO-DISCOVER" else "JOIN  $manualIp",
                style         = MaterialTheme.typography.labelSmall,
                color         = Color(0xFF00C896),
                fontSize      = 12.sp,
                letterSpacing = 2.sp,
                fontWeight    = FontWeight.Bold,
            )
        }
    }
}

// ── Role card (sender tab — unchanged) ───────────────────────────────────────

@Composable
private fun RoleCard(
    role      : SenderRole,
    isSelected: Boolean,
    onClick   : () -> Unit,
    modifier  : Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) role.surface else Color(0xFF161616))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) role.accent.copy(alpha = 0.7f) else Color(0xFF222222),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) role.accent else Color(0xFF333333))
                )
                Text(
                    text          = role.label,
                    style         = MaterialTheme.typography.labelSmall,
                    color         = if (isSelected) role.onAccent else Color(0xFF555555),
                    fontSize      = 11.sp,
                    letterSpacing = 2.sp,
                )
            }
            if (role == SenderRole.FULL || isSelected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = role.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) Color.White.copy(alpha = 0.7f) else Color(0xFF444444),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// ── Shared artwork composable ─────────────────────────────────────────────────

@Composable
fun SongArtwork(
    song    : Song,
    size    : Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape((size / 5).dp)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(Color(SongRepository.fallbackColor(song.title))),
        contentAlignment = Alignment.Center,
    ) {
        val artSource = song.remoteArtUrl ?: song.albumArtUri
        if (artSource != null) {
            AsyncImage(
                model              = artSource,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
                error              = rememberAsyncImagePainter(song.albumArtUri),
            )
        } else {
            Text(
                text       = song.title.first().uppercaseChar().toString(),
                color      = Color.White.copy(alpha = 0.6f),
                fontSize   = (size / 2.5f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}