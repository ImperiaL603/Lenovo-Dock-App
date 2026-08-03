package com.lenovodock.app

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Silences the music stream while Spotify plays an advertisement and hands the
 * sound back when the song returns. Off by default.
 *
 * Driven from MediaListenerService, not from the Activity: ad breaks happen whether
 * or not the dock is on screen, and the Activity may not exist at the time.
 *
 * NATIVE OWNS THE SETTING, for the same reason the sleep timer does — the service
 * has to read it in a possibly-cold process with no WebView anywhere. It lives in
 * prefs and the page mirrors it.
 *
 * Muting rather than pausing is deliberate: pausing an ad does not skip it, it just
 * stops the clock, so the break would still be waiting when you came back.
 */
object AdMuter {

    private const val PREFS = "lenovodock_audio"
    private const val KEY_ENABLED = "ad_mute_enabled"

    /**
     * Whether WE are the ones currently holding the stream muted, tracked separately
     * from the setting. Without it, the first song after an ad would unmute a tablet
     * the owner had muted by hand — we would be undoing someone else's action.
     */
    private const val KEY_MUTED_BY_US = "ad_mute_active"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        Log.d(TAG, "admute: enabled=$on")
        // Switching it off mid-ad hands the sound back now rather than at the end of
        // the break — otherwise the toggle appears not to work for thirty seconds.
        if (!on) unmute(context)
    }

    /**
     * Called for every snapshot MediaListenerService publishes. A null np means the
     * session has gone, which is also a reason to release the mute: whatever we were
     * silencing is no longer playing.
     */
    fun onNowPlaying(context: Context, np: NowPlaying?) {
        if (isEnabled(context) && np != null && np.isAd) mute(context) else unmute(context)
    }

    /** Both guards are on the stored flag, so the repeated snapshots that arrive
     *  during a single ad cost one prefs read and no AudioManager traffic. */
    private fun mute(context: Context) {
        if (prefs(context).getBoolean(KEY_MUTED_BY_US, false)) return
        if (!adjust(context, AudioManager.ADJUST_MUTE)) return
        prefs(context).edit().putBoolean(KEY_MUTED_BY_US, true).apply()
        Log.d(TAG, "admute: muted for ad")
    }

    private fun unmute(context: Context) {
        if (!prefs(context).getBoolean(KEY_MUTED_BY_US, false)) return
        // Cleared before the call, and regardless of whether it succeeds: a stream we
        // can no longer control must not leave us believing we still hold it, which
        // would block every future mute.
        prefs(context).edit().putBoolean(KEY_MUTED_BY_US, false).apply()
        adjust(context, AudioManager.ADJUST_UNMUTE)
        Log.d(TAG, "admute: unmuted")
    }

    /**
     * ADJUST_MUTE is refused without Do-Not-Disturb access on some builds. This is a
     * MediaTek vendor ROM, so that is a real possibility rather than a hypothetical,
     * and a swallowed exception would be indistinguishable from the feature simply
     * not working. Returns whether the stream actually moved.
     */
    private fun adjust(context: Context, direction: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "admute: stream mute refused — this build wants DND access", e)
            false
        }
    }

    private const val TAG = "LenovoDock" // same tag as the rest, one logcat filter
}
