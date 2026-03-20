package com.audiomesh.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

// ── Data model ────────────────────────────────────────────────────────────────

data class Song(
    val id            : Long,
    val title         : String,
    val artist        : String,
    val album         : String,
    val durationMs    : Long,
    val uri           : Uri,
    val albumArtUri   : Uri?,
    val remoteArtUrl  : String? = null,
    val remoteArtist  : String? = null,
    val remoteAlbum   : String? = null,
    val genre         : String? = null,
    val releaseYear   : String? = null,
)

// ── Repository ────────────────────────────────────────────────────────────────

object SongRepository {

    private val EXCLUDED_FOLDERS = listOf(
        "recordings", "record", "voice", "voicememo", "voicenote",
        "call", "calls", "telegram", "whatsapp", "dcim",
        "soundrecorder", "miuirecorder", "callrecording",
    )

    suspend fun loadSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
        )

        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
                    " AND ${MediaStore.Audio.Media.DURATION} >= 60000" +
                    " AND (${MediaStore.Audio.Media.MIME_TYPE} = 'audio/mpeg'" +
                    " OR ${MediaStore.Audio.Media.MIME_TYPE} = 'audio/mp4'" +
                    " OR ${MediaStore.Audio.Media.MIME_TYPE} = 'audio/aac'" +
                    " OR ${MediaStore.Audio.Media.MIME_TYPE} = 'audio/x-m4a')"

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null, sortOrder,
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val filePath = cursor.getString(dataCol)?.lowercase() ?: ""
                if (EXCLUDED_FOLDERS.any { folder -> filePath.contains("/$folder/") }) continue

                val id      = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId)

                val rawTitle  = cursor.getString(titleCol) ?: ""
                val rawArtist = cursor.getString(artistCol) ?: ""
                val cleanedTitle  = cleanTitle(rawTitle)
                val cleanedArtist = cleanArtist(rawArtist)

                songs.add(Song(
                    id         = id,
                    title      = cleanedTitle.ifBlank { "Unknown" },
                    artist     = cleanedArtist.ifBlank { "Unknown Artist" },
                    album      = cursor.getString(albumCol) ?: "Unknown Album",
                    durationMs = cursor.getLong(durationCol),
                    uri        = contentUri,
                    albumArtUri = albumArtUri,
                ))
            }
        }
        songs
    }

    // ── Title cleaning ────────────────────────────────────────────────────────

    fun cleanTitle(raw: String): String {
        var s = raw.trim()

        s = s.replace(Regex("""\.(?:mp3|m4a|aac|flac|ogg|wav)$""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""^\d{1,3}[\s._\-]+"""), "")
        s = s.replace(Regex("""\[(?:official|lyrics?|video|audio|hq|hd|4k|ncs|320|128|release|music)[^\]]*\]""",
            RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\((?:official|lyrics?|video|audio|hq|hd|4k|ncs|320|128|release|music)[^)]*\)""",
            RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\b\d{2,3}\s*kbps\b""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\b\d{2,3}k\b""", RegexOption.IGNORE_CASE), "")

        val dashSplit = s.split(Regex("""\s+[-–—_]+\s+"""))
        if (dashSplit.size >= 2) s = dashSplit.last().trim()

        s = s.replace('_', ' ').replace('.', ' ')
        s = s.replace(Regex("""\s{2,}"""), " ").trim()

        if (s == s.lowercase() || s == s.uppercase()) {
            s = s.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }

        return s.trim()
    }

    private fun cleanArtist(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.equals("<unknown>", ignoreCase = true)) return ""
        if (raw.equals("unknown artist", ignoreCase = true)) return ""
        return raw.replace(Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""",
            RegexOption.IGNORE_CASE), "").trim()
    }

    // ── Remote metadata ───────────────────────────────────────────────────────

    data class RemoteMetadata(
        val artUrl     : String?,
        val artist     : String?,
        val album      : String?,
        val genre      : String?,
        val releaseYear: String?,
    )

    suspend fun fetchRemoteMetadata(title: String, artist: String): RemoteMetadata? =
        withContext(Dispatchers.IO) {
            try {
                val cleanT = cleanTitle(title)
                val cleanA = cleanArtist(artist)

                // Strategy 1: iTunes title + artist
                val r1 = if (cleanA.isNotBlank()) searchItunes(cleanT, cleanA) else null
                // Strategy 2: Deezer title + artist
                val r2 = r1 ?: if (cleanA.isNotBlank()) searchDeezer(cleanT, cleanA) else null
                // Strategy 3: iTunes title only
                val r3 = r2 ?: searchItunes(cleanT, "")
                // Strategy 4: Deezer title only
                val r4 = r3 ?: searchDeezer(cleanT, "")
                // Strategy 5: MusicBrainz
                val r5 = r4 ?: searchMusicBrainz(cleanT, cleanA)
                // Strategy 6: raw title last resort
                val result = r5 ?: searchItunes(title, artist)

                result
            } catch (_: Exception) { null }
        }

    // ── iTunes ────────────────────────────────────────────────────────────────

    private fun searchItunes(title: String, artist: String): RemoteMetadata? {
        if (title.isBlank()) return null
        return try {
            val query   = if (artist.isNotBlank()) "$title $artist" else title
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url     = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=5"
            val json    = URL(url).readText()
            val results = JSONObject(json).getJSONArray("results")
            if (results.length() == 0) return null

            var bestItem  = results.getJSONObject(0)
            var bestScore = 0
            for (i in 0 until results.length()) {
                val item  = results.getJSONObject(i)
                val score = similarityScore(title, item.optString("trackName", ""))
                if (score > bestScore) { bestScore = score; bestItem = item }
            }
            // Reject if no meaningful match — forces fallback to next API
            if (bestScore == 0) return null


            val artUrl = bestItem.optString("artworkUrl100")
                .takeIf { it.isNotEmpty() }
                ?.replace("100x100bb", "600x600bb")

            val releaseYear = bestItem.optString("releaseDate")
                .takeIf { it.length >= 4 }?.substring(0, 4)

            RemoteMetadata(
                artUrl      = artUrl,
                artist      = bestItem.optString("artistName").takeIf { it.isNotEmpty() },
                album       = bestItem.optString("collectionName").takeIf { it.isNotEmpty() },
                genre       = bestItem.optString("primaryGenreName").takeIf { it.isNotEmpty() },
                releaseYear = releaseYear,
            )
        } catch (_: Exception) { null }
    }

    // ── Deezer ────────────────────────────────────────────────────────────────

    private fun searchDeezer(title: String, artist: String): RemoteMetadata? {
        if (title.isBlank()) return null
        return try {
            val query   = if (artist.isNotBlank()) "$title $artist" else title
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url     = "https://api.deezer.com/search?q=$encoded&limit=5"

            val connection = java.net.URL(url).openConnection()
            connection.setRequestProperty("User-Agent", "AudioMesh/1.0")
            val json = connection.getInputStream().bufferedReader().readText()

            val data = JSONObject(json).optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            var bestItem  = data.getJSONObject(0)
            var bestScore = 0
            for (i in 0 until data.length()) {
                val item  = data.getJSONObject(i)
                val score = similarityScore(title, item.optString("title", ""))
                if (score > bestScore) { bestScore = score; bestItem = item }
            }
            // Reject if no meaningful match
            if (bestScore == 0) return null

            val artUrl = bestItem.optJSONObject("album")
                ?.optString("cover_xl")
                ?.takeIf { it.isNotEmpty() }

            val artistName = bestItem.optJSONObject("artist")
                ?.optString("name")
                ?.takeIf { it.isNotEmpty() }

            val albumName = bestItem.optJSONObject("album")
                ?.optString("title")
                ?.takeIf { it.isNotEmpty() }

            RemoteMetadata(
                artUrl      = artUrl,
                artist      = artistName,
                album       = albumName,
                genre       = null,
                releaseYear = null,
            )
        } catch (_: Exception) { null }
    }

    // ── MusicBrainz ───────────────────────────────────────────────────────────

    private fun searchMusicBrainz(title: String, artist: String): RemoteMetadata? {
        if (title.isBlank()) return null
        return try {
            val query = if (artist.isNotBlank())
                URLEncoder.encode("recording:\"$title\" AND artist:\"$artist\"", "UTF-8")
            else
                URLEncoder.encode("recording:\"$title\"", "UTF-8")

            val url        = "https://musicbrainz.org/ws/2/recording?query=$query&limit=1&fmt=json"
            val connection = java.net.URL(url).openConnection()
            connection.setRequestProperty("User-Agent", "AudioMesh/1.0 (github.com/audiomesh)")
            val json = connection.getInputStream().bufferedReader().readText()

            val results = JSONObject(json).optJSONArray("recordings") ?: return null
            if (results.length() == 0) return null

            val recording  = results.getJSONObject(0)
            val artistName = recording.optJSONArray("artist-credit")
                ?.getJSONObject(0)?.optJSONObject("artist")?.optString("name")
            val releaseList = recording.optJSONArray("releases")
            val album       = releaseList?.getJSONObject(0)?.optString("title")
            val releaseId   = releaseList?.getJSONObject(0)?.optString("id")

            val artUrl = if (!releaseId.isNullOrBlank())
                "https://coverartarchive.org/release/$releaseId/front-500"
            else null

            RemoteMetadata(
                artUrl      = artUrl,
                artist      = artistName,
                album       = album,
                genre       = null,
                releaseYear = null,
            )
        } catch (_: Exception) { null }
    }

    // ── Similarity scoring ────────────────────────────────────────────────────

    // REPLACE the entire similarityScore function with this
    private fun similarityScore(a: String, b: String): Int {
        val n1 = a.lowercase().trim()
        val n2 = b.lowercase().trim()

        // Strip common prefixes like "the ", "a ", "an "
        val strip = { s: String -> s.removePrefix("the ").removePrefix("a ").removePrefix("an ").trim() }
        val s1 = strip(n1)
        val s2 = strip(n2)

        return when {
            n1 == n2                   -> 100  // exact match
            s1 == s2                   -> 95   // match after stripping "the"
            n2.contains(n1)            -> 80   // b contains a
            n1.contains(n2)            -> 75   // a contains b
            s2.contains(s1)            -> 70   // stripped b contains stripped a
            s1.contains(s2)            -> 65   // stripped a contains stripped b
            n2.startsWith(n1.take(6))  -> 50   // same start (6 chars)
            s2.startsWith(s1.take(6))  -> 45   // stripped same start
            else                       -> 0
        }
    }

    // ── Fallback colour ───────────────────────────────────────────────────────

    fun fallbackColor(title: String): Long {
        val palette = listOf(
            0xFF3B0764L, 0xFF431407L, 0xFF052E16L, 0xFF1E3A5FL,
            0xFF1A1035L, 0xFF2D1B00L, 0xFF1A0A2EL,
        )
        return palette[Math.abs(title.hashCode()) % palette.size]
    }

    // ── Format ms → "m:ss" ───────────────────────────────────────────────────

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}