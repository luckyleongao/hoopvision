package com.example.hoopvision.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseHelper(
    val context: Context,
    val poseLandmarkerListener: PoseLandmarkerListener? = null
) {
    private var poseLandmarker: PoseLandmarker? = null

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")

        val baseOptions = baseOptionsBuilder.build()
        
        val optionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinPoseDetectionConfidence(0.75f)
            .setMinTrackingConfidence(0.75f)
            .setMinPosePresenceConfidence(0.75f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)

        try {
            poseLandmarker = PoseLandmarker.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            e.printStackTrace()
            poseLandmarkerListener?.onError("MediaPipe failed to initialize.")
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val frameTime = SystemClock.uptimeMillis()

        val bitmap = imageProxy.toBitmap()
        
        val matrix = android.graphics.Matrix()
        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        // Save latest rotated bitmap for keyframe extraction
        latestRotatedBitmap = rotatedBitmap

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        
        detectAsync(mpImage, frameTime)
    }

    @Volatile
    private var latestRotatedBitmap: Bitmap? = null

    /**
     * Captures and compresses the latest frame as a low-resolution Base64 JPEG
     * for multimodal LLM analysis.
     */
    fun getLatestFrameBase64(maxDimension: Int = 480, quality: Int = 50): String? {
        val bitmap = latestRotatedBitmap ?: return null
        return try {
            val maxSide = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
            val scale = if (maxSide > maxDimension) maxDimension.toFloat() / maxSide else 1.0f
            
            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val byteArray = outputStream.toByteArray()
            android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun detectAsync(mpImage: MPImage, frameTime: Long) {
        poseLandmarker?.detectAsync(mpImage, frameTime)
    }

    private fun returnLivestreamResult(result: PoseLandmarkerResult, input: MPImage) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        poseLandmarkerListener?.onResults(
            ResultBundle(
                result,
                inferenceTime,
                input.height,
                input.width
            ),
            this
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        poseLandmarkerListener?.onError(error.message ?: "An unknown error has occurred")
    }

    fun clearPoseLandmarker() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    data class ResultBundle(
        val results: PoseLandmarkerResult,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int
    )

    interface PoseLandmarkerListener {
        fun onError(error: String)
        fun onResults(resultBundle: ResultBundle, helper: PoseHelper)
    }
}
