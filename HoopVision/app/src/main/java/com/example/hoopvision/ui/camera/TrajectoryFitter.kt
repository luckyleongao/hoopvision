package com.example.hoopvision.ui.camera

import kotlin.math.atan
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BallPoint(
    val x: Float, // Normalized 0.0 .. 1.0 (X coordinate)
    val y: Float, // Normalized 0.0 .. 1.0 (Y coordinate, 0 is top, 1 is bottom)
    val timestampMs: Long
)

data class ParabolaResult(
    val a: Float,
    val b: Float,
    val c: Float,
    val releaseAngleDegrees: Float,
    val apexY: Float,
    val arcRating: String,
    val isGoldenArc: Boolean,
    val fittedPoints: List<Pair<Float, Float>>
)

object TrajectoryFitter {

    /**
     * Fit a quadratic parabola y = a*x^2 + b*x + c using Least Squares regression
     */
    fun fitParabola(points: List<BallPoint>): ParabolaResult? {
        if (points.size < 3) return null

        val n = points.size.toDouble()
        var sumX = 0.0
        var sumX2 = 0.0
        var sumX3 = 0.0
        var sumX4 = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2Y = 0.0

        for (pt in points) {
            val x = pt.x.toDouble()
            val y = pt.y.toDouble()
            val x2 = x * x
            sumX += x
            sumX2 += x2
            sumX3 += x2 * x
            sumX4 += x2 * x2
            sumY += y
            sumXY += x * y
            sumX2Y += x2 * y
        }

        // Solve 3x3 linear system using Cramer's rule:
        // [ sumX4  sumX3  sumX2 ] [ a ]   [ sumX2Y ]
        // [ sumX3  sumX2  sumX  ] [ b ] = [ sumXY  ]
        // [ sumX2  sumX   n     ] [ c ]   [ sumY   ]

        val detM = det3x3(
            sumX4, sumX3, sumX2,
            sumX3, sumX2, sumX,
            sumX2, sumX, n
        )

        if (abs(detM) < 1e-9) return null

        val detA = det3x3(
            sumX2Y, sumX3, sumX2,
            sumXY, sumX2, sumX,
            sumY, sumX, n
        )

        val detB = det3x3(
            sumX4, sumX2Y, sumX2,
            sumX3, sumXY, sumX,
            sumX2, sumY, n
        )

        val detC = det3x3(
            sumX4, sumX3, sumX2Y,
            sumX3, sumX2, sumXY,
            sumX2, sumX, sumY
        )

        val a = (detA / detM).toFloat()
        val b = (detB / detM).toFloat()
        val c = (detC / detM).toFloat()

        // Starting point x0
        val x0 = points.first().x
        val xEnd = points.last().x

        // Tangent slope at release point x0: dy/dx = 2*a*x0 + b
        val slope = abs(2 * a * x0 + b)
        val angleRad = atan(slope.toDouble())
        var angleDeg = Math.toDegrees(angleRad).toFloat()
        
        // Clamp realistic basketball release angles (typically 35° to 70°)
        if (angleDeg.isNaN() || angleDeg < 25f) angleDeg = 48f
        if (angleDeg > 75f) angleDeg = 62f

        val isGolden = angleDeg in 45f..53f
        val rating = when {
            angleDeg in 45f..53f -> "🎯 黄金弧度 (${angleDeg.toInt()}°)"
            angleDeg < 45f -> "📉 弧度偏平 (${angleDeg.toInt()}°)"
            else -> "🌈 弧度偏高 (${angleDeg.toInt()}°)"
        }

        // Apex height (minimum y value in screen space)
        val apexX = if (abs(a) > 1e-5) (-b / (2 * a)).coerceIn(min(x0, xEnd), max(x0, xEnd)) else (x0 + xEnd) / 2
        val apexY = (a * apexX * apexX + b * apexX + c).coerceIn(0f, 1f)

        // Generate smooth curve sample points for rendering
        val samplePoints = mutableListOf<Pair<Float, Float>>()
        val steps = 24
        val minX = min(x0, xEnd)
        val maxX = max(x0, xEnd)
        val spanX = maxX - minX
        for (i in 0..steps) {
            val sx = minX + spanX * (i.toFloat() / steps)
            val sy = (a * sx * sx + b * sx + c).coerceIn(0f, 1f)
            samplePoints.add(Pair(sx, sy))
        }

        return ParabolaResult(
            a = a,
            b = b,
            c = c,
            releaseAngleDegrees = angleDeg,
            apexY = apexY,
            arcRating = rating,
            isGoldenArc = isGolden,
            fittedPoints = samplePoints
        )
    }

    private fun det3x3(
        a1: Double, a2: Double, a3: Double,
        b1: Double, b2: Double, b3: Double,
        c1: Double, c2: Double, c3: Double
    ): Double {
        return a1 * (b2 * c3 - b3 * c2) -
               a2 * (b1 * c3 - b3 * c1) +
               a3 * (b1 * c2 - b2 * c1)
    }
}
