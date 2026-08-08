package com.example.hoopvision.network

import com.example.hoopvision.ui.camera.ShotFrame
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class AnalysisRequest(
    @SerializedName("template_id")
    val template_id: String = "standard_shot",
    @SerializedName("frames")
    val frames: List<ShotFrame>,
    @SerializedName("keyframe_images_base64")
    val keyframe_images_base64: List<String> = emptyList()
)

data class AnalysisResponse(
    val status: String,
    val dtw_distance: Float,
    val min_elbow_angle: Float,
    val min_knee_angle: Float,
    val feedback: String
)

interface HoopVisionApiService {
    @POST("/api/v1/analyze_shot")
    suspend fun analyzeShot(@Body request: AnalysisRequest): AnalysisResponse
}

object ApiClient {
    // For physical device testing, use the computer's actual WiFi IP address
    private const val BASE_URL = "https://hoopvision-backend-o71w.onrender.com"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: HoopVisionApiService = retrofit.create(HoopVisionApiService::class.java)
}
