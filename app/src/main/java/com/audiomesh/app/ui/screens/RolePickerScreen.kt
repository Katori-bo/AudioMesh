package com.audiomesh.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiomesh.app.ui.theme.*

@Composable
fun RolePickerScreen(
    onSenderSelected  : () -> Unit,
    onReceiverSelected: () -> Unit,
) {
    // tracks which card is being pressed for scale animation
    var senderPressed   by remember { mutableStateOf(false) }
    var receiverPressed by remember { mutableStateOf(false) }

    val senderScale   by animateFloatAsState(
        targetValue = if (senderPressed) 0.96f else 1f,
        animationSpec = tween(100), label = "senderScale"
    )
    val receiverScale by animateFloatAsState(
        targetValue = if (receiverPressed) 0.96f else 1f,
        animationSpec = tween(100), label = "receiverScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // ── App name ──────────────────────────────────────────────────────
            Text(
                text  = "AUDIOMESH",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text  = "choose your role",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(56.dp))

            // ── Sender card ───────────────────────────────────────────────────
            RoleCard(
                modifier = Modifier.scale(senderScale),
                title       = "SENDER",
                subtitle    = "Play music · control the mesh",
                description = "This device streams audio to all connected receivers. Pick a track, hit play.",
                accentColor = RoleFull,
                surfaceColor = RoleFullSurface,
                labelColor  = RoleFullOn,
                onPress     = { senderPressed = it },
                onClick     = onSenderSelected,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Receiver card ─────────────────────────────────────────────────
            RoleCard(
                modifier = Modifier.scale(receiverScale),
                title       = "RECEIVER",
                subtitle    = "Listen · sync to the mesh",
                description = "This device plays audio sent by the sender. Set your role — bass, mid, treble, or full range.",
                accentColor = RoleBass,
                surfaceColor = RoleBassSurface,
                labelColor  = RoleBassOn,
                onPress     = { receiverPressed = it },
                onClick     = onReceiverSelected,
            )
        }
    }
}

// ── Reusable role card ────────────────────────────────────────────────────────

@Composable
private fun RoleCard(
    modifier    : Modifier = Modifier,
    title       : String,
    subtitle    : String,
    description : String,
    accentColor : Color,
    surfaceColor: Color,
    labelColor  : Color,
    onPress     : (Boolean) -> Unit,
    onClick     : () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        surfaceColor.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.surface,
                    )
                )
            )
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                onClick = onClick,
            )
            .padding(24.dp)
    ) {
        Column {
            // accent dot + title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accentColor)
                )
                Text(
                    text  = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    fontSize = 13.sp,
                    letterSpacing = 3.sp,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text  = subtitle,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text      = description,
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                lineHeight = 20.sp,
            )
        }
    }
}