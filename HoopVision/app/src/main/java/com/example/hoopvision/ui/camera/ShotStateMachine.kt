package com.example.hoopvision.ui.camera

import com.example.hoopvision.ui.camera.SmoothedLandmark

import com.google.gson.annotations.SerializedName

enum class ShotState {
    IDLE,
    GATHER,      // 蓄力：膝盖弯曲
    SET_POINT,   // 举球：手腕高于肩膀
    RELEASE,     // 出手：手腕高于头顶且手臂伸直
}

data class ShotFrame(
    @SerializedName("timestamp_ms")
    val timestampMs: Long,
    val landmarks: List<SmoothedLandmark>
)

data class ShotAnalysis(
    val state: ShotState = ShotState.IDLE,
    val feedback: String = "请侧身对准镜头",
    val elbowAngle: Double = 180.0,
    val kneeAngle: Double = 180.0,
    val completedShotFrames: List<ShotFrame>? = null,
    val stateJustChangedTo: ShotState? = null
)

class ShotStateMachine {
    private var currentState = ShotState.IDLE
    private var minKneeAngle = 180.0
    private var minElbowAngle = 180.0
    private var currentFeedback = "请侧身对准镜头"
    private val recordedFrames = mutableListOf<ShotFrame>()

    fun processFrame(timestampMs: Long, landmarks: List<SmoothedLandmark>): ShotAnalysis {
        // MediaPipe indices: Nose=0, R_Shoulder=12, R_Elbow=14, R_Wrist=16, R_Hip=24, R_Knee=26, R_Ankle=28
        if (landmarks.size < 29) return ShotAnalysis()

        val rightShoulder = landmarks[12]
        val rightElbow = landmarks[14]
        val rightWrist = landmarks[16]
        val rightHip = landmarks[24]
        val rightKnee = landmarks[26]
        val rightAnkle = landmarks[28]

        val elbowAngle = AngleUtils.calculateAngle(rightShoulder, rightElbow, rightWrist)
        val kneeAngle = AngleUtils.calculateAngle(rightHip, rightKnee, rightAnkle)

        // y goes from 0.0 (top) to 1.0 (bottom)
        val wristHeight = rightWrist.y()
        val shoulderHeight = rightShoulder.y()
        val headHeight = landmarks[0].y()

        var justChangedTo: ShotState? = null

        when (currentState) {
            ShotState.IDLE -> {
                if (kneeAngle < 160.0) {
                    currentState = ShotState.GATHER
                    justChangedTo = ShotState.GATHER
                    minKneeAngle = kneeAngle
                    minElbowAngle = elbowAngle
                    currentFeedback = "蓄力中..."
                    recordedFrames.clear()
                    recordedFrames.add(ShotFrame(timestampMs, landmarks))
                } else {
                    currentFeedback = "准备投篮 (请弯曲膝盖)"
                }
            }
            ShotState.GATHER -> {
                recordedFrames.add(ShotFrame(timestampMs, landmarks))
                if (kneeAngle < minKneeAngle) minKneeAngle = kneeAngle
                if (elbowAngle < minElbowAngle) minElbowAngle = elbowAngle

                if (wristHeight < shoulderHeight) {
                    currentState = ShotState.SET_POINT
                    justChangedTo = ShotState.SET_POINT
                    currentFeedback = "举球..."
                } else if (kneeAngle > 170.0) {
                    currentState = ShotState.IDLE // 假动作或放弃
                    recordedFrames.clear()
                }
            }
            ShotState.SET_POINT -> {
                recordedFrames.add(ShotFrame(timestampMs, landmarks))
                if (kneeAngle < minKneeAngle) minKneeAngle = kneeAngle
                if (elbowAngle < minElbowAngle) minElbowAngle = elbowAngle

                if (wristHeight < headHeight && elbowAngle > 130.0) {
                    currentState = ShotState.RELEASE
                    justChangedTo = ShotState.RELEASE
                    evaluateShot()
                    val completedFrames = recordedFrames.toList()
                    return ShotAnalysis(currentState, currentFeedback, elbowAngle, kneeAngle, completedFrames, justChangedTo)
                } else if (wristHeight > shoulderHeight && kneeAngle > 160.0) {
                    currentState = ShotState.IDLE // 放弃投篮
                    recordedFrames.clear()
                }
            }
            ShotState.RELEASE -> {
                if (elbowAngle < 100.0 || wristHeight > shoulderHeight) {
                    // 手臂放下了，重置状态机准备下一次投篮
                    currentState = ShotState.IDLE
                    minKneeAngle = 180.0
                    minElbowAngle = 180.0
                    recordedFrames.clear()
                }
            }
        }

        return ShotAnalysis(currentState, currentFeedback, elbowAngle, kneeAngle, null, justChangedTo)
    }

    private fun evaluateShot() {
        val feedbacks = mutableListOf<String>()
        
        // 评估下肢发力：膝盖弯曲度
        if (minKneeAngle > 135.0) {
            feedbacks.add("膝盖弯曲不足(${minKneeAngle.toInt()}°)，需更多下肢力量")
        } else if (minKneeAngle < 90.0) {
            feedbacks.add("膝盖过度弯曲，影响发力连贯性")
        }

        // 评估上肢结构：手肘夹角（正常在90度左右为完美的L型）
        if (minElbowAngle < 60.0) {
            feedbacks.add("举球时手肘夹角太小(${minElbowAngle.toInt()}°)，发力容易受阻")
        } else if (minElbowAngle > 120.0) {
            feedbacks.add("举球手肘没有形成托球结构")
        }

        if (feedbacks.isEmpty()) {
            currentFeedback = "完美投篮！动作标准！"
        } else {
            currentFeedback = feedbacks.joinToString("\n")
        }
    }
}
