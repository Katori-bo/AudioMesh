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
//    • The upcoming queue
//    • Receiver session metadata (senderIp, role, active flag)
//    • Playback progress (polled from SenderService binder)
//
//  skipNext / skipPrev crash fix
//  ─────────────────────────────
//  Root cause: Song.equals() compares ALL fields including remoteArtUrl,
//  remoteArtist, remoteAlbum.  The Song in _song may have been enriched with
//  remote metadata after being added to songList, so indexOf() always returns
//  -1 in that case — making next/prev always jump to index 0 (first song).
//
//  Fix: match songs by ID (stable Long), not by structural equality.
//
//  Secondary crash: if the SenderService binder is null AND mode is MESH,
//  startForegroundService is called with localOnly=false, which starts the
//  sender without a detected hotspot IP and causes the native engine to crash
//  on bind().  Fix: guard with the current mode and skip the restart when
//  the service is expected to be running but the binder is transiently null.
// ─────────────────────────────────────────────────────────────────────────────

class NowPlayingViewModel : ViewModel() {

    // Survives navigation back via miniplayer — prevents swapTrack from
    // firing again when SenderMainScreen is recreated for the same song.
    var lastSwappedSongId: Long = -1L

    // ── Queue State ──────────────────────────────────────────────────────────
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(0)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    // ── Playback State ───────────────────────────────────────────────────────
    private val _song  = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    private val _role  = MutableStateFlow(SenderRole.FULL)
    val role: StateFlow<SenderRole> = _role.asStateFlow()

    private val _mode  = MutableStateFlow(SenderMode.MESH)
    val mode: StateFlow<SenderMode> = _mode.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    // ── Song list (set from LibraryScreen on load) ───────────────────────────
    // Kept as a plain list — no need to expose as StateFlow since the
    // Composable doesn't observe it directly.
    private var songList: List<Song> = emptyList()

    // ── Receiver session tracking ────────────────────────────────────────────
    private val _receiverSenderIp = MutableStateFlow<String?>(null)
    val receiverSenderIp: StateFlow<String?> = _receiverSenderIp.asStateFlow()

    private val _receiverRole = MutableStateFlow("FULL")
    val receiverRole: StateFlow<String> = _receiverRole.asStateFlow()

    private val _isReceiverActive = MutableStateFlow(false)
    val isReceiverActive: StateFlow<Boolean> = _isReceiverActive.asStateFlow()

    fun setReceiverActive(senderIp: String, role: String) {
        _receiverSenderIp.value = senderIp
        _receiverRole.value     = role
        _isReceiverActive.value = true
    }

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
        _queue.value  = emptyList()
        _queueIndex.value = 0
    }

    // ── Song list ────────────────────────────────────────────────────────────

    fun setSongList(songs: List<Song>) {
        songList = songs
    }

    // ── Playback control ─────────────────────────────────────────────────────

    fun select(song: Song, role: SenderRole, mode: SenderMode = SenderMode.MESH) {
        autoAdvanceFired = false
        lastSwappedSongId = -1L   // reset so the new song always goes through swapTrack
        _song.value  = song
        _role.value  = role
        _mode.value  = mode
        _progressMs.value = 0L
        clearReceiver()
    }
    fun updateProgress(context: Context, ms: Long) {
        _progressMs.value = ms
        val currentSong = _song.value ?: return
        checkAutoAdvance(context, ms, currentSong.durationMs)
    }

    // Guard flag — prevents checkAutoAdvance firing more than once
    // per track. Reset in skipNext and select.
    private var autoAdvanceFired = false

    private fun checkAutoAdvance(context: Context, positionMs: Long, durationMs: Long) {
        if (autoAdvanceFired) return
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
    //    1. Play the first song in the manual queue.
    //    2. Advance to the next song in songList (by ID match, not equals()).
    //    3. Wrap around to the first song in songList.
    //    4. No-op if songList is also empty.

    fun skipNext(context: Context) {
        autoAdvanceFired = false   // reset so next track can auto-advance too
        val q = _queue.value
        if (q.isNotEmpty()) {
            val next = q.first()
            _queue.value = q.drop(1)
            _song.value = next
            _progressMs.value = 0L
            swapOrRestartService(context, next)
            return
        }

        val current = _song.value ?: return
        if (songList.isEmpty()) return

        // FIX: match by ID so enriched remote-metadata fields don't break indexOf
        val idx  = songList.indexOfFirst { it.id == current.id }
        val next = when {
            idx < 0                    -> songList.first()           // not found → wrap
            idx < songList.lastIndex   -> songList[idx + 1]          // normal advance
            else                       -> songList.first()           // end of list → wrap
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
            idx <= 0  -> songList.last()     // first song or not found → wrap to end
            else      -> songList[idx - 1]
        }
        _song.value       = prev
        _progressMs.value = 0L
        swapOrRestartService(context, prev)
    }

    // ── Service management ───────────────────────────────────────────────────
    //
    //  FIX: swapTrack preferred — avoids the 10 s stop/restart latency.
    //
    //  Cold-start guard: if the binder is null AND mode is MESH, the service
    //  only when mode == MESH; for LOCAL we use localOnly=true.
    //  This prevents the native SenderEngine crash that occurred when
    //  startForegroundService was called with localOnly=false before the
    //  Wi-Fi hotspot IP had been detected.

    private fun swapOrRestartService(context: Context, song: Song) {
        if (SenderService.getBinder() != null) {
            // Binder is live — SenderMainScreen.LaunchedEffect(song.id) already
            // calls swapTrack() when _song.value changes. Calling it here too
            // causes two concurrent senderSwapTrack() calls on the native engine
            // → race condition → crash. Let the LaunchedEffect own the swap.
            return
        }

        // No binder — service not running. Only auto-restart in LOCAL mode.
        // In MESH mode the sender screen will show GO LIVE for the user to re-initiate.
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

    // Keep old name for any call sites that already use it
    @Suppress("unused")
    private fun restartService(context: Context, song: Song) = swapOrRestartService(context, song)
}