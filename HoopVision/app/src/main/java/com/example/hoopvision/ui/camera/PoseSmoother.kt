package com.example.hoopvision.ui.camera

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * A data class representing a 3D landmark that can be smoothed over time.
 */
data class SmoothedLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float = 1.0f
) {
    fun x(): Float = x
    fun y(): Float = y
    fun z(): Float = z
}

/**
 * Applies an Exponential Moving Average (EMA) filter to smooth MediaPipe landmarks
 * to reduce jitter and improve angle calculation stability.
 */
class PoseSmoother(private val alpha: Float = 0.5f) {
    private var previousLandmarks: List<SmoothedLandmark>? = null

    fun smooth(currentLandmarks: List<NormalizedLandmark>): List<SmoothedLandmark> {
        val prev = previousLandmarks
        
        if (prev == null || prev.size != currentLandmarks.size) {
            val newLandmarks = currentLandmarks.map { 
                val vis = if (it.visibility().isPresent) it.visibility().get() else 1.0f
                SmoothedLandmark(it.x(), it.y(), it.z(), vis) 
            }
            previousLandmarks = newLandmarks
            return newLandmarks
        }

        val smoothedLandmarks = mutableListOf<SmoothedLandmark>()
        for (i in currentLandmarks.indices) {
            val curr = currentLandmarks[i]
            val p = prev[i]
            val currVis = if (curr.visibility().isPresent) curr.visibility().get() else 1.0f

            // Normal EMA Filter without distance capping to prevent frozen limb distortion
            val smoothedX = alpha * curr.x() + (1 - alpha) * p.x
            val smoothedY = alpha * curr.y() + (1 - alpha) * p.y
            val smoothedZ = alpha * curr.z() + (1 - alpha) * p.z

            smoothedLandmarks.add(SmoothedLandmark(smoothedX, smoothedY, smoothedZ, currVis))
        }

        previousLandmarks = smoothedLandmarks
        return smoothedLandmarks
    }
    
    fun reset() {
        previousLandmarks = null
    }
}
