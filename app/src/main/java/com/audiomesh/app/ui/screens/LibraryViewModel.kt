package com.audiomesh.app.ui.screens
import androidx.core.content.ContextCompat
import com.audiomesh.app.data.MetadataCache
import com.audiomesh.app.data.MetadataCache.toRemoteMetadata
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiomesh.app.BeaconListenerService
import com.audiomesh.app.data.Song
import com.audiomesh.app.data.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay     // ← ADD
import kotlinx.coroutines.isActive  // ← ADD
// ── UI state ──────────────────────────────────────────────────────────────────

data class LibraryUiState(
    val songs            : List<Song> = emptyList(),
    val isLoading        : Boolean    = true,
    val searchQuery      : String     = "",
    val nearbyMeshTrack  : String?    = null,
    val nearbyMeshArtist : String?    = null,
    val nearbyMeshSenderIp: String?   = null,
    val hasStoragePermission: Boolean = true // Objectives 2: Resilience
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val artCache = mutableMapOf<Long, String?>()
    private val _devicePings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val devicePings: StateFlow<Map<String, Long>> = _devicePings.asStateFlow()

    // ── Beacon broadcast receiver ─────────────────────────────────────────────

    private val beaconReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BeaconListenerService.ACTION_BEACON_DETECTED) return
            val senderIp = intent.getStringExtra(BeaconListenerService.EXTRA_SENDER_IP) ?: ""
            val track    = intent.getStringExtra(BeaconListenerService.EXTRA_TRACK)     ?: ""
            val artist   = intent.getStringExtra(BeaconListenerService.EXTRA_ARTIST)    ?: ""

            if (senderIp.isBlank()) {
                dismissNearbyBanner()
            } else {
                val senderBinder = com.audiomesh.app.SenderService.getBinder()
                if (senderBinder != null) return

                _uiState.update { it.copy(
                    nearbyMeshSenderIp = senderIp,
                    nearbyMeshTrack    = track.ifBlank { "AudioMesh" },
                    nearbyMeshArtist   = artist.ifBlank { senderIp },
                ) }
            }
        }
    }

    init {
        // Only load if permission might be there; Activity will call updatePermissionStatus soon
        loadSongs()
        registerBeaconReceiver()
        startPingPolling()
    }

    // ── Permission Management (Objective 2: Resilience) ───────────────────────

    /**
     * Called by AudioMeshActivity when permission result is known.
     */
    fun updatePermissionStatus(isGranted: Boolean) {
        _uiState.update { it.copy(hasStoragePermission = isGranted) }

        if (isGranted) {
            // If we just got permission, trigger a fresh scan
            loadSongs()
        } else {
            // If denied, ensure we aren't showing a "loading" spinner forever
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ── Load songs ────────────────────────────────────────────────────────────

    private fun loadSongs() {
        viewModelScope.launch {
            // Only attempt to load from MediaStore if we think we have permission
            if (_uiState.value.hasStoragePermission) {
                val songs = SongRepository.loadSongs(getApplication())
                _uiState.update { it.copy(
                    songs     = songs,
                    isLoading = false,
                ) }

            // Fetch art in background after list is ready
                viewModelScope.launch {
                    songs.forEach { song ->
                        fetchArtIfNeeded(song)
                        delay(100)
                    }
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun reloadSongs() {
        _uiState.update { it.copy(isLoading = true) }
        loadSongs()
    }

    // ── Search & Filter ───────────────────────────────────────────────────────

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun filteredSongs(): List<Song> {
        val q = _uiState.value.searchQuery.trim().lowercase()
        if (q.isEmpty()) return _uiState.value.songs
        return _uiState.value.songs.filter {
            it.title.lowercase().contains(q)  ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
        }
    }

    // ── Remote art fetch ──────────────────────────────────────────────────────

    fun fetchArtIfNeeded(song: Song) {
        if (artCache.containsKey(song.id)) return
        artCache[song.id] = null
        viewModelScope.launch {

            // ── Check disk cache first ────────────────────────────────────────
            val cached = MetadataCache.get(getApplication(), song.title, song.artist)
            if (cached != null) {
                // Already have data on disk — apply immediately, no network call
                val meta = cached.toRemoteMetadata()
                applyMeta(song.id, meta)
                return@launch
            }

            // ── Not cached — fetch from APIs ──────────────────────────────────
            val meta = SongRepository.fetchRemoteMetadata(song.title, song.artist)

            if (meta != null) {
                // Save to disk so next launch is instant
                MetadataCache.put(
                    context = getApplication(),
                    title   = song.title,
                    artist  = song.artist,
                    entry   = MetadataCache.CacheEntry(
                        artUrl      = meta.artUrl,
                        artist      = meta.artist,
                        album       = meta.album,
                        genre       = meta.genre,
                        releaseYear = meta.releaseYear,
                    )
                )
                applyMeta(song.id, meta)
            } else {
                // All APIs failed — mark as failed so we don't retry for 7 days
                MetadataCache.putFailed(getApplication(), song.title, song.artist)
            }
        }
    }

    // ── Apply metadata to song in UI state ───────────────────────────────────────
    private fun applyMeta(songId: Long, meta: SongRepository.RemoteMetadata) {
        val updated = _uiState.value.songs.map {
            if (it.id == songId) it.copy(
                remoteArtUrl = meta.artUrl,
                remoteArtist = meta.artist,
                remoteAlbum  = meta.album,
                genre        = meta.genre,
                releaseYear  = meta.releaseYear,
            ) else it
        }
        _uiState.update { it.copy(songs = updated) }
    }

    // ── Banner Management ─────────────────────────────────────────────────────

    fun dismissNearbyBanner() {
        _uiState.update { it.copy(
            nearbyMeshTrack    = null,
            nearbyMeshArtist   = null,
            nearbyMeshSenderIp = null,
        ) }
    }

    // ← ADD THIS FUNCTION RIGHT AFTER dismissNearbyBanner()
    private fun startPingPolling() {
        viewModelScope.launch {
            while (isActive) {
                val binder = com.audiomesh.app.SenderService.getBinder()
                if (binder != null) {
                    _devicePings.update { binder.getReceiverPings() }
                } else {
                    _devicePings.update { emptyMap() }
                }
                delay(2000L)
            }
        }
    }

    private fun registerBeaconReceiver() {
        val filter = IntentFilter(BeaconListenerService.ACTION_BEACON_DETECTED)
        // AFTER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(
                beaconReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // API 26–32: must also pass the flag explicitly
            androidx.core.content.ContextCompat.registerReceiver(
                getApplication(),
                beaconReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(beaconReceiver)
        } catch (_: Exception) { }
    }
}