package com.audiomesh.app.ui.screens

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.audiomesh.app.SenderService
import com.audiomesh.app.ui.screens.SenderMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiomesh.app.data.Song
import com.audiomesh.app.data.SongRepository
import com.audiomesh.app.ui.components.MiniPlayerBar
import com.audiomesh.app.ui.components.PlaybackSheetContent
import com.audiomesh.app.ui.components.SenderRole
import com.audiomesh.app.ui.components.SongArtwork
import com.audiomesh.app.ui.theme.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton

// ── Library screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onSendToMesh : (Song, SenderRole) -> Unit,
    onPlayLocally: (Song) -> Unit,
    onJoinMesh   : (String, String) -> Unit = { _, _ -> },
    viewModel    : LibraryViewModel = viewModel(),
    nowPlaying   : NowPlayingViewModel = viewModel(),
) {
    val uiState     by viewModel.uiState.collectAsState()
    val progressMs  by nowPlaying.progressMs.collectAsState()
    val nowSong     by nowPlaying.song.collectAsState()
    var infoSong    by remember { mutableStateOf<Song?>(null) }
    var selectedFilterIndex by remember { mutableStateOf(0) }
    var activeBottomTab     by remember { mutableStateOf("LIBRARY") }

    // Keep NowPlayingViewModel's song list in sync so Next/Prev work correctly
    LaunchedEffect(uiState.songs) {
        nowPlaying.setSongList(uiState.songs)
    }

    var sheetSong         by remember { mutableStateOf<Song?>(null) }
    val sheetState        = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet         by remember { mutableStateOf(false) }
    var showMeshSheet     by remember { mutableStateOf(false) }
    var optionsSong       by remember { mutableStateOf<Song?>(null) }
    var showOptionsSheet  by remember { mutableStateOf(false) }
    var showDevicesSheet  by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            LibraryTopBar(
                searchQuery    = uiState.searchQuery,
                onSearchChange = viewModel::onSearchQueryChange,
                onSearchClear  = { viewModel.onSearchQueryChange("") },
            )

            // ── Nearby mesh banner ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = uiState.nearbyMeshTrack != null,
                enter   = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit    = fadeOut(),
            ) {
                NearbyMeshBanner(
                    track     = uiState.nearbyMeshTrack ?: "",
                    artist    = uiState.nearbyMeshArtist ?: "",
                    onJoin    = { uiState.nearbyMeshSenderIp?.let { onJoinMesh(it, "FULL") } },
                    onDismiss = viewModel::dismissNearbyBanner,
                    modifier  = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // ── Filter tabs ───────────────────────────────────────────────────
            FilterTabs(
                selected         = selectedFilterIndex,
                onSelectedChange = { selectedFilterIndex = it },
            )

            // ── Song list / empty states ──────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                val context = LocalContext.current

                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.align(Alignment.Center),
                        color       = RoleFull,
                        strokeWidth = 2.dp,
                    )
                } else {
                    val songs = viewModel.filteredSongs()

                    when {
                        !uiState.hasStoragePermission -> {
                            EmptyState(
                                hasQuery          = false,
                                permissionGranted = false,
                                onGrantClick      = {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                    ).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                },
                            )
                        }
                        songs.isEmpty() -> {
                            EmptyState(
                                hasQuery          = uiState.searchQuery.isNotEmpty(),
                                permissionGranted = true,
                                onGrantClick      = {},
                            )
                        }
                        else -> {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start  = 12.dp,
                                    end    = 12.dp,
                                    top    = 4.dp,
                                    bottom = 8.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                when (selectedFilterIndex) {
                                    0 -> { // Songs
                                        items(songs, key = { it.id }) { song ->
                                            SongRow(
                                                song      = song,
                                                isPlaying = nowSong?.id == song.id,
                                                onTap     = { sheetSong = song; showSheet = true },
                                                onOptions = {
                                                    optionsSong  = song
                                                    showOptionsSheet = true
                                                },
                                            )
                                        }
                                    }
                                    1 -> { // Albums
                                        val albums = songs.groupBy { it.album }
                                        items(albums.keys.toList()) { albumTitle ->
                                            val albumSongs = albums[albumTitle] ?: emptyList()
                                            GroupedRow(
                                                title     = albumTitle,
                                                subtitle  = "${albumSongs.size} songs",
                                                firstSong = albumSongs.first(),
                                                onClick   = {},
                                            )
                                        }
                                    }
                                    2 -> { // Artists
                                        val artists = songs.groupBy { it.artist }
                                        items(artists.keys.toList()) { artistName ->
                                            val artistSongs = artists[artistName] ?: emptyList()
                                            GroupedRow(
                                                title     = artistName,
                                                subtitle  = "${artistSongs.size} tracks",
                                                firstSong = artistSongs.first(),
                                                onClick   = {},
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }



            // ── Bottom nav ────────────────────────────────────────────────────
            BottomNavBar(
                activeTab  = activeBottomTab,
                onTabClick = { tab ->
                    activeBottomTab = tab
                    when (tab) {
                        "MESH"    -> { showMeshSheet = true;    activeBottomTab = "LIBRARY" }
                        "DEVICES" -> { showDevicesSheet = true; activeBottomTab = "LIBRARY" }
                    }
                },
            )
        }

        // ── Playback bottom sheet ─────────────────────────────────────────────
        if (showSheet && sheetSong != null) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState       = sheetState,
                containerColor   = Color(0xFF141414),
                dragHandle       = null,
                shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                PlaybackSheetContent(
                    song               = sheetSong!!,
                    startOnReceiverTab = false,
                    onSendToMesh = { role ->
                        showSheet = false
                        nowPlaying.select(sheetSong!!, role, SenderMode.MESH)
                        onSendToMesh(sheetSong!!, role)
                    },
                    onPlayLocally = {
                        showSheet = false
                        nowPlaying.select(sheetSong!!, SenderRole.FULL, SenderMode.LOCAL)
                        onPlayLocally(sheetSong!!)
                    },
                    onJoinAsMesh = { role, ip ->
                        showSheet = false
                        onJoinMesh(ip, role)
                    },
                    onDismiss = { showSheet = false },
                )
            }
        }

        // ── Mesh sheet ────────────────────────────────────────────────────────
        if (showMeshSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMeshSheet = false },
                sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor   = Color(0xFF141414),
                dragHandle       = null,
                shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                val meshSong = nowSong ?: sheetSong
                if (meshSong != null) {
                    PlaybackSheetContent(
                        song               = meshSong,
                        startOnReceiverTab = true,
                        onSendToMesh = { role ->
                            showMeshSheet = false
                            nowPlaying.select(meshSong, role, SenderMode.MESH)
                            onSendToMesh(meshSong, role)
                        },
                        onPlayLocally = {
                            showMeshSheet = false
                            nowPlaying.select(meshSong, SenderRole.FULL, SenderMode.LOCAL)
                            onPlayLocally(meshSong)
                        },
                        onJoinAsMesh = { role, ip ->
                            showMeshSheet = false
                            onJoinMesh(ip, role)
                        },
                        onDismiss = { showMeshSheet = false },
                    )
                } else {
                    // No song — show receiver-only join
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF141414))
                            .padding(24.dp)
                            .navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .width(36.dp).height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF2A2A2A))
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "JOIN A MESH",
                            style         = MaterialTheme.typography.labelSmall,
                            color         = Color(0xFF555555),
                            letterSpacing = 3.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        ReceiverQuickJoin(
                            onJoin = { role, ip ->
                                showMeshSheet = false
                                onJoinMesh(ip, role)
                            }
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        // ── Song options sheet ────────────────────────────────────────────────
        if (showOptionsSheet && optionsSong != null) {
            val context = LocalContext.current
            ModalBottomSheet(
                onDismissRequest = { showOptionsSheet = false },
                sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor   = Color(0xFF141414),
                dragHandle       = null,
                shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                SongOptionsSheet(
                    song       = optionsSong!!,
                    nowPlaying = nowPlaying,
                    onDismiss  = { showOptionsSheet = false },
                    onPlay     = {
                        showOptionsSheet = false
                        sheetSong = optionsSong
                        showSheet = true
                    },
                    onShowInfo = { song ->
                        infoSong = song
                    },
                )
            }
        }

        // ── Song info dialog ──────────────────────────────────────────────────
        infoSong?.let { song ->
            AlertDialog(
                onDismissRequest = { infoSong = null },
                containerColor   = Color(0xFF1A1A1A),
                title            = { Text("Song Metadata", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetadataItem("Title",    song.title)
                        MetadataItem("Artist",   song.artist)
                        MetadataItem("Album",    song.album)
                        MetadataItem("Duration", SongRepository.formatDuration(song.durationMs))
                        MetadataItem("File ID",  song.id.toString())
                    }
                },
                confirmButton = {
                    TextButton(onClick = { infoSong = null }) {
                        Text("CLOSE", color = RoleFull)
                    }
                },
            )
        }

        // ── Connected devices sheet ───────────────────────────────────────────
        if (showDevicesSheet) {
            val pings by viewModel.devicePings.collectAsState()
            ModalBottomSheet(
                onDismissRequest = { showDevicesSheet = false },
                containerColor   = Color(0xFF141414),
                dragHandle       = null,
                shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF2A2A2A))
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "CONNECTED DEVICES",
                        style         = MaterialTheme.typography.labelSmall,
                        color         = Color(0xFF555555),
                        letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.height(20.dp))

                    if (pings.isEmpty()) {
                        Text(
                            text      = "No receivers connected.\nStart a mesh session to see live ping data.",
                            color     = Color.Gray,
                            fontSize  = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier  = Modifier.padding(vertical = 32.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(pings.toList()) { (ip, ping) ->
                                DeviceRow(ip = ip, ping = ping)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun LibraryTopBar(
    searchQuery   : String,
    onSearchChange: (String) -> Unit,
    onSearchClear : () -> Unit,
) {
    var searchExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard       = LocalSoftwareKeyboardController.current

    // Auto-focus when the search field appears
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) focusRequester.requestFocus()
    }

    // Collapse search when query is cleared externally
    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty() && searchExpanded) {
            searchExpanded = false
            keyboard?.hide()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text          = "AUDIOMESH",
                style         = MaterialTheme.typography.labelSmall,
                color         = Color(0xFF444444),
                letterSpacing = 3.sp,
            )
            Text(
                text  = "Library",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
            )
        }

        if (searchExpanded) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value         = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder   = {
                        Text("Search…", color = Color(0xFF444444), fontSize = 14.sp)
                    },
                    singleLine    = true,
                    modifier      = Modifier
                        .width(180.dp)
                        .height(44.dp)
                        .focusRequester(focusRequester),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color(0xFF1A1A1A),
                        unfocusedContainerColor = Color(0xFF1A1A1A),
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        cursorColor             = RoleFull,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape     = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
                // Close / clear search button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A1A1A))
                        .clickable {
                            onSearchClear()
                            searchExpanded = false
                            keyboard?.hide()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", color = Color(0xFF888888), fontSize = 18.sp)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, Color(0xFF2A2A2A), CircleShape)
                    .clickable { searchExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("⌕", color = Color(0xFF888888), fontSize = 18.sp)
            }
        }
    }
}

