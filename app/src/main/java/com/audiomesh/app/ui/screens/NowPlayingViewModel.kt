package com.audiomesh.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.audiomesh.app.SenderService
import com.audiomesh.app.data.Song
import com.audiomesh.app.ui.components.SenderRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ─────────────────────────────────────────────────────────────────────────────
//  NowPlayingViewModel
//
//  Single source of truth for:
//    • The currently playing Song + role + mode
//    • The manual queue
//    • Receiver session metadata (senderIp, role, isActive)
//    • Playback progress (polled from SenderService binder in AppNavigation)
//
//  Session lifecycle contract:
//    • clearReceiver() is called ONLY when the user explicitly leaves the mesh
//      (LEAVE MESH button → ReceiverMainScreen → AppNavigation.onLeave lambda).
//    • It is NEVER called on back-press. Back-press just pops the nav stack
//      while isReceiverActive stays true, keeping the MiniPlayerBar visible.
//
//  Auto-advance guard (MESH mode):
//    • When a track ends in MESH mode, skipNext() is called from checkAutoAdvance.
//    • In MESH mode, swapOrRestartService() calls binder.swapTrack() if the
//      binder is live, or does nothing if it isn't (the sender screen shows
//      GO LIVE and the user re-initiates manually).
//    • We do NOT restart the service in MESH mode when the binder is null —
//      that would start the sender without a hotspot IP and crash the engine.
// ─────────────────────────────────────────────────────────────────────────────

class NowPlayingViewModel : ViewModel() {

    // Guards against duplicate swapTrack calls when returning via MiniPlayerBar.
    // Reset to -1L in select() so every fresh track selection always swaps.
    var lastSwappedSongId: Long = -1L

    // ── Queue ────────────────────────────────────────────────────────────────
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(0)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    // ── Playback state ───────────────────────────────────────────────────────
    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    private val _role = MutableStateFlow(SenderRole.FULL)
    val role: StateFlow<SenderRole> = _role.asStateFlow()

    private val _mode = MutableStateFlow(SenderMode.MESH)
    val mode: StateFlow<SenderMode> = _mode.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    // Internal song list — used for next/prev navigation.
    // Not exposed as StateFlow; Library sets it via setSongList().
    private var songList: List<Song> = emptyList()

    // ── Receiver session ─────────────────────────────────────────────────────
    private val _receiverSenderIp = MutableStateFlow<String?>(null)
    val receiverSenderIp: StateFlow<String?> = _receiverSenderIp.asStateFlow()

    private val _receiverRole = MutableStateFlow("FULL")
    val receiverRole: StateFlow<String> = _receiverRole.asStateFlow()

    private val _isReceiverActive = MutableStateFlow(false)
    val isReceiverActive: StateFlow<Boolean> = _isReceiverActive.asStateFlow()

    // Called when user joins a mesh (LibraryScreen / NearbyMeshBanner)
    fun setReceiverActive(senderIp: String, role: String) {
        _receiverSenderIp.value = senderIp
        _receiverRole.value     = role
        _isReceiverActive.value = true
    }

    // Called ONLY from the LEAVE MESH path (AppNavigation.onLeave lambda).
    // Do NOT call this on back-press.
    fun clearReceiver() {
        _receiverSenderIp.value = null
        _isReceiverActive.value = false
    }

    // ── Queue helpers ────────────────────────────────────────────────────────

