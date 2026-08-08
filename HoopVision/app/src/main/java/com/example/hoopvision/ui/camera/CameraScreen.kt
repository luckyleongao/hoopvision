package com.example.hoopvision.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.hoopvision.network.ApiClient
import com.example.hoopvision.network.AnalysisRequest
import com.example.hoopvision.network.AnalysisResponse
import com.example.hoopvision.util.TtsManager
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

enum class StarArchetype(
    val id: String,
    val shortName: String,
    val icon: String,
    val style: String
) {
    CURRY("curry", "库里", "⚡", "One-Motion 连贯推射"),
    KOBE("kobe", "科比", "🦅", "Two-Motion 滞空干拔"),
    KLAY("klay", "汤普森", "🎯", "教科书 90° 投篮")
}

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraPreview(modifier = modifier.fillMaxSize())
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A)), 
            contentAlignment = Alignment.Center
        ) {
            Text(
                "需要相机权限才能进行投篮动作监测",
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var poseResult by remember { mutableStateOf<PoseHelper.ResultBundle?>(null) }
    var smoothedLandmarks by remember { mutableStateOf<List<SmoothedLandmark>?>(null) }
    var shotAnalysis by remember { mutableStateOf(ShotAnalysis()) }
    val shotStateMachine = remember { ShotStateMachine() }
    val poseSmoother = remember { PoseSmoother() }
    val ballTracker = remember { BallTracker() }
    
    var parabolaResult by remember { mutableStateOf<ParabolaResult?>(null) }
    var currentBallPoint by remember { mutableStateOf<BallPoint?>(null) }
    var trajectoryPoints by remember { mutableStateOf<List<BallPoint>>(emptyList()) }

    val coroutineScope = rememberCoroutineScope()
    var isAnalyzing by remember { mutableStateOf(false) }
    var latestResponse by remember { mutableStateOf<AnalysisResponse?>(null) }
    var displayedKeyframes by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedStar by remember { mutableStateOf(StarArchetype.CURRY) }
    var autoDismissJob by remember { mutableStateOf<Job?>(null) }
    
    val capturedKeyframes = remember { mutableListOf<String>() }

    // TTS Voice Engine
    val ttsManager = remember { TtsManager(context) }

    val poseHelper = remember {
        PoseHelper(context, object : PoseHelper.PoseLandmarkerListener {
            override fun onError(error: String) {
                // handle error
            }

            override fun onResults(resultBundle: PoseHelper.ResultBundle, helper: PoseHelper) {
                poseResult = resultBundle
                val rawLandmarks = resultBundle.results.landmarks().firstOrNull()
                val timestamp = resultBundle.results.timestampMs()
                if (rawLandmarks != null) {
                    val smoothed = poseSmoother.smooth(rawLandmarks)
                    smoothedLandmarks = smoothed
                    val analysis = shotStateMachine.processFrame(timestamp, smoothed)
                    shotAnalysis = analysis
                    
                    // Track basketball & fit parabola
                    val parabola = ballTracker.processFrame(timestamp, analysis.state, smoothed)
                    parabolaResult = parabola
                    currentBallPoint = ballTracker.currentBallPoint
                    trajectoryPoints = ballTracker.getTrajectoryPoints()

                    // Capture keyframe image on critical state changes
                    analysis.stateJustChangedTo?.let { newState ->
                        if (newState == ShotState.GATHER) {
                            // Instant Zero-Touch Reset on next shot squat!
                            capturedKeyframes.clear()
                            autoDismissJob?.cancel()
                            latestResponse = null
                            ballTracker.reset()
                            parabolaResult = null
                        }
                        val base64Img = helper.getLatestFrameBase64()
                        if (base64Img != null) {
                            capturedKeyframes.add(base64Img)
                        }
                    }
                    
                    if (analysis.completedShotFrames != null && !isAnalyzing) {
                        isAnalyzing = true
                        val frames = analysis.completedShotFrames
                        val keyframesToSend = capturedKeyframes.toList()
                        displayedKeyframes = keyframesToSend
                        val currentStarId = selectedStar.id
                        val measuredReleaseAngle = parabolaResult?.releaseAngleDegrees ?: 48f
                        
                        coroutineScope.launch {
                            try {
                                val response = ApiClient.apiService.analyzeShot(
                                    AnalysisRequest(
                                        template_id = currentStarId,
                                        frames = frames,
                                        keyframe_images_base64 = keyframesToSend,
                                        release_angle = measuredReleaseAngle
                                    )
                                )
                                latestResponse = response
                                // Real-time Voice Coach Speaking!
                                ttsManager.speak(response.feedback)

                                // Auto-dismiss sheet after 6.5 seconds of voice coaching
                                autoDismissJob?.cancel()
                                autoDismissJob = coroutineScope.launch {
                                    delay(6500)
                                    latestResponse = null
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                latestResponse = AnalysisResponse(
                                    status = "error",
                                    dtw_distance = 999f,
                                    min_elbow_angle = shotAnalysis.elbowAngle.toFloat(),
                                    min_knee_angle = shotAnalysis.kneeAngle.toFloat(),
                                    release_angle = measuredReleaseAngle,
                                    feedback = "分析暂时失败: ${e.message}"
                                )
                            } finally {
                                isAnalyzing = false
                            }
                        }
                    }
                }
            }
        })
    }

    DisposableEffect(Unit) {
        onDispose {
            autoDismissJob?.cancel()
            poseHelper.clearPoseLandmarker()
            ttsManager.shutdown()
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var previewUseCase by remember { mutableStateOf<Preview?>(null) }
    var imageAnalysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }

    val executor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                
                previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageAnalysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { imageProxy ->
                            poseHelper.detectLiveStream(imageProxy, false)
                            imageProxy.close()
                        }
                    }

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            previewUseCase,
                            imageAnalysisUseCase
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )
        
        // Skeleton & Neon Trajectory Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val landmarks = smoothedLandmarks
            if (landmarks != null && landmarks.isNotEmpty() && poseResult != null) {
                val bundle = poseResult!!

                val imageWidth = bundle.inputImageWidth.toFloat()
                val imageHeight = bundle.inputImageHeight.toFloat()
                
                val scaleFactor = maxOf(size.width / imageWidth, size.height / imageHeight)
                val scaledWidth = imageWidth * scaleFactor
                val scaledHeight = imageHeight * scaleFactor
                
                val offsetX = (size.width - scaledWidth) / 2f
                val offsetY = (size.height - scaledHeight) / 2f

                // 1. Draw Neon Basketball Trajectory Parabola
                val fitted = parabolaResult?.fittedPoints
                if (!fitted.isNullOrEmpty() && fitted.size >= 2) {
                    val path = Path()
                    fitted.forEachIndexed { i, pt ->
                        val px = pt.first * scaledWidth + offsetX
                        val py = pt.second * scaledHeight + offsetY
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }

                    // Outer neon glow
                    drawPath(
                        path = path,
                        color = Color(0x6600FFCC),
                        style = Stroke(width = 12f, cap = StrokeCap.Round)
                    )
                    // Inner bright beam
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFF38BDF8), Color(0xFF00FFCC), Color(0xFFFBBF24))
                        ),
                        style = Stroke(width = 5f, cap = StrokeCap.Round)
                    )
                } else if (trajectoryPoints.size >= 2) {
                    // Fallback to raw points trail
                    for (i in 0 until trajectoryPoints.size - 1) {
                        val p1 = trajectoryPoints[i]
                        val p2 = trajectoryPoints[i + 1]
                        drawLine(
                            brush = Brush.horizontalGradient(listOf(Color(0xFF38BDF8), Color(0xFFFBBF24))),
                            start = Offset(p1.x * scaledWidth + offsetX, p1.y * scaledHeight + offsetY),
                            end = Offset(p2.x * scaledWidth + offsetX, p2.y * scaledHeight + offsetY),
                            strokeWidth = 6f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // 2. Draw Detected Basketball Glowing Reticle
                currentBallPoint?.let { ball ->
                    val bx = ball.x * scaledWidth + offsetX
                    val by = ball.y * scaledHeight + offsetY

                    // Outer halo
                    drawCircle(
                        color = Color(0x55F59E0B),
                        radius = 24f,
                        center = Offset(bx, by)
                    )
                    // Basketball core
                    drawCircle(
                        color = Color(0xFFF59E0B),
                        radius = 12f,
                        center = Offset(bx, by)
                    )
                    // Center bright spot
                    drawCircle(
                        color = Color(0xFFFFFFFF),
                        radius = 4f,
                        center = Offset(bx, by)
                    )
                }

                // 3. Draw neon skeleton lines
                PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                    val start = landmarks[connection.start()]
                    val end = landmarks[connection.end()]
                    
                    val startX = start.x() * scaledWidth + offsetX
                    val startY = start.y() * scaledHeight + offsetY
                    val endX = end.x() * scaledWidth + offsetX
                    val endY = end.y() * scaledHeight + offsetY
                    
                    drawLine(
                        color = Color(0xFF00FFCC),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 5f
                    )
                }

                // 4. Draw joints
                for (landmark in landmarks) {
                    val x = landmark.x() * scaledWidth + offsetX
                    val y = landmark.y() * scaledHeight + offsetY
                    
                    drawCircle(
                        color = Color(0xFFFF3366),
                        radius = 7f,
                        center = Offset(x, y)
                    )
                }
            }
        }
        
        // Top High-Tech Live HUD + Star Archetype Selector
        TopLiveHud(
            shotAnalysis = shotAnalysis,
            parabolaResult = parabolaResult,
            isAnalyzing = isAnalyzing,
            selectedStar = selectedStar,
            onStarSelected = { selectedStar = it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
        )

        // Bottom Rich Diagnosis Card (Animated Popup with Auto-Dismiss)
        AnimatedVisibility(
            visible = latestResponse != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            latestResponse?.let { response ->
                DiagnosisSheetCard(
                    response = response,
                    star = selectedStar,
                    keyframeImages = displayedKeyframes,
                    onReplayVoice = {
                        ttsManager.speak(response.feedback)
                    },
                    onDismiss = {
                        autoDismissJob?.cancel()
                        latestResponse = null
                    }
                )
            }
        }
    }
}