// ── Nearby mesh banner ────────────────────────────────────────────────────────

@Composable
private fun NearbyMeshBanner(
    track    : String,
    artist   : String,
    onJoin   : () -> Unit,
    onDismiss: () -> Unit,
    modifier : Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1035))
            .border(1.dp, Color(0xFF3B1F6E), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(RoleFull.copy(alpha = 0.15f))
                .border(1.dp, RoleFull.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(RoleFullOn))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text          = "AUDIOMESH NEARBY",
                style         = MaterialTheme.typography.labelSmall,
                color         = RoleFullOn,
                fontSize      = 9.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text     = if (artist.isNotBlank()) "$artist · $track" else track,
                style    = MaterialTheme.typography.bodyMedium,
                color    = Color(0xFFE0D0FF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(RoleFull)
                .clickable(onClick = onJoin)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text          = "JOIN →",
                style         = MaterialTheme.typography.labelSmall,
                color         = Color.White,
                fontSize      = 10.sp,
                letterSpacing = 1.sp,
                fontWeight    = FontWeight.Bold,
            )
        }

        Text(
            text     = "×",
            color    = Color(0xFF444444),
            fontSize = 18.sp,
            modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
        )
    }
}

// ── Filter tabs ───────────────────────────────────────────────────────────────

@Composable
private fun FilterTabs(
    selected        : Int,
    onSelectedChange: (Int) -> Unit,
) {
    val tabs = listOf("Songs", "Albums", "Artists")
    Row(
        modifier              = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (i == selected) Color.White else Color(0xFF1A1A1A))
                    .clickable { onSelectedChange(i) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text          = label,
                    fontSize      = 12.sp,
                    fontWeight    = FontWeight.Bold,
                    color         = if (i == selected) Color.Black else Color(0xFF666666),
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

// ── Song row ──────────────────────────────────────────────────────────────────

@Composable
private fun SongRow(
    song     : Song,
    isPlaying: Boolean = false,
    onTap    : () -> Unit,
    onOptions: () -> Unit = {},
) {
    val bgColor = if (isPlaying) Color(0xFF1A1025) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .then(
                if (isPlaying) Modifier.border(
                    1.dp, RoleFull.copy(alpha = 0.25f), RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SongArtwork(song = song, size = 46)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = song.title,
                style      = MaterialTheme.typography.bodyLarge,
                color      = if (isPlaying) RoleFullOn else Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                fontSize   = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            // Prefer enriched remote metadata for artist/album display
            val displayArtist = song.remoteArtist ?: song.artist
            val displayAlbum  = song.remoteAlbum  ?: song.album
            Text(
                text     = "$displayArtist · $displayAlbum",
                style    = MaterialTheme.typography.bodyMedium,
                color    = Color(0xFF555555),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
            )
        }

        // Playing indicator or duration
        if (isPlaying) {
            // Small animated-ish indicator for the now-playing row
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(RoleFull),
            )
        } else {
            Text(
                text     = SongRepository.formatDuration(song.durationMs),
                style    = MaterialTheme.typography.labelMedium,
                color    = Color(0xFF444444),
                fontSize = 11.sp,
            )
        }

        // 3-dot menu
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOptions)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF444444))
                    )
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    hasQuery         : Boolean,
    permissionGranted: Boolean,
    onGrantClick     : () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text     = if (!permissionGranted) "🔒" else if (hasQuery) "⌕" else "🎵",
            fontSize = 48.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text = when {
                !permissionGranted -> "ACCESS REQUIRED"
                hasQuery           -> "NO RESULTS"
                else               -> "LIBRARY EMPTY"
            },
            style         = MaterialTheme.typography.labelSmall,
            color         = if (!permissionGranted) RoleFull else Color(0xFF444444),
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = when {
                !permissionGranted -> "GridAudio needs permission to access your local music files."
                hasQuery           -> "Try a different search term."
                else               -> "No audio files found. Add music to your 'Music' folder to get started."
            },
            style     = MaterialTheme.typography.bodyMedium,
            color     = Color(0xFF666666),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!permissionGranted) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onGrantClick,
                colors  = ButtonDefaults.buttonColors(containerColor = RoleFull),
                shape   = RoundedCornerShape(12.dp),
            ) {
                Text("GRANT PERMISSION", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Receiver quick join (used when no song is selected in the Mesh sheet) ─────

@Composable
private fun ReceiverQuickJoin(
    onJoin: (role: String, ip: String) -> Unit,
) {
    var selectedRole by remember { mutableStateOf("FULL") }
    var manualIp     by remember { mutableStateOf("") }
    val keyboard     = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("FULL", "BASS", "MID", "TREBLE").forEach { role ->
                val roleEnum   = SenderRole.valueOf(role)
                val isSelected = selectedRole == role
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) roleEnum.surface else Color(0xFF161616))
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
                    Text(
                        role,
                        style         = MaterialTheme.typography.labelSmall,
                        color         = if (isSelected) roleEnum.onAccent else Color(0xFF555555),
                        fontSize      = 10.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        TextField(
            value         = manualIp,
            onValueChange = { manualIp = it.filter { c -> c.isDigit() || c == '.' } },
            placeholder   = {
                Text("Sender IP (blank = auto)", color = Color(0xFF333333), fontSize = 13.sp)
            },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor   = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF181818),
                focusedTextColor        = Color.White,
                unfocusedTextColor      = Color.White.copy(alpha = 0.70f),
                cursorColor             = RoleFull,
                focusedIndicatorColor   = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape           = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction    = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D2B1F))
                .border(1.dp, Color(0xFF1A5C38), RoundedCornerShape(16.dp))
                .clickable { keyboard?.hide(); onJoin(selectedRole, manualIp.trim()) }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text          = if (manualIp.isBlank()) "JOIN MESH  ·  AUTO-DISCOVER"
                else "JOIN  $manualIp",
                style         = MaterialTheme.typography.labelSmall,
                color         = Color(0xFF00C896),
                fontSize      = 12.sp,
                letterSpacing = 2.sp,
                fontWeight    = FontWeight.Bold,
            )
        }
    }
}

