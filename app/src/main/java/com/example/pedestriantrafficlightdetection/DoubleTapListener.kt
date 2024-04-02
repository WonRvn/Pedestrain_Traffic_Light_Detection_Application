package com.example.pedestriantrafficlightdetection

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.MotionEvent

class DoubleTapListener(
    context: Context,
    private val onDoubleTap: () -> Unit
) : GestureDetector.SimpleOnGestureListener() {

    private val gestureDetector = GestureDetector(context, this)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        vibratePhone(200)
        onDoubleTap.invoke()
        return super.onDoubleTap(e)
    }

    private fun vibratePhone(amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        // Define a pattern for two vibrations with a pause in between
        val pattern = longArrayOf(0, 200, 50, 200) // Start immediately, vibrate for 50 ms, pause for 100 ms, vibrate for 50 ms

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android Oreo and above, use createWaveform with custom amplitudes
            // The amplitude array must match the pattern: 0 (pause), amplitude (vibrate), 0 (pause), amplitude (vibrate)
            val validAmplitude = amplitude.coerceIn(1, 255) // Ensure amplitude is within valid range
            val amplitudes = intArrayOf(0, validAmplitude, 0, validAmplitude) // Apply custom amplitude to vibration segments
            val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1) // -1 means do not repeat
            vibrator.vibrate(effect)
        } else {
            // For older devices, fallback to using the pattern without amplitude control
            vibrator.vibrate(pattern, -1) // -1 means do not repeat the pattern
        }
    }

}
