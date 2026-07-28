package com.lenovodock.app

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import kotlin.math.abs
import kotlin.math.ln

/**
 * Dims the dock to match the room, driven by the tablet's ambient light sensor.
 *
 * Brightness is set on the Activity's window rather than in system settings: that
 * needs no permission, is released the moment the app stops, and a window carrying
 * a brightness override outranks the platform's own auto-brightness while focused,
 * so the two never fight over the panel.
 *
 * Doubles as the `AndroidDisplay` JS bridge. Only @JavascriptInterface methods are
 * reachable from the page, and the settings panel has nothing to say to the screen
 * beyond the one preference, so a separate bridge class would be a file of one line.
 */
class AutoDim(private val activity: Activity) : SensorEventListener {

    private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val light: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var enabled = false
    private var floor = DEFAULT_FLOOR
    private var smoothedLux = UNSET
    private var applied = UNSET

    // A dim waiting out DIM_HOLD_MS. Sensor events and this handler are both on the
    // main thread, so neither needs guarding.
    private val handler = Handler(Looper.getMainLooper())
    private var pendingDim = UNSET
    private val applyPendingDim = Runnable {
        if (pendingDim >= 0f) {
            apply(pendingDim)
            pendingDim = UNSET
        }
    }

    /** Listening is pure cost while the dock is behind another app. */
    fun onResume() {
        if (enabled) register()
    }

    fun onPause() {
        sensors.unregisterListener(this)
        // Otherwise a dim scheduled just before backgrounding lands on a window that
        // is no longer the one being looked at.
        cancelPendingDim()
    }

    /**
     * Called by the settings panel on load and on every change, carrying both fields
     * together so there is no window where one has been applied and the other hasn't.
     * Arrives on the WebView's JS-bridge thread, so it hops to main before touching
     * the window — the same reason the transport commands do.
     */
    @JavascriptInterface
    fun setAutoDim(on: Boolean, floorPercent: Int) {
        activity.runOnUiThread {
            enabled = on
            floor = (floorPercent / 100f).coerceIn(MIN_FLOOR, 1f)
            // Both are re-seeded so a floor change takes effect on the next reading
            // instead of being swallowed by the no-op threshold below.
            smoothedLux = UNSET
            applied = UNSET
            cancelPendingDim()
            if (on) register() else release()
            Log.d(TAG, "auto-dim: enabled=$on floor=$floor sensor=${light != null}")
        }
    }

    /**
     * Brightening and dimming are deliberately asymmetric. A shadow crossing the
     * sensor and a room that has actually gone dark are identical at the instant
     * they happen — only persistence tells them apart — so a drop has to hold for
     * DIM_HOLD_MS before it reaches the screen. Brightening applies at once: a light
     * being switched on is never accidental, and a screen that is too dark to read
     * is the one failure worth fixing immediately.
     */
    override fun onSensorChanged(e: SensorEvent) {
        if (!enabled) return
        val lux = e.values[0]
        // Seeded from the first reading rather than 0, so the screen settles at the
        // room's level instead of ramping up from black.
        smoothedLux = if (smoothedLux < 0f) lux else smoothedLux + SMOOTHING * (lux - smoothedLux)
        val target = brightnessFor(smoothedLux)

        if (applied < 0f) { // first reading: nothing to debounce against yet
            cancelPendingDim()
            apply(target)
            return
        }
        // Back to roughly where we already are — including a shadow that has passed,
        // which is what cancels the dim it started.
        if (abs(target - applied) < MIN_STEP) {
            cancelPendingDim()
            return
        }
        if (target > applied) {
            cancelPendingDim()
            apply(target)
            return
        }
        // The deadline belongs to the first dim-ward reading, and later ones only
        // refresh the value. Restarting the clock each time would let a room that
        // darkens gradually postpone the dim indefinitely.
        val alreadyWaiting = pendingDim >= 0f
        pendingDim = target
        if (!alreadyWaiting) handler.postDelayed(applyPendingDim, DIM_HOLD_MS)
    }

    private fun cancelPendingDim() {
        if (pendingDim < 0f) return
        pendingDim = UNSET
        handler.removeCallbacks(applyPendingDim)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun register() {
        if (light == null) {
            Log.d(TAG, "auto-dim: device has no light sensor")
            return
        }
        sensors.unregisterListener(this) // setAutoDim can arrive while already listening
        sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** Hands the panel back to the platform's own brightness handling. */
    private fun release() {
        sensors.unregisterListener(this)
        apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    /**
     * Lux is perceived logarithmically — 10 to 100 is a far bigger change than 10000
     * to 10090 — so the curve interpolates on ln(). Below LUX_DARK the room is dark
     * enough that the floor is right; above LUX_BRIGHT the panel has nothing left to
     * give and more lux buys nothing.
     */
    private fun brightnessFor(lux: Float): Float {
        val t = ((ln(lux + 1f) - LN_DARK) / (LN_BRIGHT - LN_DARK)).coerceIn(0f, 1f)
        return floor + (1f - floor) * t
    }

    private fun apply(value: Float) {
        applied = value
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
        // The lux thresholds below are estimates until they meet a real room, and
        // MIN_STEP keeps this to a handful of lines an hour, so it is worth logging
        // the pair that tuning them depends on.
        Log.d(TAG, "auto-dim: lux=${smoothedLux.toInt()} -> brightness=${"%.2f".format(value)}")
    }

    companion object {
        const val NAME = "AndroidDisplay"
        private const val TAG = "LenovoDock" // one logcat filter for the whole app

        private const val UNSET = -1f
        private const val DEFAULT_FLOOR = 0.15f
        private const val MIN_FLOOR = 0.02f // below this the panel reads as off
        private const val SMOOTHING = 0.2f  // weight of each new reading in the average
        private const val MIN_STEP = 0.04f  // smallest change worth repainting for
        // How long the room must stay darker before the screen follows it down.
        // Raise this if a shadow still gets through; it costs nothing but lag on a
        // genuine change, and none at all on brightening.
        private const val DIM_HOLD_MS = 5_000L

        private const val LUX_DARK = 8f     // unlit room at night
        private const val LUX_BRIGHT = 600f // ordinary lit room; daylight is far above
        private val LN_DARK = ln(LUX_DARK + 1f)
        private val LN_BRIGHT = ln(LUX_BRIGHT + 1f)
    }
}
