package com.elingo.locket.media.processor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.elingo.locket.media.filter.LocketColorFilters
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoMetadataInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMs: Long
) {
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    val isSquare: Boolean
        get() = displayWidth > 0 && kotlin.math.abs(displayWidth - displayHeight) <= 4
}

/**
 * GPU & Hardware MediaCodec Video Processor powered by AndroidX Media3 Transformer (Step 3 Core):
 * - Crops 1:1 square directly in OpenGL ES via Media3 Crop + Presentation effects (zero CPU frame loops).
 * - Applies Locket Color Filters via 4x4 GPU RgbMatrix shader.
 * - Encodes using Hardware H.264/AVC + AAC with dynamic target bitrate so output MP4 fits under 5.66 MB
 *   (hard ceiling 6.0 MB for Tier-1 Direct GCS upload).
 * - Bypasses re-encoding completely when a live CameraX hardware recording already meets 1:1 H.264 < 6 MB!
 */
object Media3VideoProcessor {

    const val MAX_INPUT_VIDEO_BYTES = 209_715_200L // 200 MB
    const val MAX_VIDEO_DURATION_MS = 60_500L      // 60 seconds
    const val TIER1_MAX_VIDEO_BYTES = 6_291_456L   // 6 MB exact ceiling (module 3082: r = 6291456)
    private const val TARGET_VIDEO_BYTES = 5_662_310L // ~5.4 MB safety target (module 3082: a = 5662310)

    suspend fun inspectVideo(file: File): VideoMetadataInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            VideoMetadataInfo(width = w, height = h, rotationDegrees = rot, durationMs = dur)
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun extractVideoThumbnailFile(context: Context, videoFile: File): File? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val rawFrame = retriever.getFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return@withContext null

            val side = min(rawFrame.width, rawFrame.height)
            val cropX = (rawFrame.width - side) / 2
            val cropY = (rawFrame.height - side) / 2
            val square = Bitmap.createBitmap(rawFrame, cropX, cropY, side, side)
            val thumbSide = min(480, side)
            val scaled = if (square.width != thumbSide) {
                Bitmap.createScaledBitmap(square, thumbSide, thumbSide, true).also { square.recycle() }
            } else {
                square
            }
            if (rawFrame !== scaled) rawFrame.recycle()

