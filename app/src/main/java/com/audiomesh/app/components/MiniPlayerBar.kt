package com.audiomesh.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiomesh.app.data.Song
import com.audiomesh.app.data.SongRepository
import com.audiomesh.app.ui.theme.RoleFull

// ── MiniPlayerBar ─────────────────────────────────────────────────────────────
//
// FIX #4 — The onTap lambda is now responsible for routing:
//
//   • If a SENDER session is active → navigate to SenderMainScreen
//   • If a RECEIVER session is active → navigate to ReceiverMainScreen
//
// This component itself is unchanged; the routing decision lives in
// AppNavigation (or whatever host composable shows this bar).
// In AppNavigation, wire it like this:
//
//   val isReceiverActive by nowPlaying.isReceiverActive.collectAsState()
//   val receiverSenderIp by nowPlaying.receiverSenderIp.collectAsState()
//   val receiverRole     by nowPlaying.receiverRole.collectAsState()
//
//   MiniPlayerBar(
//       song       = song,
//       progressMs = progressMs,
//       durationMs = durationMs,
//       isPlaying  = isPlaying,
//       onTap = {
//           if (isReceiverActive) {
//               navController.navigate(
//                   "${Routes.RECEIVER_MAIN}?senderIp=${receiverSenderIp}&role=${receiverRole}"
//               )
//           } else {
//               navController.navigate(Routes.SENDER_MAIN)
//           }
//       },
//       onPlayPause = { ... },
//   )
//
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MiniPlayerBar(
    song       : Song,
    progressMs : Long,
    durationMs : Long,
    isPlaying  : Boolean,
    onTap      : () -> Unit,
    onPlayPause: () -> Unit,
    modifier   : Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .border(
                width = 1.dp,
                color = Color(0xFF222222),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SongArtwork(song = song, size = 40)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = song.title,
                    color    = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                )
                Text(
                    text     = song.artist,
                    color    = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            }

            val remainingMs = (durationMs - progressMs).coerceAtLeast(0L)
            Text(
                text  = "-${SongRepository.formatDuration(remainingMs)}",
                color = Color(0xFF555555),
                fontSize = 11.sp,
            )

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1F1F1F))
                    .border(1.dp, Color(0xFF2A2A2A), CircleShape)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (isPlaying) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(3.dp).height(12.dp)
                            .clip(RoundedCornerShape(2.dp)).background(Color.White))
                        Box(Modifier.width(3.dp).height(12.dp)
                            .clip(RoundedCornerShape(2.dp)).background(Color.White))
                    }
                } else {
                    Text("▶", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val progress = if (durationMs > 0L)
            (progressMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else 0f

        LinearProgressIndicator(
            progress   = { progress },
            modifier   = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
            color      = RoleFull,
            trackColor = Color(0xFF2A2A2A),
            strokeCap  = StrokeCap.Round,
        )
    }
}