/**
 * Modern High-Tech Top HUD Bar with Star Selector & Parabolic Arc Metrics
 */
@Composable
fun TopLiveHud(
    shotAnalysis: ShotAnalysis,
    parabolaResult: ParabolaResult?,
    isAnalyzing: Boolean,
    selectedStar: StarArchetype,
    onStarSelected: (StarArchetype) -> Unit,
    modifier: Modifier = Modifier
) {
    val stateText = when (shotAnalysis.state) {
        ShotState.IDLE -> "准备投篮"
        ShotState.GATHER -> "🔥 蓄力下蹲"
        ShotState.SET_POINT -> "⚡ 举球托球"
        ShotState.RELEASE -> "🎯 顶峰出手"
    }

    val stateColor = when (shotAnalysis.state) {
        ShotState.IDLE -> Color(0xFF64748B)
        ShotState.GATHER -> Color(0xFFF97316)
        ShotState.SET_POINT -> Color(0xFF38BDF8)
        ShotState.RELEASE -> Color(0xFF10B981)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Star Archetype Switcher Row
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xBB0F172A))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StarArchetype.values().forEach { star ->
                val isSelected = star == selectedStar
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) Color(0xFF0284C7) else Color.Transparent)
                        .clickable { onStarSelected(star) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${star.icon} ${star.shortName}",
                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Live Action HUD with Angle & Trajectory Arc
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xCC0F172A))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(30.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // State Badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stateText,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Divider(
                color = Color.DarkGray,
                modifier = Modifier
                    .height(14.dp)
                    .width(1.dp)
            )

            // Live Angles
            val isElbowGood = shotAnalysis.elbowAngle in 80.0..105.0
            Text(
                text = "肘: ${shotAnalysis.elbowAngle.toInt()}°",
                color = if (isElbowGood) Color(0xFF4ADE80) else Color(0xFFFACC15),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            val isKneeGood = shotAnalysis.kneeAngle in 110.0..145.0
            Text(
                text = "膝: ${shotAnalysis.kneeAngle.toInt()}°",
                color = if (isKneeGood) Color(0xFF4ADE80) else Color(0xFFFACC15),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            // Trajectory Release Angle
            parabolaResult?.let { arc ->
                Divider(
                    color = Color.DarkGray,
                    modifier = Modifier
                        .height(14.dp)
                        .width(1.dp)
                )
                Text(
                    text = "弧: ${arc.releaseAngleDegrees.toInt()}°",
                    color = if (arc.isGoldenArc) Color(0xFF4ADE80) else Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isAnalyzing) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xDD1E1B4B))
                    .border(1.dp, Color(0xFF818CF8), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color(0xFFA5B4FC),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${selectedStar.shortName}流派 · 视觉与轨迹诊断中...",
                    color = Color(0xFFA5B4FC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Rich Glassmorphic Diagnosis Card with Keyframe Gallery and AI Feedback
 */
@Composable
fun DiagnosisSheetCard(
    response: AnalysisResponse,
    star: StarArchetype,
    keyframeImages: List<String>,
    onReplayVoice: () -> Unit,
    onDismiss: () -> Unit
) {
    val dtw = response.dtw_distance
    val (grade, gradeColor, score) = when {
        dtw < 380 -> Triple("S", Color(0xFF10B981), "96")
        dtw < 520 -> Triple("A", Color(0xFF38BDF8), "88")
        dtw < 750 -> Triple("B", Color(0xFFFACC15), "76")
        else -> Triple("C", Color(0xFFF87171), "62")
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF00B132B)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x8838BDF8), Color(0x221E293B))), RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Grade & Star Style & Replay Voice Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Grade Badge
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(gradeColor.copy(alpha = 0.2f))
                            .border(2.dp, gradeColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = grade,
                            color = gradeColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${star.icon} ${star.shortName}投篮评分: $score 分",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "流派: ${star.style} | 节奏偏差: ${dtw.toInt()}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                // Speaker Replay Button
                IconButton(
                    onClick = onReplayVoice,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x3338BDF8))
                ) {
                    Text("🔊", fontSize = 18.sp)
                }
            }

            // Keyframe Thumbnails (3-shot sequential film strip)
            if (keyframeImages.isNotEmpty()) {
                val labels = listOf("1. 蓄力下蹲", "2. 举球托球", "3. 顶峰出手")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    keyframeImages.take(3).forEachIndexed { idx, base64 ->
                        val bitmap = remember(base64) {
                            decodeBase64(base64)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(84.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Keyframe $idx",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("无画面", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = labels.getOrElse(idx) { "关键瞬间" },
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Metrics Data Bar (Elbow, Knee, and Release Trajectory Angle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x661E293B))
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem(label = "托球手肘", value = "${response.min_elbow_angle.toInt()}°", ideal = "标准 90°")
                Divider(color = Color.DarkGray, modifier = Modifier.height(24.dp).width(1.dp))
                MetricItem(label = "蓄力膝盖", value = "${response.min_knee_angle.toInt()}°", ideal = "标准 125°")
                Divider(color = Color.DarkGray, modifier = Modifier.height(24.dp).width(1.dp))
                val arcVal = response.release_angle?.toInt() ?: 48
                MetricItem(label = "出手弧度", value = "${arcVal}°", ideal = "黄金 48°")
            }

            // AI Coach Feedback Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x330284C7))
                    .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = response.feedback,
                    color = Color(0xFFE0F2FE),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Bottom Auto-Dismiss Prompt & Manual Dismiss Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "⏳ 6秒自动收起，或直接下蹲投下一球",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Text(
                    text = "收起 ✕",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp)
                )
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, ideal: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Text(text = value, color = Color(0xFF38BDF8), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(text = ideal, color = Color(0xFF64748B), fontSize = 10.sp)
    }
}

fun decodeBase64(base64Str: String): android.graphics.Bitmap? {
    return try {
        val clean = base64Str.split(",").last()
        val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        null
    }
}
