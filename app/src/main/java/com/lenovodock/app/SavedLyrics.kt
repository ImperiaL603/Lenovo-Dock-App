package com.lenovodock.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Lyrics the owner has chosen to keep, one JSON file per song in the app's private
 * storage. No permission is needed, and the folder goes with the app if it is
 * uninstalled.
 *
 * The FILE IS THE TRUTH about what is saved. Nothing here is mirrored in memory, so
 * the page, the repository and the Settings list cannot disagree with each other:
 * they all ask this object.
 *
 * Keyed the same way LyricsRepository keys its cache ("artist|title|album" after
 * cleaning), so a song saved from one play is found again on the next, offline or
 * not. The key is hashed for the filename; the readable title and artist are stored
 * inside the file for the Settings list.
 */
object SavedLyrics {

    private const val TAG = "LenovoDock"
    private const val SUBDIR = "saved_lyrics"
    private const val EXT = ".json"

    /** What a delete request is allowed to name. Anything else is not one of our files. */
    private val ID_RE = Regex("^[0-9a-f]{16}$")

    /** What the Settings list needs — not the lines themselves, which can be long. */
    data class Entry(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val synced: Boolean,
        val lines: Int,
        val savedAt: Long,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, SUBDIR).also { if (!it.exists()) it.mkdirs() }

    private fun idFor(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun fileFor(context: Context, id: String) = File(dir(context), id + EXT)

    fun exists(context: Context, key: String): Boolean = fileFor(context, idFor(key)).isFile

    /** Overwrites any earlier copy of the same song. Returns whether it was written. */
    fun save(
        context: Context,
        key: String,
        title: String,
        artist: String,
        album: String,
        lyrics: LyricsRepository.Lyrics,
    ): Boolean {
        if (lyrics.lines.isEmpty()) return false
        return try {
            val lines = JSONArray()
            lyrics.lines.forEach {
                lines.put(JSONObject().put("t", it.timeMs).put("text", it.text))
            }
            val body = JSONObject()
                .put("v", 1)
                .put("key", key)
                .put("title", title)
                .put("artist", artist)
                .put("album", album)
                .put("synced", lyrics.synced)
                .put("savedAt", System.currentTimeMillis())
                .put("lines", lines)
            val target = fileFor(context, idFor(key))
            // Written beside the target and renamed in, so a crash mid-write can never
            // leave a half-file that loads as a broken song.
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body.toString())
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            Log.d(TAG, "lyrics: saved '$title' / '$artist' (${lyrics.lines.size} lines)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "lyrics: save failed", e)
            false
        }
    }

    /** Null when nothing is saved for this key, or the file cannot be read. */
    fun load(context: Context, key: String): LyricsRepository.Lyrics? {
        val file = fileFor(context, idFor(key))
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("lines")
            val lines = (0 until arr.length()).map {
                val l = arr.getJSONObject(it)
                LyricsRepository.Line(l.optLong("t"), l.optString("text", ""))
            }
            if (lines.isEmpty()) null else LyricsRepository.Lyrics(lines, o.optBoolean("synced", true))
        } catch (e: Exception) {
            Log.w(TAG, "lyrics: unreadable saved file ${file.name}", e)
            null
        }
    }

    /** Sorted by title then artist, case-insensitively. Unreadable files are skipped. */
    fun list(context: Context): List<Entry> =
        (dir(context).listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: emptyArray())
            .mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText())
                    Entry(
                        id = f.name.removeSuffix(EXT),
                        title = o.optString("title", ""),
                        artist = o.optString("artist", ""),
                        album = o.optString("album", ""),
                        synced = o.optBoolean("synced", true),
                        lines = o.optJSONArray("lines")?.length() ?: 0,
                        savedAt = o.optLong("savedAt"),
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))

    /** Returns how many were actually removed. */
    fun delete(context: Context, ids: List<String>): Int =
        ids.count { id ->
            // The id comes from the page. Only a bare 16-character hash is a file in this
            // folder; anything with a separator or a dot in it is never resolved.
            if (!ID_RE.matches(id)) {
                Log.w(TAG, "lyrics: refused suspicious id '$id'")
                false
            } else {
                fileFor(context, id).delete()
            }
        }

    fun deleteAll(context: Context): Int =
        (dir(context).listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: emptyArray())
            .count { it.delete() }
}
