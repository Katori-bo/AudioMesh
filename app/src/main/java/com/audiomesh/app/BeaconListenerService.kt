package com.audiomesh.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

// ── BeaconListenerService ─────────────────────────────────────────────────────
//
// Listens on UDP port 5102 for AudioMesh sender beacons.
// Beacon format: "AUDIOMESH:<senderIP>:<tcpPort>\n"
//   e.g.        "AUDIOMESH:192.168.43.1:5100\n"
//
// When a beacon is heard:
//   1. Parses sender IP and port
//   2. Broadcasts ACTION_BEACON_DETECTED intent with extras:
//        EXTRA_SENDER_IP  = "192.168.43.1"
//        EXTRA_TRACK      = "" (filled in when palette broadcast is wired, step 9)
//        EXTRA_ARTIST     = ""
//
// The LibraryViewModel registers a receiver for ACTION_BEACON_DETECTED and
// calls onNearbyMeshDetected() to show the banner.
//
// Lifecycle: started by AudioMeshActivity on launch, stopped on activity destroy.
// Uses a coroutine on Dispatchers.IO — no native threads needed.
// ─────────────────────────────────────────────────────────────────────────────

class BeaconListenerService : Service() {

    companion object {
        const val TAG                  = "BeaconListener"
        const val UDP_PORT             = 5102
        const val BEACON_PREFIX        = "AUDIOMESH:"
        const val ACTION_BEACON_DETECTED =
            "com.audiomesh.app.BEACON_DETECTED"
        const val EXTRA_SENDER_IP      = "sender_ip"
        const val EXTRA_TRACK          = "track"
        const val EXTRA_ARTIST         = "artist"

        // Silence the banner after this many ms with no beacon
        const val BEACON_TIMEOUT_MS    = 6000L

        private const val CHANNEL      = "audiomesh_beacon"
        private const val NOTIF_ID     = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null

    // Last sender IP we reported — used to avoid spamming duplicate intents
    private var lastSenderIp: String = ""
    private var lastBeaconMs: Long   = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        startListening()
        Log.i(TAG, "BeaconListenerService started — listening on UDP $UDP_PORT")
    }

    override fun onDestroy() {
        scope.cancel()
        socket?.close()
        socket = null
        Log.i(TAG, "BeaconListenerService stopped")
        super.onDestroy()
    }

    // ── UDP listener coroutine ────────────────────────────────────────────────

    private fun startListening() {
        scope.launch {
            try {
                socket = DatagramSocket(UDP_PORT).also { s ->
                    s.soTimeout = 1000 // 1 s read timeout so we can check timeout
                    s.reuseAddress = true
                }

                val buf    = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    try {
                        socket?.receive(packet) ?: break
                        val msg = String(packet.data, 0, packet.length).trim()

                        if (!msg.startsWith(BEACON_PREFIX)) continue

                        // Parse: "AUDIOMESH:<ip>:<port>"
                        val rest  = msg.removePrefix(BEACON_PREFIX)
                        val parts = rest.split(":")
                        if (parts.isEmpty()) continue
                        val senderIp = parts[0].trim()
                        if (senderIp.isBlank()) continue

                        lastBeaconMs = System.currentTimeMillis()

                        // Only broadcast if sender changed or first detection

                        lastSenderIp = senderIp
                        broadcastDetected(senderIp, track = "", artist = "")
                        Log.i(TAG, "Beacon from $senderIp")


                    } catch (_: SocketTimeoutException) {
                        // Check if beacon has gone silent — post a "lost" broadcast
                        val silentMs = System.currentTimeMillis() - lastBeaconMs
                        if (lastSenderIp.isNotBlank() && silentMs > BEACON_TIMEOUT_MS) {
                            Log.i(TAG, "Beacon lost — sender gone silent ($silentMs ms)")
                            lastSenderIp = ""
                            broadcastLost()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: ${e.message}")
            }
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private fun broadcastDetected(senderIp: String, track: String, artist: String) {
        sendBroadcast(Intent(ACTION_BEACON_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_SENDER_IP, senderIp)
            putExtra(EXTRA_TRACK,     track)
            putExtra(EXTRA_ARTIST,    artist)
        })
    }

    private fun broadcastLost() {
        sendBroadcast(Intent(ACTION_BEACON_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_SENDER_IP, "")   // empty = sender gone
            putExtra(EXTRA_TRACK,     "")
            putExtra(EXTRA_ARTIST,    "")
        })
    }

    // ── Notification (required for foreground service) ────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "AudioMesh Discovery",
                    NotificationManager.IMPORTANCE_MIN,  // silent — no sound/vibrate
                ).apply { setShowBadge(false) }
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("AudioMesh")
            .setContentText("Listening for nearby devices")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }
}