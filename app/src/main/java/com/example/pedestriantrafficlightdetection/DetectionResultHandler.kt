package com.example.pedestriantrafficlightdetection

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.ImageView

class DetectionResultHandler(private val activity: Activity, private val imageView: ImageView) {

    fun updateImageViewBasedOnDetection(detectionResult: String) {
        val imageResId = when (detectionResult) {
            "green" -> R.drawable.green_light // Ensure this drawable exists
            "red" -> R.drawable.red_light // Ensure this drawable exists
            else -> R.drawable.ic_camera // Default camera icon or similar
        }

        // Update ImageView on the UI thread
        activity.runOnUiThread {
            imageView.setImageResource(imageResId)
        }

        // Reset to default icon after 5 seconds if detection is green or red
        if (detectionResult == "green" || detectionResult == "red") {
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                activity.runOnUiThread {
                    imageView.setImageResource(R.drawable.ic_camera)
                }
            }, 3500) // Delay in milliseconds
        }
    }
}