            val dir = File(context.filesDir, "outbox_thumbs").apply { mkdirs() }
            val out = File(dir, "thumb_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            scaled.recycle()
            out
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun processVideoForLocket(
        context: Context,
        inputFile: File,
        filterId: String = "none",
        forPrivate: Boolean = false,
        onProgress: (Float) -> Unit
    ): File {
        val meta = inspectVideo(inputFile)
        val filter = LocketColorFilters.byId(filterId)

        // Zero-reencode fast path: if CameraX already produced a 1:1 H.264 MP4 < 5.95 MB with no filter & no rotation
        if (meta.isSquare &&
            meta.rotationDegrees == 0 &&
            max(meta.displayWidth, meta.displayHeight) <= 1920 &&
            filter.isIdentity &&
            inputFile.extension.equals("mp4", ignoreCase = true) &&
            (forPrivate || inputFile.length() < 6_100_000L)
        ) {
            onProgress(1f)
            return inputFile
        }

        val durationSec = (meta.durationMs.coerceAtLeast(1000L) / 1000.0)
        val outDir = File(context.filesDir, "outbox_media").apply { mkdirs() }

        // Up to 2 passes ([1.0, 0.78]) only if an unusually complex scene slightly exceeds 6 MB on pass 1
        val bitrateMultipliers = if (forPrivate) listOf(1.0) else listOf(1.0, 0.78)

        for ((passIdx, factor) in bitrateMultipliers.withIndex()) {
            val audioBitrate = if (durationSec <= 24.0) 96_000 else 64_000
            val targetVideoBitrate = if (forPrivate) {
                3_500_000
            } else {
                val rawBps = ((8.0 * TARGET_VIDEO_BYTES / durationSec) * factor).toLong() - audioBitrate
                rawBps.coerceIn(320_000L, 4_200_000L).toInt()
            }

            val sourceMinSide = min(
                meta.displayWidth.takeIf { it > 0 } ?: 1080,
                meta.displayHeight.takeIf { it > 0 } ?: 1080
            )
            val maxAllowedSide = if (targetVideoBitrate >= 2_000_000) 1080 else 720
            val targetSquareSide = makeEven(min(sourceMinSide, maxAllowedSide).coerceAtLeast(480))

            val outputFile = File(outDir, "locket_vid_${System.currentTimeMillis()}_p$passIdx.mp4")

            val succeeded = runSingleMedia3GpuTransform(
                context = context,
                inputFile = inputFile,
                outputFile = outputFile,
                meta = meta,
                targetSquareSide = targetSquareSide,
                targetVideoBitrate = targetVideoBitrate,
                filterId = filterId,
                onProgress = onProgress
            )

            if (succeeded && outputFile.exists() && outputFile.length() > 0L) {
                if (forPrivate || outputFile.length() < TIER1_MAX_VIDEO_BYTES || passIdx == bitrateMultipliers.lastIndex) {
                    onProgress(1f)
                    return outputFile
                } else {
                    outputFile.delete()
                }
            }
        }

        // Safe fallback: return original file (ThreeTierMediaUploader will automatically switch to Tier-2 1MB Chunked Upload!)
        return inputFile
    }

    @OptIn(UnstableApi::class)
    private suspend fun runSingleMedia3GpuTransform(
        context: Context,
        inputFile: File,
        outputFile: File,
        meta: VideoMetadataInfo,
        targetSquareSide: Int,
        targetVideoBitrate: Int,
        filterId: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        // Note: Media3 Transformer must be instantiated, started, and polled from a single Looper thread (Main),
        // while all actual demuxing, OpenGL GPU shaders, and MediaCodec H.264 encoding execute on Media3's internal HandlerThread!
        val videoEffects = mutableListOf<Effect>()

        // 1. Hardware GPU 1:1 Center Crop in normalized [-1f, 1f] OpenGL coordinates
        val dw = meta.displayWidth.toFloat()
        val dh = meta.displayHeight.toFloat()
        if (dw > 0f && dh > 0f && kotlin.math.abs(dw - dh) > 2f) {
            if (dw > dh) {
                val halfSpan = (dh / dw).coerceIn(0.1f, 1f)
                videoEffects.add(Crop(-halfSpan, halfSpan, -1f, 1f))
            } else {
                val halfSpan = (dw / dh).coerceIn(0.1f, 1f)
                videoEffects.add(Crop(-1f, 1f, -halfSpan, halfSpan))
            }
        }

        // 2. Scale to exact 1:1 square target resolution (e.g. 1080x1080 or 720x720) on GPU
        videoEffects.add(
            Presentation.createForWidthAndHeight(
                targetSquareSide,
                targetSquareSide,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
        )

        // 3. Apply Locket Color Filter via 4x4 GPU RgbMatrix shader
        val filter = LocketColorFilters.byId(filterId)
        if (!filter.isIdentity) {
            videoEffects.add(filter.toMedia3RgbMatrix())
        }

        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetVideoBitrate)
                    .build()
            )
            .setEnableFallback(true)
            .build()

        val doneSignal = CompletableDeferred<Boolean>()
        var continuationRef: CancellableContinuation<Boolean>? = null

        val transformer = Transformer.Builder(context.applicationContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    doneSignal.complete(true)
                    continuationRef?.takeIf { it.isActive }?.resume(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    doneSignal.complete(false)
                    continuationRef?.takeIf { it.isActive }?.resumeWithException(exportException)
                }
            })
            .build()

        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        if (outputFile.exists()) outputFile.delete()
        transformer.start(editedItem, outputFile.absolutePath)

        // Poll progress smoothly every 120ms until export completes
        val progressJob = launch {
            val holder = ProgressHolder()
            while (!doneSignal.isCompleted) {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress((holder.progress / 100f).coerceIn(0f, 0.99f))
                }
                delay(120L)
            }
        }

        try {
            suspendCancellableCoroutine { cont ->
                continuationRef = cont
                cont.invokeOnCancellation {
                    runCatching { transformer.cancel() }
                }
            }
        } catch (_: Throwable) {
            false
        } finally {
            progressJob.cancel()
        }
    }

    private fun makeEven(value: Int): Int = max(2, ((value / 2f).roundToInt()) * 2)
}

