package com.lenovodock.app

import android.util.Log

/**
 * Tidies the title and artist of tracks from players whose metadata is not already
 * clean, so the lyrics lookup has a fair chance of matching. Not used for Spotify,
 * whose " - Slowed & Reverb" style titles are real version labels that
 * LyricsProviders.editMatch relies on.
 *
 * Deliberately conservative: a wrong split or a stripped real title word costs a
 * missed lookup at worst, and the providers' duration bounds still stop a wrong
 * song from being accepted.
 */
object TitleCleaner {

    private const val TAG = "LenovoDock"

    // Offline files with no tags often surface the filename as the title.
    private val FILE_EXT = Regex("""(?i)\.(mp3|flac|m4a|aac|wav|ogg|opus|wma)$""")
    // "01 - Song". Needs a space after the separator so "1-800-273-8255" survives.
    private val TRACK_NO = Regex("""^\d{1,3}\s*[-._)]\s+""")
    // "(Official Video)", "[Lyrics]", "(Official Music Video)", "(Audio)", "[HD]"...
    private val NOISE_BRACKET = Regex(
        """(?i)\s*[(\[]\s*(?:official\b[^)\]]*|lyrics?\b[^)\]]*|audio|video|music video|visuali[sz]er|hd|hq|4k|mv)\s*[)\]]"""
    )
    // "Song - Official Video", "Song | Lyrics"
    private val NOISE_DASH = Regex("""(?i)\s[-–|]\s*(?:official\b.*|lyrics?\b.*|audio|video|music video)\s*$""")
    // YouTube channel naming: "Artist - Topic", "ArtistVEVO"
    private val CHANNEL_SUFFIX = Regex("""(?i)(?:\s*-\s*topic|\s*vevo)\s*$""")
    private val SPLIT = Regex("""\s[-–]\s""")
    // A right-hand part like these is a version label, not a song name.
    private val VERSION_WORDS = Regex(
        """(?i)\b(slowed|sped\s*up|nightcore|reverb|remix|live|acoustic|instrumental|remaster(ed)?|version|edit|mix|cover)\b"""
    )

    fun clean(rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = rawTitle.trim()
        var artist = rawArtist.trim()

        val fromChannel = CHANNEL_SUFFIX.containsMatchIn(artist)
        artist = CHANNEL_SUFFIX.replace(artist, "").trim()

        title = FILE_EXT.replace(title, "")
        title = TRACK_NO.replace(title, "")
        if (!title.contains(' ') && title.contains('_')) title = title.replace('_', ' ')
        title = NOISE_BRACKET.replace(title, "")
        title = NOISE_DASH.replace(title, "").trim()

        // "Artist - Song": split when the artist is missing, came from a channel name,
        // or is the same name as the left-hand part.
        val parts = SPLIT.split(title, 2)
        if (parts.size == 2) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (left.isNotEmpty() && right.isNotEmpty() && !VERSION_WORDS.containsMatchIn(right)) {
                when {
                    artist.isEmpty() || fromChannel -> { artist = left; title = right }
                    sameName(artist, left) -> title = right
                }
            }
        }

        if (title != rawTitle.trim() || artist != rawArtist.trim()) {
            Log.d(TAG, "clean: '$rawTitle' / '$rawArtist' -> '$title' / '$artist'")
        }
        return title to artist
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = a.lowercase().filter { it.isLetterOrDigit() }
        val y = b.lowercase().filter { it.isLetterOrDigit() }
        return x.length >= 2 && y.length >= 2 && (x.contains(y) || y.contains(x))
    }
}