// ── Bottom nav ────────────────────────────────────────────────────────────────

@Composable
private fun BottomNavBar(
    activeTab : String,
    onTabClick: (String) -> Unit,
) {
    val items = listOf("LIBRARY", "MESH", "DEVICES")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D0D))
            .border(1.dp, Color(0xFF1A1A1A))
            .padding(vertical = 10.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        items.forEach { label ->
            val isActive = activeTab == label
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTabClick(label) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isActive) RoleFull else Color.Transparent),
                )
                Text(
                    text          = label,
                    style         = MaterialTheme.typography.labelSmall,
                    color         = if (isActive) Color.White else Color(0xFF444444),
                    fontSize      = 10.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

// ── Song options sheet ────────────────────────────────────────────────────────

@Composable
private fun SongOptionsSheet(
    song      : Song,
    nowPlaying: NowPlayingViewModel,
    onDismiss : () -> Unit,
    onPlay    : () -> Unit,
    onShowInfo: (Song) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .width(36.dp).height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF2A2A2A))
                .align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(20.dp))

        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            SongArtwork(song = song, size = 46)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = song.title,
                    style      = MaterialTheme.typography.bodyLarge,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    fontSize   = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                val displayArtist = song.remoteArtist ?: song.artist
                val displayAlbum  = song.remoteAlbum  ?: song.album
                Text(
                    text     = "$displayArtist · $displayAlbum",
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = Color(0xFF555555),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 0.5.dp)
        OptionsRow("Play / Send to Mesh", "▶", onClick = onPlay)
        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 0.5.dp)
        OptionsRow("Add to Queue", "↓") {
            nowPlaying.addToQueue(song)
            Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 0.5.dp)
        OptionsRow("Song Info", "ℹ") {
            onShowInfo(song)
            onDismiss()
        }
        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 0.5.dp)
        OptionsRow("Share", "↗") {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Listening to ${song.title} by ${song.artist} on GridAudio"
                )
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Song"))
            onDismiss()
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OptionsRow(label: String, icon: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = icon, color = Color(0xFF666666), fontSize = 16.sp, modifier = Modifier.width(20.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun GroupedRow(
    title    : String,
    subtitle : String,
    firstSong: Song,
    onClick  : () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SongArtwork(song = firstSong, size = 50)
        Column {
            Text(text = title,    color = Color.White,         fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(text = subtitle, color = Color(0xFF555555),   fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetadataItem(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = Color.Gray, letterSpacing = 1.sp)
        Text(value, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun DeviceRow(ip: String, ping: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = ip, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(text = "Receiver client", color = Color.Gray, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            ping < 0   -> Color.Gray
                            ping < 80  -> Color(0xFF00C896)
                            ping < 150 -> Color.Yellow
                            else       -> Color.Red
                        }
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text       = if (ping >= 0) "$ping ms" else "— ms",
                color      = Color.White,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}