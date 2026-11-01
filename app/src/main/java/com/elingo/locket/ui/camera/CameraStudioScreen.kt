package com.elingo.locket.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.elingo.locket.core.database.OutboxEntity
import com.elingo.locket.core.model.CaptionOverlayConfig
import com.elingo.locket.core.model.FriendUser
import com.elingo.locket.core.model.StreakState
import com.elingo.locket.core.model.SupporterStatus
import com.elingo.locket.media.camera.HardwareCameraController
import com.elingo.locket.media.filter.LocketColorFilters
import com.elingo.locket.media.processor.Media3VideoProcessor
import com.elingo.locket.media.processor.PhotoProcessor
import com.elingo.locket.media.processor.PhotoboothComposer
import com.elingo.locket.media.processor.PhotoboothFrame
import com.elingo.locket.media.processor.PhotoboothLayout
import com.elingo.locket.ui.OverlayLookupState
import com.elingo.locket.ui.auth.AccountAvatarCircle
import com.elingo.locket.ui.theme.LocalLocketPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class DraftCapture(
    val file: File,
    val isVideo: Boolean,
    val perks: List<String> = emptyList(),
    val alreadyPrepared: Boolean = false
)

@Composable
fun CameraStudioScreen(
    hasCameraPermission: Boolean,
    hasMicPermission: Boolean,
    friends: List<FriendUser>,
    streakState: StreakState,
    supporterStatus: SupporterStatus,
    outboxItems: List<OutboxEntity>,
    overlayLookup: OverlayLookupState,
    onRequestPermissions: () -> Unit,
    onRequestLocationOverlays: () -> Unit,
    onSendToOutbox: (
        file: File,
        isVideo: Boolean,
        isPrivate: Boolean,
        filterId: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        perks: List<String>,
        alreadyPrepared: Boolean
    ) -> Unit,
    onRetryOutbox: (String) -> Unit,
    onCancelOutbox: (String) -> Unit,
    onJumpToFeed: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = LocalLocketPalette.current
    val scope = rememberCoroutineScope()

    val cameraController = remember { HardwareCameraController(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { cameraController.release() }
    }

    val isFrontCamera by cameraController.isFrontCamera.collectAsState()
    val flashEnabled by cameraController.flashEnabled.collectAsState()
    val showRingFlash by cameraController.showScreenRingFlash.collectAsState()
    val zoomRatio by cameraController.zoomRatio.collectAsState()
    val isRecording by cameraController.isRecording.collectAsState()
    val recordingElapsedMs by cameraController.recordingElapsedMs.collectAsState()
    val cameraError by cameraController.cameraError.collectAsState()

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var selectedFilterId by remember { mutableStateOf("none") }
    val selectedFilter = remember(selectedFilterId) { LocketColorFilters.byId(selectedFilterId) }

    var isPhotoboothMode by remember { mutableStateOf(false) }
    val boothShots = remember { mutableStateListOf<File>() }
    var boothAutoCountdown by remember { mutableStateOf(false) }
    var countdownSec by remember { mutableIntStateOf(0) }
    var boothLayout by remember { mutableStateOf(PhotoboothLayout.GRID_2X2) }
    var boothFrame by remember { mutableStateOf(PhotoboothComposer.FRAMES.first()) }
    var boothFooterNote by remember { mutableStateOf("") }
    var isComposingBooth by remember { mutableStateOf(false) }

    var focusRingOffset by remember { mutableStateOf<Offset?>(null) }
    var draftCapture by remember { mutableStateOf<DraftCapture?>(null) }

    // Bind CameraX hardware pipeline whenever permission is granted & draft preview is closed
    LaunchedEffect(hasCameraPermission, previewViewRef, draftCapture == null) {
        val view = previewViewRef
        if (hasCameraPermission && view != null && draftCapture == null) {
            cameraController.bindToLifecycle(lifecycleOwner, view, isFrontCamera)
        }
    }

    // Auto-clear focus indicator after 900ms
    LaunchedEffect(focusRingOffset) {
        if (focusRingOffset != null) {
            delay(900L)
            focusRingOffset = null
        }
    }

    // Gallery Media Picker (Images <= 20 MB, Videos <= 200 MB & <= 60s)
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val mime = context.contentResolver.getType(uri).orEmpty()
                val isVid = mime.startsWith("video")
                val copied = PhotoProcessor.copyUriToTempFile(context, uri, if (isVid) "vid" else "img")
                if (isVid) {
                    if (copied.length() > Media3VideoProcessor.MAX_INPUT_VIDEO_BYTES) {
                        copied.delete()
                        onShowMessage("Video vượt quá giới hạn 200 MB")
                        return@launch
                    }
                    val meta = Media3VideoProcessor.inspectVideo(copied)
                    if (meta.durationMs > Media3VideoProcessor.MAX_VIDEO_DURATION_MS) {
                        copied.delete()
                        onShowMessage("Locket hỗ trợ video tối đa 60 giây")
                        return@launch
                    }
                } else {
                    if (copied.length() > PhotoProcessor.MAX_INPUT_IMAGE_BYTES) {
                        copied.delete()
                        onShowMessage("Ảnh vượt quá giới hạn 20 MB")
                        return@launch
                    }
                }
                draftCapture = DraftCapture(file = copied, isVideo = isVid)
            } catch (e: Throwable) {
                onShowMessage(e.localizedMessage ?: "Không đọc được tệp từ thư viện")
            }
        }
    }

    // Location permission launcher for Weather & Nearby Places overlays
    val locationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            onRequestLocationOverlays()
        } else {
            onShowMessage("Cần quyền Vị trí để lấy Thời tiết & Địa điểm")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.spec.background)
    ) {
        if (draftCapture != null) {
            val activeDraft = draftCapture!!
            PostCaptureComposerView(
                draft = activeDraft,
                selectedFilterId = selectedFilterId,
                onSelectFilter = { selectedFilterId = it },
                friends = friends,
                streakState = streakState,
                overlayLookup = overlayLookup,
                onFetchLocationOverlays = {
                    val hasLoc = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasLoc) {
                        onRequestLocationOverlays()
                    } else {
                        locationPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                onDiscard = {
                    draftCapture = null
                },
                onSend = { isPriv, caption, overlayCfg, recipients ->
                    onSendToOutbox(
                        activeDraft.file,
                        activeDraft.isVideo,
                        isPriv,
                        selectedFilterId,
                        caption,
                        overlayCfg,
                        recipients,
                        activeDraft.perks,
                        activeDraft.alreadyPrepared
                    )
                    draftCapture = null
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Non-Blocking Background Outbox Progress Strip
                AnimatedVisibility(visible = outboxItems.isNotEmpty()) {
                    OutboxFloatingStatusCard(
                        items = outboxItems,
                        onRetry = onRetryOutbox,
                        onCancel = onCancelOutbox
                    )
                }

                // 2. Top Hardware Controls Row (Flash, Streak, Mode Switcher, Zoom)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { cameraController.toggleFlash() },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (flashEnabled) palette.spec.accent else palette.cardContainer
                            )
                    ) {
                        Icon(
                            imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Đèn Flash",
                            tint = if (flashEnabled) palette.spec.onAccent else Color.White
                        )
                    }

                    // Mode Toggle Pill: 1:1 Live vs 4-Shot Photobooth
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.cardContainer)
                            .border(1.dp, palette.cardBorder, RoundedCornerShape(50))
                            .padding(3.dp)
                    ) {
                        ModeChip(
                            label = "1:1 Live",
                            selected = !isPhotoboothMode,
                            onClick = {
                                isPhotoboothMode = false
                                boothShots.clear()
                            }
                        )
                        ModeChip(
                            label = "Photobooth 4×",
                            selected = isPhotoboothMode,
                            onClick = { isPhotoboothMode = true }
                        )
                    }

                    // Streak & Zoom Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.cardContainer)
                            .clickable {
                                val nextZoom = if (zoomRatio < 1.8f) 2.0f else 1.0f
                                cameraController.setZoom(nextZoom)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (streakState.count > 0) {
                            Text(
                                text = "🔥${streakState.count} • ",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.spec.accent
                            )
                        }
                        Text(
                            text = "${((zoomRatio * 10f).roundToInt() / 10f)}x",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 3. 1:1 Hardware SurfaceView Viewfinder (Zero WebView / Zero Canvas Copy!)
                var swipeAccumulator by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(36.dp))
                        .background(Color(0xFF101115))
                        .border(
                            width = if (isRecording) 3.dp else 1.5.dp,
                            color = if (isRecording) Color(0xFFFF453A) else palette.cardBorder,
                            shape = RoundedCornerShape(36.dp)
                        )
                ) {
                    if (!hasCameraPermission) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Cấp quyền Camera & Micro để mở ống kính 60FPS gốc",
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = onRequestPermissions,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.spec.accent,
                                    contentColor = palette.spec.onAccent
                                )
                            ) {
                                Text("Cho phép truy cập Camera", fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    // PERFORMANCE forces hardware SurfaceView direct display composition (60FPS, minimal latency)
                                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    previewViewRef = this
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoomChange, _ ->
                                        if (zoomChange != 1f) {
                                            cameraController.applyPinchZoomFactor(zoomChange)
                                        }
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { offset ->
                                            focusRingOffset = offset
                                            cameraController.tapToFocus(offset.x, offset.y)
                                        },
                                        onDoubleTap = {
                                            scope.launch { cameraController.toggleFacing() }
                                        }
                                    )
                                }
                                .pointerInput(selectedFilterId) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { swipeAccumulator = 0f },
                                        onHorizontalDrag = { _, dragAmount ->
                                            swipeAccumulator += dragAmount
                                        },
                                        onDragEnd = {
                                            if (swipeAccumulator > 90f) {
                                                selectedFilterId = LocketColorFilters.next(selectedFilterId, -1).id
                                            } else if (swipeAccumulator < -90f) {
                                                selectedFilterId = LocketColorFilters.next(selectedFilterId, 1).id
                                            }
                                            swipeAccumulator = 0f
                                        }
                                    )
                                }
                        )

                        // Lightweight GPU Tint Preview Overlay over SurfaceView
                        selectedFilter.tint?.let { tint ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(tint.composeColor.copy(alpha = (tint.opacity * 0.32f).coerceAtMost(0.22f)))
                            )
                        }

                        // Tap-to-Focus Indicator Ring
                        focusRingOffset?.let { pos ->
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(pos.x.roundToInt() - 32, pos.y.roundToInt() - 32) }
                                    .size(64.dp)
                                    .border(2.dp, palette.spec.accent, CircleShape)
                            )
                        }

                        // Live Recording Timer Pill
                        if (isRecording) {
                            val sec = (recordingElapsedMs / 1000L).toInt()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 14.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xCCFF3B30))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = String.format("00:%02d / 00:59", sec),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // Photobooth Countdown Overlay
                        if (countdownSec > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.28f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = countdownSec.toString(),
                                    fontSize = 84.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        }

                        // Active Filter Badge
                        if (!selectedFilter.isIdentity) {
                            Text(
                                text = "✨ ${selectedFilter.name}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.52f))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        if (cameraError != null) {
                            Text(
                                text = cameraError.orEmpty(),
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(20.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 4. Horizontal Filter Selector Pills (10 Official Locket Filters)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(LocketColorFilters.ALL, key = { it.id }) { filter ->
                        val active = filter.id == selectedFilterId
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (active) palette.spec.accent else palette.cardContainer
                                )
                                .border(1.dp, palette.cardBorder, RoundedCornerShape(50))
                                .clickable { selectedFilterId = filter.id }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = filter.name,
                                fontSize = 12.5.sp,
                                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (active) palette.spec.onAccent else Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 5. Photobooth 4-Shot Studio Strip & Frame Selector (when Photobooth Mode is ON)
                if (isPhotoboothMode) {
                    PhotoboothControlPanel(
                        shots = boothShots,
                        autoCountdown = boothAutoCountdown,
                        selectedLayout = boothLayout,
                        selectedFrame = boothFrame,
                        footerNote = boothFooterNote,
                        supporterStatus = supporterStatus,
                        isComposing = isComposingBooth,
                        onToggleCountdown = { boothAutoCountdown = !boothAutoCountdown },
                        onSelectLayout = { boothLayout = it },
                        onSelectFrame = { frame ->
                            if (supporterStatus.canUseTier(frame.requiredTier)) {
                                boothFrame = frame
                            } else {
                                onShowMessage("Khung '${frame.name}' dành cho tài khoản Supporter (mở mục Hồ sơ -> Supporter để kiểm tra)")
                            }
                        },
                        onFooterChange = { boothFooterNote = it },
                        onClearShots = { boothShots.clear() },
                        onExportBooth = { exportAsStopMotionVideo ->
                            scope.launch {
                                isComposingBooth = true
                                try {
                                    val output = if (exportAsStopMotionVideo) {
                                        PhotoboothComposer.composePhotoboothStopMotionVideo(
                                            context = context,
                                            shotFiles = boothShots.toList(),
                                            layout = boothLayout,
                                            frame = boothFrame,
                                            filterId = selectedFilterId,
                                            footerNote = boothFooterNote,
                                            supporterStatus = supporterStatus
                                        )
                                    } else {
                                        PhotoboothComposer.composePhotoboothStill(
                                            context = context,
                                            shotFiles = boothShots.toList(),
                                            layout = boothLayout,
                                            frame = boothFrame,
                                            filterId = selectedFilterId,
                                            footerNote = boothFooterNote,
                                            supporterStatus = supporterStatus
                                        )
                                    }
                                    boothShots.clear()
                                    draftCapture = DraftCapture(
                                        file = output.file,
                                        isVideo = output.isVideo,
                                        perks = output.usedPerks,
                                        alreadyPrepared = true
                                    )
                                } catch (e: Throwable) {
                                    onShowMessage(e.localizedMessage ?: "Không ghép được ảnh Photobooth")
                                } finally {
                                    isComposingBooth = false
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 6. Primary Hardware Shutter Row (Gallery | Tap=Photo / Hold=60s H.264 Video | Flip Camera)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Gallery Picker Button
                    IconButton(
                        onClick = {
                            mediaPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(palette.cardContainer)
                            .border(1.dp, palette.cardBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Chọn ảnh/video từ máy",
                            tint = Color.White
                        )
                    }

                    // Hardware Dual-Mode Shutter (Tap: Photo | Hold >= 380ms: H.264 Live Video)
                    HardwareShutterButton(
                        isRecording = isRecording,
                        recordingProgress = (recordingElapsedMs.toFloat() / HardwareCameraController.MAX_RECORD_DURATION_MS.toFloat()).coerceIn(0f, 1f),
                        accentColor = palette.spec.accent,
                        onTapPhoto = {
                            if (!hasCameraPermission) {
                                onRequestPermissions()
                                return@HardwareShutterButton
                            }
                            if (isRecording) {
                                cameraController.stopRecordingIfActive()
                                return@HardwareShutterButton
                            }
                            scope.launch {
                                try {
                                    if (isPhotoboothMode) {
                                        if (boothAutoCountdown && boothShots.isEmpty()) {
                                            while (boothShots.size < PhotoboothComposer.REQUIRED_SHOTS) {
                                                for (c in 3 downTo 1) {
                                                    countdownSec = c
                                                    delay(950L)
                                                }
                                                countdownSec = 0
                                                val shot = cameraController.takePhotoToFile()
                                                boothShots.add(shot)
                                            }
                                        } else if (boothShots.size < PhotoboothComposer.REQUIRED_SHOTS) {
                                            val shot = cameraController.takePhotoToFile()
                                            boothShots.add(shot)
                                        } else {
                                            onShowMessage("Đã chụp đủ 4 kiểu! Hãy bấm Xuất ảnh hoặc Chụp lại.")
                                        }
                                    } else {
                                        val photo = cameraController.takePhotoToFile()
                                        draftCapture = DraftCapture(file = photo, isVideo = false)
                                    }
                                } catch (e: Throwable) {
                                    countdownSec = 0
                                    onShowMessage(e.localizedMessage ?: "Chụp ảnh thất bại")
                                }
                            }
                        },
                        onStartHoldVideo = {
                            if (!hasCameraPermission || isPhotoboothMode) return@HardwareShutterButton
                            cameraController.startVideoRecording(withAudio = hasMicPermission) { result ->
                                result.onSuccess { vidFile ->
                                    draftCapture = DraftCapture(file = vidFile, isVideo = true)
                                }.onFailure { err ->
                                    onShowMessage(err.localizedMessage ?: "Quay video thất bại")
                                }
                            }
                        },
                        onReleaseHoldVideo = {
                            cameraController.stopRecordingIfActive()
                        }
                    )

                    // Flip Front / Rear Camera
                    IconButton(
                        onClick = {
                            scope.launch { cameraController.toggleFacing() }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(palette.cardContainer)
                            .border(1.dp, palette.cardBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Đổi camera trước/sau",
                            tint = Color.White
                        )
                    }
                }

                Text(
                    text = if (isPhotoboothMode) "Chạm chụp đủ 4 ảnh (${boothShots.size}/4) để đóng khung Photobooth"
                    else "Chạm để chụp ảnh • Nhấn giữ để quay video H.264 mượt 60FPS",
                    fontSize = 11.5.sp,
                    color = palette.mutedText,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Quick Jump to Locket Feed
                TextButton(onClick = onJumpToFeed) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = palette.spec.accent
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Xem lịch sử Locket bạn bè", color = palette.spec.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Front-Camera Warm White Ring-Flash Overlay
        if (showRingFlash) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEBFFF8EE))
            )
        }
    }
}

@Composable
private fun HardwareShutterButton(
    isRecording: Boolean,
    recordingProgress: Float,
    accentColor: Color,
    onTapPhoto: () -> Unit,
    onStartHoldVideo: () -> Unit,
    onReleaseHoldVideo: () -> Unit
) {
    val animatedSweep by animateFloatAsState(targetValue = recordingProgress * 360f, label = "rec_sweep")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp)
            .pointerInput(isRecording) {
                detectTapGestures(
                    onPress = {
                        var triggeredHold = false
                        val holdJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            delay(380L)
                            triggeredHold = true
                            onStartHoldVideo()
                        }
                        tryAwaitRelease()
                        holdJob.cancel()
                        if (triggeredHold) {
                            onReleaseHoldVideo()
                        } else {
                            onTapPhoto()
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = if (isRecording) Color(0x44FF3B30) else accentColor.copy(alpha = 0.25f),
                style = Stroke(width = 6.dp.toPx())
            )
            if (isRecording) {
                drawArc(
                    color = Color(0xFFFF3B30),
                    startAngle = -90f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            } else {
                drawCircle(
                    color = accentColor,
                    style = Stroke(width = 4.5.dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .size(if (isRecording) 38.dp else 66.dp)
                .clip(if (isRecording) RoundedCornerShape(10.dp) else CircleShape)
                .background(if (isRecording) Color(0xFFFF3B30) else Color.White)
        )
    }
}

@Composable
private fun PhotoboothControlPanel(
    shots: List<File>,
    autoCountdown: Boolean,
    selectedLayout: PhotoboothLayout,
    selectedFrame: PhotoboothFrame,
    footerNote: String,
    supporterStatus: SupporterStatus,
    isComposing: Boolean,
    onToggleCountdown: () -> Unit,
    onSelectLayout: (PhotoboothLayout) -> Unit,
    onSelectFrame: (PhotoboothFrame) -> Unit,
    onFooterChange: (String) -> Unit,
    onClearShots: () -> Unit,
    onExportBooth: (asVideo: Boolean) -> Unit
) {
    val palette = LocalLocketPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardContainer)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(22.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Buồng chụp Photobooth (${shots.size}/4)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = autoCountdown,
                    onClick = onToggleCountdown,
                    label = { Text("Đếm 3s tự động", fontSize = 11.5.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                )
                if (shots.isNotEmpty()) {
                    IconButton(onClick = onClearShots) {
                        Icon(Icons.Default.Refresh, contentDescription = "Chụp lại")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4 Shot Slot Thumbnails
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(PhotoboothComposer.REQUIRED_SHOTS) { idx ->
                val file = shots.getOrNull(idx)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (file != null) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Ảnh ${idx + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("#${idx + 1}", color = palette.mutedText, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Layout Selector
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PhotoboothLayout.entries.forEach { layout ->
                FilterChip(
                    selected = selectedLayout == layout,
                    onClick = { onSelectLayout(layout) },
                    label = { Text(layout.title, fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Frame Color / Supporter Tier Selector
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PhotoboothComposer.FRAMES.forEach { frame ->
                val unlocked = supporterStatus.canUseTier(frame.requiredTier)
                val active = selectedFrame.id == frame.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) palette.spec.accent.copy(alpha = 0.25f)
                            else Color.Black.copy(alpha = 0.25f)
                        )
                        .border(
                            width = if (active) 1.5.dp else 1.dp,
                            color = if (active) palette.spec.accent else palette.cardBorder,
                            shape = RoundedCornerShape(50)
                        )
                        .clickable { onSelectFrame(frame) }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    if (!unlocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Supporter",
                            tint = palette.mutedText,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = frame.name,
                        fontSize = 12.sp,
                        color = if (unlocked) Color.White else palette.mutedText
                    )
                }
            }
        }

        if (shots.size >= PhotoboothComposer.REQUIRED_SHOTS) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = footerNote,
                onValueChange = { if (it.length <= 32) onFooterChange(it) },
                placeholder = { Text("Ghi chú chân khung Photobooth (tùy chọn)", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onExportBooth(false) },
                    enabled = !isComposing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.spec.accent,
                        contentColor = palette.spec.onAccent
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isComposing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Ghép ảnh 4×", fontWeight = FontWeight.Bold)
                    }
                }

                val canVideoBooth = supporterStatus.canUseTier("booth-1")
                Button(
                    onClick = {
                        if (canVideoBooth) onExportBooth(true)
                        else onSelectFrame(PhotoboothComposer.FRAMES.first { it.requiredTier == "booth-1" })
                    },
                    enabled = !isComposing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.spec.surface,
                        contentColor = if (canVideoBooth) Color.White else palette.mutedText
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (canVideoBooth) Icons.Default.Videocam else Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Stop-Motion", fontSize = 12.5.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostCaptureComposerView(
    draft: DraftCapture,
    selectedFilterId: String,
    onSelectFilter: (String) -> Unit,
    friends: List<FriendUser>,
    streakState: StreakState,
    overlayLookup: OverlayLookupState,
    onFetchLocationOverlays: () -> Unit,
    onDiscard: () -> Unit,
    onSend: (
        isPrivate: Boolean,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>
    ) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalLocketPalette.current
    val filter = remember(selectedFilterId) { LocketColorFilters.byId(selectedFilterId) }

    var captionText by remember { mutableStateOf("") }
    var overlayConfig by remember { mutableStateOf(CaptionOverlayConfig(noteType = "standard")) }
    var showOverlaySheet by remember { mutableStateOf(false) }

    var isPrivateVault by remember { mutableStateOf(false) }
    val selectedRecipients = remember { mutableStateListOf<String>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onDiscard) {
                Icon(Icons.Default.Close, contentDescription = "Hủy")
            }
            Text(
                text = if (draft.isVideo) "Xem trước Video 1:1" else "Xem trước Ảnh 1:1",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            TextButton(onClick = { showOverlaySheet = true }) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = palette.spec.accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Nhãn dán", color = palette.spec.accent, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 1:1 Square Media Preview + Live Locket Caption Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(36.dp))
                .background(Color.Black)
                .border(1.5.dp, palette.cardBorder, RoundedCornerShape(36.dp))
        ) {
            if (draft.isVideo) {
                DraftVideoPlayer(videoFile = draft.file)
                if (!draft.alreadyPrepared && !filter.isIdentity) {
                    filter.tint?.let { tint ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(tint.composeColor.copy(alpha = 0.22f))
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = draft.file,
                    contentDescription = "Ảnh vừa chụp",
                    contentScale = ContentScale.Crop,
                    colorFilter = if (!draft.alreadyPrepared && !filter.isIdentity) {
                        ColorFilter.colorMatrix(filter.toComposeColorMatrix())
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Live Overlay Pill on bottom of 1:1 frame
            val pillLabel = overlayConfig.previewText?.takeIf { it.isNotBlank() } ?: captionText
            if (pillLabel.isNotBlank() || !overlayConfig.icon.isNullOrBlank()) {
                val bgBrush = if (overlayConfig.colors.size >= 2) {
                    val c1 = runCatching { Color(android.graphics.Color.parseColor(overlayConfig.colors[0])) }.getOrDefault(Color(0xCC000000))
                    val c2 = runCatching { Color(android.graphics.Color.parseColor(overlayConfig.colors[1])) }.getOrDefault(c1)
                    Brush.horizontalGradient(listOf(c1, c2))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xB3000000), Color(0xB3000000)))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(bgBrush)
                        .clickable { showOverlaySheet = true }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    if (!overlayConfig.icon.isNullOrBlank()) {
                        Text(text = overlayConfig.icon!!, fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = pillLabel,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Filter switcher (if not already baked by Photobooth)
        if (!draft.alreadyPrepared) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(LocketColorFilters.ALL, key = { it.id }) { item ->
                    val selected = item.id == selectedFilterId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) palette.spec.accent else palette.cardContainer)
                            .clickable { onSelectFilter(item.id) }
                            .padding(horizontal = 13.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = item.name,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) palette.spec.onAccent else Color.White
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Caption Text Input
        OutlinedTextField(
            value = captionText,
            onValueChange = {
                captionText = it
                if (overlayConfig.noteType == "standard" || overlayConfig.noteType == "gradient") {
                    overlayConfig = overlayConfig.copy(previewText = it)
                }
            },
            placeholder = { Text("Nhập lời nhắn Locket...") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Recipient Selector (All Friends | Specific Friends | Private Vault)
        Text(
            text = "Gửi tới:",
            fontSize = 12.5.sp,
            color = palette.mutedText,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !isPrivateVault && selectedRecipients.isEmpty(),
                onClick = {
                    isPrivateVault = false
                    selectedRecipients.clear()
                },
                label = { Text("Tất cả bạn bè") }
            )
            FilterChip(
                selected = isPrivateVault,
                onClick = {
                    isPrivateVault = true
                    selectedRecipients.clear()
                },
                label = { Text("🔒 Kho riêng tư") }
            )
            friends.forEach { friend ->
                val isSelected = !isPrivateVault && friend.uid in selectedRecipients
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isPrivateVault = false
                        if (isSelected) selectedRecipients.remove(friend.uid)
                        else selectedRecipients.add(friend.uid)
                    },
                    leadingIcon = {
                        AccountAvatarCircle(
                            avatarUrl = friend.profilePictureUrl,
                            label = friend.fullName,
                            sizeDp = 22
                        )
                    },
                    label = { Text(friend.firstName.ifBlank { friend.fullName }) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Instant Non-Blocking Send Button
        Button(
            onClick = {
                val finalCaption = overlayConfig.previewText?.takeIf { it.isNotBlank() } ?: captionText
                onSend(
                    isPrivateVault,
                    finalCaption,
                    overlayConfig,
                    selectedRecipients.toList()
                )
            },
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.spec.accent,
                contentColor = palette.spec.onAccent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isPrivateVault) "Lưu vào Kho riêng tư (Chạy ngầm)"
                else "Gửi Locket ngay (Xử lý ngầm 0 độ trễ)",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp
            )
        }
    }

    if (showOverlaySheet) {
        OverlayStylePickerSheet(
            currentCaption = captionText,
            streakState = streakState,
            overlayLookup = overlayLookup,
            onRequestLocation = onFetchLocationOverlays,
            onApply = { cfg, newCaption ->
                overlayConfig = cfg
                captionText = newCaption
                showOverlaySheet = false
            },
            onDismiss = { showOverlaySheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayStylePickerSheet(
    currentCaption: String,
    streakState: StreakState,
    overlayLookup: OverlayLookupState,
    onRequestLocation: () -> Unit,
    onApply: (CaptionOverlayConfig, String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalLocketPalette.current
    var starRating by remember { mutableIntStateOf(5) }

    val gradients = listOf(
        "Hoàng hôn" to listOf("#FF512F", "#DD2476"),
        "Đại dương" to listOf("#2193B0", "#6DD5ED"),
        "Tím Neon" to listOf("#8E2DE2", "#4A00E0"),
        "Lục bảo" to listOf("#11998E", "#38EF7D"),
        "Vàng Locket" to listOf("#F2994A", "#F2C94C")
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.spec.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Chọn kiểu Nhãn dán & Caption Locket", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            // 1. Time & Streak Quick Pills
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.cardContainer,
                    modifier = Modifier.clickable {
                        onApply(
                            CaptionOverlayConfig(noteType = "time", icon = "🕒", previewText = nowTime),
                            nowTime
                        )
                    }
                ) {
                    Text("🕒 Giờ hiện tại ($nowTime)", modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }

                val streakLabel = "Chuỗi ${streakState.nextCaptionDay} ngày"
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.cardContainer,
                    modifier = Modifier.clickable {
                        onApply(
                            CaptionOverlayConfig(
                                noteType = "streak",
                                icon = "🔥",
                                colors = listOf("#FF8A00", "#FFB800"),
                                previewText = streakLabel
                            ),
                            streakLabel
                        )
                    }
                ) {
                    Text("🔥 $streakLabel", modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }

            // 2. 5-Star Review Overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.cardContainer)
                    .padding(12.dp)
            ) {
                Text("⭐ Đánh giá sao (Review)", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (1..5).forEach { s ->
                        IconButton(onClick = { starRating = s }) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "$s sao",
                                tint = if (s <= starRating) Color(0xFFFFC107) else palette.mutedText
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val starsStr = "★".repeat(starRating) + "☆".repeat(5 - starRating)
                        val text = if (currentCaption.isNotBlank()) "$starsStr $currentCaption" else starsStr
                        onApply(
                            CaptionOverlayConfig(
                                noteType = "review",
                                payload = buildJsonObject { put("rating", starRating) },
                                previewText = text
                            ),
                            text
                        )
                    }) {
                        Text("Áp dụng", color = palette.spec.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 3. Gradient Styles
            Text("🎨 Dải màu Gradient", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gradients.forEach { (name, pair) ->
                    val c1 = Color(android.graphics.Color.parseColor(pair[0]))
                    val c2 = Color(android.graphics.Color.parseColor(pair[1]))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(c1, c2)))
                            .clickable {
                                val txt = currentCaption.ifBlank { name }
                                onApply(
                                    CaptionOverlayConfig(
                                        noteType = "gradient",
                                        colors = pair,
                                        textColor = "#FFFFFF",
                                        previewText = txt
                                    ),
                                    txt
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // 4. GPS Weather & Nearby Places
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("📍 Thời tiết & Địa điểm GPS", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                TextButton(onClick = onRequestLocation) {
                    if (overlayLookup.isLoadingLocation) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Cập nhật GPS", color = palette.spec.accent)
                    }
                }
            }

            overlayLookup.weather?.let { w ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.cardContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onApply(
                                CaptionOverlayConfig(noteType = "weather", icon = w.icon, previewText = w.label),
                                w.label
                            )
                        }
                ) {
                    Text("${w.icon} ${w.label}", modifier = Modifier.padding(12.dp), fontSize = 13.5.sp)
                }
            }

            overlayLookup.places.take(5).forEach { place ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.cardContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onApply(
                                CaptionOverlayConfig(noteType = "location", icon = "📍", previewText = place.name),
                                place.name
                            )
                        }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("📍 ${place.name}", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        if (place.address.isNotBlank()) {
                            Text(place.address, fontSize = 11.5.sp, color = palette.mutedText)
                        }
                    }
                }
            }

            // 5. Server Caption Presets
            if (overlayLookup.presets.isNotEmpty()) {
                Text("✨ Mẫu Caption gợi ý", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    overlayLookup.presets.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = palette.cardContainer,
                            modifier = Modifier.clickable {
                                onApply(
                                    CaptionOverlayConfig(
                                        noteType = "caption",
                                        icon = preset.icon,
                                        textColor = preset.textColor,
                                        colors = preset.colors,
                                        previewText = preset.label
                                    ),
                                    preset.label
                                )
                            }
                        ) {
                            Text(
                                text = listOfNotNull(preset.icon, preset.label).joinToString(" "),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DraftVideoPlayer(videoFile: File) {
    val context = LocalContext.current
    val player = remember(videoFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun OutboxFloatingStatusCard(
    items: List<OutboxEntity>,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    val palette = LocalLocketPalette.current
    val primary = items.firstOrNull() ?: return
    val isFailed = primary.status == OutboxEntity.STATUS_FAILED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.cardContainer)
            .border(1.dp, if (isFailed) Color(0xFFFF5C6C) else palette.cardBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (items.size > 1) "${primary.statusMessage} (+${items.size - 1} mục chờ)"
                    else primary.statusMessage,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFailed) Color(0xFFFF6B7A) else Color.White
                )
                if (!primary.errorMessage.isNullOrBlank()) {
                    Text(
                        text = primary.errorMessage,
                        fontSize = 11.5.sp,
                        color = palette.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isFailed) {
                TextButton(onClick = { onRetry(primary.id) }) {
                    Text("Thử lại", color = palette.spec.accent, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = { onCancel(primary.id) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Hủy", modifier = Modifier.size(16.dp))
            }
        }
        if (!isFailed) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (primary.progressPercent / 100f).coerceIn(0.03f, 1f) },
                color = palette.spec.accent,
                trackColor = Color.White.copy(alpha = 0.12f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
            )
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalLocketPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.spec.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (selected) palette.spec.onAccent else Color.White
        )
    }
}
