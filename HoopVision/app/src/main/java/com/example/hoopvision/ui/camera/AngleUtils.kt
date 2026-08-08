package com.example.hoopvision.ui.camera

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2

object AngleUtils {
    /**
     * Calculates the 2D angle between three NormalizedLandmarks.
     * @param firstPoint The start point (e.g., shoulder)
     * @param midPoint The vertex point (e.g., elbow)
     * @param lastPoint The end point (e.g., wrist)
     * @return Angle in degrees (0 to 180)
     */
    fun calculateAngle(
        firstPoint: SmoothedLandmark,
        midPoint: SmoothedLandmark,
        lastPoint: SmoothedLandmark
    ): Double {
        val radians = atan2(
            (lastPoint.y() - midPoint.y()).toDouble(),
            (lastPoint.x() - midPoint.x()).toDouble()
        ) - atan2(
            (firstPoint.y() - midPoint.y()).toDouble(),
            (firstPoint.x() - midPoint.x()).toDouble()
        )
        
        var angle = abs(Math.toDegrees(radians))
        if (angle > 180.0) {
            angle = 360.0 - angle
        }
        return angle
    }
}
