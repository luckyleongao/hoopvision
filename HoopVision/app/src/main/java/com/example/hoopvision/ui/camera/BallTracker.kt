package com.example.hoopvision.ui.camera

import kotlin.math.abs

class BallTracker {
    private val trajectory = mutableListOf<BallPoint>()
    var currentBallPoint: BallPoint? = null
        private set
    var latestParabola: ParabolaResult? = null
        private set

    private var isTrackingFlight = false
    private var lastWristX = 0f
    private var lastWristY = 0f
    private var lastTimestamp = 0L

    private var vx = 0f
    private var vy = 0f

    fun reset() {
        trajectory.clear()
        currentBallPoint = null
        latestParabola = null
        isTrackingFlight = false
        lastWristX = 0f
        lastWristY = 0f
        lastTimestamp = 0L
        vx = 0f
        vy = 0f
    }

    fun processFrame(
        timestampMs: Long,
        state: ShotState,
        landmarks: List<SmoothedLandmark>?
    ): ParabolaResult? {
        if (landmarks == null || landmarks.size < 17) {
            return latestParabola
        }

        // Right wrist (landmark 16) and right elbow (landmark 14)
        val wrist = landmarks[16]
        val elbow = landmarks[14]

        when (state) {
            ShotState.IDLE -> {
                if (trajectory.isNotEmpty()) {
                    reset()
                }
            }

            ShotState.GATHER -> {
                reset()
                // Ball in hands near abdomen / chest
                val ballX = (wrist.x() + elbow.x()) / 2f
                val ballY = wrist.y() - 0.03f
                currentBallPoint = BallPoint(ballX, ballY, timestampMs)
                lastWristX = wrist.x()
                lastWristY = wrist.y()
                lastTimestamp = timestampMs
            }

            ShotState.SET_POINT -> {
                // Ball at set-point above head / forehead
                val ballX = wrist.x() + 0.02f
                val ballY = wrist.y() - 0.05f
                currentBallPoint = BallPoint(ballX, ballY, timestampMs)

                if (lastTimestamp > 0 && timestampMs > lastTimestamp) {
                    val dt = (timestampMs - lastTimestamp) / 1000f
                    if (dt in 0.01f..0.2f) {
                        // Calculate upward and forward velocity of the release
                        vx = (wrist.x() - lastWristX) / dt
                        vy = (wrist.y() - lastWristY) / dt
                    }
                }

                lastWristX = wrist.x()
                lastWristY = wrist.y()
                lastTimestamp = timestampMs
            }

            ShotState.RELEASE -> {
                if (!isTrackingFlight) {
                    isTrackingFlight = true
                    trajectory.clear()

                    // Ensure initial upward velocity (in screen space, -vy means moving up)
                    if (vy >= -0.2f) {
                        vy = -1.15f // Initial upward push velocity
                    }
                    if (abs(vx) < 0.1f) {
                        vx = 0.65f // Forward propulsion toward basket
                    }

                    // Initial release point from fingertips
                    val initX = wrist.x() + 0.03f
                    val initY = wrist.y() - 0.06f
                    val initPoint = BallPoint(initX, initY, timestampMs)
                    trajectory.add(initPoint)
                    currentBallPoint = initPoint
                } else {
                    // Continue flight simulation / physical ballistic tracking
                    val prev = trajectory.lastOrNull()
                    if (prev != null) {
                        val dt = 0.033f // ~30 FPS frame delta
                        val gravity = 2.4f // Gravity constant in normalized screen space

                        vy += gravity * dt
                        val nextX = (prev.x + vx * dt).coerceIn(0f, 1f)
                        val nextY = (prev.y + vy * dt).coerceIn(0f, 1f)

                        val nextPoint = BallPoint(nextX, nextY, timestampMs)
                        trajectory.add(nextPoint)
                        currentBallPoint = nextPoint

                        // Fit parabola when we have sufficient points
                        if (trajectory.size >= 4) {
                            val parabola = TrajectoryFitter.fitParabola(trajectory)
                            if (parabola != null) {
                                latestParabola = parabola
                            }
                        }

                        // Stop flight when ball drops off bottom or exits frame
                        if (nextY >= 0.85f || nextX >= 0.98f || trajectory.size > 30) {
                            isTrackingFlight = false
                        }
                    }
                }
            }
        }

        return latestParabola
    }

    fun getTrajectoryPoints(): List<BallPoint> = trajectory.toList()
}
