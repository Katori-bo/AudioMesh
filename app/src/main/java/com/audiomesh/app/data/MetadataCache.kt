package com.audiomesh.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

// ── Persistent metadata cache ─────────────────────────────────────────────────
//
// Saves fetched art URLs, artist names, album names to disk so they survive
// app restarts. Cache is keyed by "title|artist" so it works across library
// rescans even if MediaStore IDs change.
//
// Format: one JSON object per line in cache.json
//   {"key":"title|artist","artUrl":"...","artist":"...","album":"...","genre":"...","year":"..."}
// ─────────────────────────────────────────────────────────────────────────────

object MetadataCache {

    private const val CACHE_FILE = "metadata_cache.json"

    // In-memory map for fast lookup — loaded once on first access
    private var memCache: MutableMap<String, CacheEntry>? = null

    data class CacheEntry(
        val artUrl     : String?,
        val artist     : String?,
        val album      : String?,
        val genre      : String?,
        val releaseYear: String?,
        // Flag to mark entries where all APIs failed — so we don't retry
        // on every launch forever. Reset after 7 days.
        val failed     : Boolean = false,
        val fetchedAt  : Long    = System.currentTimeMillis(),
    )

    // ── Cache key ─────────────────────────────────────────────────────────────
    // Use cleaned title + artist so "01_song_320kbps" and "Song" map to the
    // same cache entry.

    fun key(title: String, artist: String): String =
        "${title.lowercase().trim()}|${artist.lowercase().trim()}"

    // ── Read ──────────────────────────────────────────────────────────────────

    fun get(context: Context, title: String, artist: String): CacheEntry? {
        val cache = loadCache(context)
        return cache[key(title, artist)]
    }

    fun has(context: Context, title: String, artist: String): Boolean {
        val entry = get(context, title, artist) ?: return false
        // Treat failed entries as expired after 7 days so we retry
        if (entry.failed) {
            val sevenDays = 7L * 24 * 60 * 60 * 1000
            return (System.currentTimeMillis() - entry.fetchedAt) < sevenDays
        }
        return true
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun put(
        context    : Context,
        title      : String,
        artist     : String,
        entry      : CacheEntry,
    ) {
        val cache = loadCache(context)
        cache[key(title, artist)] = entry
        saveCache(context, cache)
    }

    fun putFailed(context: Context, title: String, artist: String) {
        put(context, title, artist, CacheEntry(
            artUrl      = null,
            artist      = null,
            album       = null,
            genre       = null,
            releaseYear = null,
            failed      = true,
        ))
    }

    // ── Load from disk ────────────────────────────────────────────────────────

    private fun loadCache(context: Context): MutableMap<String, CacheEntry> {
        memCache?.let { return it }

        val result = mutableMapOf<String, CacheEntry>()
        try {
            val file = cacheFile(context)
            if (!file.exists()) {
                memCache = result
                return result
            }
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val obj = JSONObject(line)
                    val k   = obj.optString("key").takeIf { it.isNotBlank() } ?: return@forEachLine
                    result[k] = CacheEntry(
                        artUrl      = obj.optString("artUrl").takeIf { it.isNotEmpty() },
                        artist      = obj.optString("artist").takeIf { it.isNotEmpty() },
                        album       = obj.optString("album").takeIf { it.isNotEmpty() },
                        genre       = obj.optString("genre").takeIf { it.isNotEmpty() },
                        releaseYear = obj.optString("year").takeIf { it.isNotEmpty() },
                        failed      = obj.optBoolean("failed", false),
                        fetchedAt   = obj.optLong("fetchedAt", System.currentTimeMillis()),
                    )
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        memCache = result
        return result
    }

    // ── Save to disk ──────────────────────────────────────────────────────────

    private fun saveCache(context: Context, cache: Map<String, CacheEntry>) {
        try {
            val file = cacheFile(context)
            file.bufferedWriter().use { writer ->
                cache.forEach { (k, entry) ->
                    val obj = JSONObject().apply {
                        put("key",       k)
                        put("artUrl",    entry.artUrl     ?: "")
                        put("artist",    entry.artist     ?: "")
                        put("album",     entry.album      ?: "")
                        put("genre",     entry.genre      ?: "")
                        put("year",      entry.releaseYear ?: "")
                        put("failed",    entry.failed)
                        put("fetchedAt", entry.fetchedAt)
                    }
                    writer.write(obj.toString())
                    writer.newLine()
                }
            }
        } catch (_: Exception) {}
    }

    private fun cacheFile(context: Context): File =
        File(context.filesDir, CACHE_FILE)

    // ── Convert to RemoteMetadata ─────────────────────────────────────────────

    fun CacheEntry.toRemoteMetadata(): SongRepository.RemoteMetadata =
        SongRepository.RemoteMetadata(
            artUrl      = artUrl,
            artist      = artist,
            album       = album,
            genre       = genre,
            releaseYear = releaseYear,
        )
}