    fun addToQueue(song: Song) {
        _queue.value = _queue.value + song
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _queue.value = list
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from in list.indices && to in list.indices) {
            val item = list.removeAt(from)
            list.add(to, item)
            _queue.value = list
        }
    }

    fun clearQueue() {
        _queue.value      = emptyList()
        _queueIndex.value = 0
    }

    // ── Song list ────────────────────────────────────────────────────────────

    fun setSongList(songs: List<Song>) {
        songList = songs
    }

    // ── Playback control ─────────────────────────────────────────────────────

    fun select(song: Song, role: SenderRole, mode: SenderMode = SenderMode.MESH) {
        autoAdvanceFired  = false
        lastSwappedSongId = -1L   // always reset so the new song always triggers swapTrack
        _song.value       = song
        _role.value       = role
        _mode.value       = mode
        _progressMs.value = 0L
        clearReceiver()           // selecting a song as sender ends any receiver session
    }

    fun updateProgress(context: Context, ms: Long) {
        _progressMs.value = ms
        val currentSong = _song.value ?: return
        checkAutoAdvance(context, ms, currentSong.durationMs)
    }

    private var autoAdvanceFired = false

    private fun checkAutoAdvance(context: Context, positionMs: Long, durationMs: Long) {
        if (autoAdvanceFired) return
        // Only auto-advance in LOCAL mode — in MESH mode the sender engine
        // loops the track automatically (see SenderEngine streaming loop).
        // Auto-advancing in MESH mode while the engine is already looping
        // causes a double-swap: the engine restarts the track AND swapTrack()
        // is called simultaneously, creating a race that crashes the encoder.
        if (_mode.value == SenderMode.MESH) return
        if (durationMs > 0 && positionMs >= durationMs - 500) {
            if (_queue.value.isNotEmpty()) {
                autoAdvanceFired = true
                skipNext(context)
            }
        }
    }

    fun clearNowPlaying() {
        _song.value       = null
        _progressMs.value = 0L
    }

    fun clearRole(newRole: SenderRole) {
        _role.value = newRole
    }

    // ── Skip next ────────────────────────────────────────────────────────────
    //
    //  Priority:
    //    1. First song in manual queue
    //    2. Next song in songList (matched by ID, not by equals())
    //    3. Wrap to first song in songList
    //    4. No-op if both are empty

    fun skipNext(context: Context) {
        autoAdvanceFired = false
        val q = _queue.value
        if (q.isNotEmpty()) {
            val next = q.first()
            _queue.value      = q.drop(1)
            _song.value       = next
            _progressMs.value = 0L
            swapOrRestartService(context, next)
            return
        }

        val current = _song.value ?: return
        if (songList.isEmpty()) return

        val idx  = songList.indexOfFirst { it.id == current.id }
        val next = when {
            idx < 0                  -> songList.first()
            idx < songList.lastIndex -> songList[idx + 1]
            else                     -> songList.first()
        }
        _song.value       = next
        _progressMs.value = 0L
        swapOrRestartService(context, next)
    }

    // ── Skip prev ────────────────────────────────────────────────────────────

    fun skipPrev(context: Context) {
        val current = _song.value ?: return
        if (songList.isEmpty()) return

        val idx  = songList.indexOfFirst { it.id == current.id }
        val prev = when {
            idx <= 0 -> songList.last()
            else     -> songList[idx - 1]
        }
        _song.value       = prev
        _progressMs.value = 0L
        swapOrRestartService(context, prev)
    }

    // ── Service management ───────────────────────────────────────────────────

    private fun swapOrRestartService(context: Context, song: Song) {
        val binder = SenderService.getBinder()
        if (binder != null) {
            // Binder is live — SenderMainScreen.LaunchedEffect(song.id) owns
            // the actual swapTrack() call. Calling it here too would cause two
            // concurrent senderSwapTrack() calls → race condition → crash.
            // Just return and let the LaunchedEffect handle it.
            return
        }

        // No binder — service not running.
        // Only auto-restart in LOCAL mode. In MESH mode the user sees GO LIVE
        // and initiates manually (we can't detect the hotspot IP here).
        if (_mode.value == SenderMode.LOCAL) {
            context.startForegroundService(
                Intent(context, SenderService::class.java).apply {
                    putExtra("mp3Uri",    song.uri.toString())
                    putExtra("title",     song.title)
                    putExtra("artist",    song.artist)
                    putExtra("localOnly", true)
                }
            )
        }
    }

    @Suppress("unused")
    private fun restartService(context: Context, song: Song) = swapOrRestartService(context, song)
}