package com.elingo.locket.media.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.exifinterface.media.ExifInterface
import com.elingo.locket.core.model.SupporterStatus
import com.elingo.locket.media.filter.LocketColorFilter
import com.elingo.locket.media.filter.LocketColorFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.min

enum class PhotoboothLayout(
    val id: String,
    val title: String,
    val subtitle: String
) {
    GRID_2X2("grid", "Lưới 2×2", "4 ô vuông cổ điển"),
    TWIN_STRIPS("strips", "Dải đôi", "2 cột photobooth"),
    HERO_1_3("hero", "Tiêu điểm 1+3", "1 ảnh lớn + 3 ảnh phụ")
}

data class PhotoboothFrame(
    val id: String,
    val name: String,
    val primaryHex: String,
    val secondaryHex: String? = null,
    val textColorHex: String = "#1F1F24",
    val pattern: String = "solid", // solid, gradient, filmstrip, polka
    val requiredTier: String? = null,
    val perkId: String? = null
)

data class PhotoboothOutput(
    val file: File,
    val isVideo: Boolean,
    val usedPerks: List<String>
)

/**
 * Hardware-friendly 4-Shot Photobooth Composer (Still JPEG & Hardware MediaCodec Stop-Motion H.264 MP4).
 * Respects server supporter tier access control (`SupporterStatus.canUseTier`) without bypassing any checks.
 */
object PhotoboothComposer {

    const val REQUIRED_SHOTS = 4
    private const val STILL_CANVAS_SIZE = 1600
    private const val VIDEO_CANVAS_SIZE = 1080
    private const val STOP_MOTION_FPS = 12
    private const val STOP_MOTION_TOTAL_FRAMES = 60 // 5.0 seconds @ 12 FPS
    private const val STOP_MOTION_BITRATE = 2_200_000

    val FRAMES: List<PhotoboothFrame> = listOf(
        // Standard free frames
        PhotoboothFrame("white", "Trắng", "#FFFFFF", textColorHex = "#1A1A1E"),
        PhotoboothFrame("black", "Đen", "#141418", textColorHex = "#F5F5F7"),
        PhotoboothFrame("cream", "Kem", "#FAF3E3", textColorHex = "#3E3224"),
        PhotoboothFrame("pink", "Phấn hồng", "#FFE4EC", textColorHex = "#5C2335"),
        PhotoboothFrame("sky", "Mây xanh", "#E3F2FD", textColorHex = "#1B3B5A"),
        PhotoboothFrame("mint", "Bạc hà", "#E2F7EE", textColorHex = "#184A36"),
        // Supporter tier ("booth-1") frames - legitimately gated by SupporterStatus
        PhotoboothFrame(
            id = "sunset",
            name = "Hoàng hôn",
            primaryHex = "#FF9A8B",
            secondaryHex = "#FF6A88",
            textColorHex = "#FFFFFF",
            pattern = "gradient",
            requiredTier = "booth-1",
            perkId = "booth-frame-sunset"
        ),
        PhotoboothFrame(
            id = "aurora",
            name = "Cực quang",
            primaryHex = "#A18CD1",
            secondaryHex = "#FBC2EB",
            textColorHex = "#FFFFFF",
            pattern = "gradient",
            requiredTier = "booth-1",
            perkId = "booth-frame-aurora"
        ),
        PhotoboothFrame(
            id = "filmstrip",
            name = "Cuộn phim",
            primaryHex = "#101114",
            secondaryHex = "#24262D",
            textColorHex = "#F4C95D",
            pattern = "filmstrip",
            requiredTier = "booth-1",
            perkId = "booth-frame-filmstrip"
        ),
        PhotoboothFrame(
            id = "polka",
            name = "Chấm bi",
            primaryHex = "#FFF0F5",
            secondaryHex = "#FFB6C9",
            textColorHex = "#6B2D44",
            pattern = "polka",
            requiredTier = "booth-1",
            perkId = "booth-frame-polka"
        )
    )

    suspend fun composePhotoboothStill(
        context: Context,
        shotFiles: List<File>,
        layout: PhotoboothLayout,
        frame: PhotoboothFrame,
        filterId: String,
        footerNote: String,
        supporterStatus: SupporterStatus
    ): PhotoboothOutput = withContext(Dispatchers.Default) {
        require(shotFiles.size >= REQUIRED_SHOTS) { "Cần đủ 4 ảnh cho chế độ Photobooth" }
        if (!supporterStatus.canUseTier(frame.requiredTier)) {
            throw IllegalStateException("Khung '${frame.name}' dành cho Supporter (${frame.requiredTier})")
        }

        val filter = LocketColorFilters.byId(filterId)
        val preparedShots = shotFiles.take(REQUIRED_SHOTS).map { file ->
            decodeSquareFilteredShot(file, targetSide = 820, filter = filter)
        }

        val composite = Bitmap.createBitmap(STILL_CANVAS_SIZE, STILL_CANVAS_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        renderBoard(
            canvas = canvas,
            size = STILL_CANVAS_SIZE,
            shots = preparedShots,
            layout = layout,
            frame = frame,
            footerNote = footerNote,
            highlightedSlot = null
        )
        preparedShots.forEach { it.recycle() }

        val tempDir = File(context.cacheDir, "photobooth").apply { mkdirs() }
        val rawOut = File(tempDir, "booth_raw_${System.currentTimeMillis()}.jpg")
        rawOut.outputStream().use { out ->
            composite.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        composite.recycle()

        val compressed = PhotoProcessor.processImageFile(
            context = context,
            sourceFile = rawOut,
            filterId = "none",
            forPrivate = false
        )
        if (compressed.absolutePath != rawOut.absolutePath) {
            rawOut.delete()
        }

        PhotoboothOutput(
            file = compressed,
            isVideo = false,
            usedPerks = listOfNotNull(frame.perkId)
        )
    }

    /**
     * Encodes a 5.0s (60 frames @ 12 FPS) 1080x1080 Stop-Motion H.264 MP4 directly via Hardware MediaCodec Surface.
     * Legitimately requires `booth-1` Supporter tier as defined by server rules.
     */
    suspend fun composePhotoboothStopMotionVideo(
        context: Context,
        shotFiles: List<File>,
        layout: PhotoboothLayout,
        frame: PhotoboothFrame,
        filterId: String,
        footerNote: String,
        supporterStatus: SupporterStatus,
        onProgress: (Float) -> Unit = {}
    ): PhotoboothOutput = withContext(Dispatchers.Default) {
        require(shotFiles.size >= REQUIRED_SHOTS) { "Cần đủ 4 ảnh cho video Stop-Motion" }
        if (!supporterStatus.canUseTier("booth-1") || !supporterStatus.canUseTier(frame.requiredTier)) {
            throw IllegalStateException("Xuất video Photobooth Stop-Motion yêu cầu quyền Supporter (booth-1)")
        }

        val filter = LocketColorFilters.byId(filterId)
        val preparedShots = shotFiles.take(REQUIRED_SHOTS).map { file ->
            decodeSquareFilteredShot(file, targetSide = 640, filter = filter)
        }

        val outDir = File(context.filesDir, "outbox_media").apply { mkdirs() }
        val outputFile = File(outDir, "locket_booth_${System.currentTimeMillis()}.mp4")

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            VIDEO_CANVAS_SIZE,
            VIDEO_CANVAS_SIZE
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, STOP_MOTION_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, STOP_MOTION_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder.signalEndOfInputStream()
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val encodedData: ByteBuffer? = encoder.getOutputBuffer(outIndex)
                            if (encodedData != null && bufferInfo.size > 0 && muxerStarted &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
            }

            // Pre-render the 4 rotated stop-motion boards so encoding 60 frames is instantaneous & cool
            val cycleBitmaps = (0 until REQUIRED_SHOTS).map { cycleIdx ->
                val rotatedShots = List(REQUIRED_SHOTS) { slot ->
                    preparedShots[(slot + cycleIdx) % REQUIRED_SHOTS]
                }
                Bitmap.createBitmap(VIDEO_CANVAS_SIZE, VIDEO_CANVAS_SIZE, Bitmap.Config.ARGB_8888).also { bmp ->
                    renderBoard(
                        canvas = Canvas(bmp),
                        size = VIDEO_CANVAS_SIZE,
                        shots = rotatedShots,
                        layout = layout,
                        frame = frame,
                        footerNote = footerNote,
                        highlightedSlot = cycleIdx % REQUIRED_SHOTS
                    )
                }
            }

            for (frameIdx in 0 until STOP_MOTION_TOTAL_FRAMES) {
                // Switch active stop-motion pose every 6 frames (0.5s per step)
                val step = (frameIdx / 6) % REQUIRED_SHOTS
                val surfaceCanvas = inputSurface.lockCanvas(null)
                try {
                    surfaceCanvas.drawBitmap(cycleBitmaps[step], 0f, 0f, null)
                } finally {
                    inputSurface.unlockCanvasAndPost(surfaceCanvas)
                }
                drainEncoder(endOfStream = false)
                onProgress((frameIdx + 1).toFloat() / STOP_MOTION_TOTAL_FRAMES.toFloat())
            }

            drainEncoder(endOfStream = true)
            cycleBitmaps.forEach { it.recycle() }
            inputSurface.release()
        } finally {
            preparedShots.forEach { if (!it.isRecycled) it.recycle() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }

        val perks = buildList {
            add("booth-video")
            frame.perkId?.let(::add)
        }
        PhotoboothOutput(
            file = outputFile,
            isVideo = true,
            usedPerks = perks
        )
    }

    private fun renderBoard(
        canvas: Canvas,
        size: Int,
        shots: List<Bitmap>,
        layout: PhotoboothLayout,
        frame: PhotoboothFrame,
        footerNote: String,
        highlightedSlot: Int?
    ) {
        val fSize = size.toFloat()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Background / Frame Pattern
        when (frame.pattern) {
            "gradient" -> {
                val c1 = Color.parseColor(frame.primaryHex)
                val c2 = Color.parseColor(frame.secondaryHex ?: frame.primaryHex)
                bgPaint.shader = LinearGradient(0f, 0f, fSize, fSize, c1, c2, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, fSize, fSize, bgPaint)
            }
            "filmstrip" -> {
                bgPaint.color = Color.parseColor(frame.primaryHex)
                canvas.drawRect(0f, 0f, fSize, fSize, bgPaint)
                val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(frame.secondaryHex ?: "#2A2C34")
                }
                val holeW = fSize * 0.024f
                val holeH = fSize * 0.036f
                val margin = fSize * 0.016f
                var y = fSize * 0.05f
                while (y < fSize - holeH * 2) {
                    canvas.drawRoundRect(RectF(margin, y, margin + holeW, y + holeH), 6f, 6f, holePaint)
                    canvas.drawRoundRect(RectF(fSize - margin - holeW, y, fSize - margin, y + holeH), 6f, 6f, holePaint)
                    y += holeH * 1.8f
                }
            }
            "polka" -> {
                bgPaint.color = Color.parseColor(frame.primaryHex)
                canvas.drawRect(0f, 0f, fSize, fSize, bgPaint)
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(frame.secondaryHex ?: "#FFB6C9")
                    alpha = 95
                }
                val step = fSize / 12f
                val radius = fSize * 0.011f
                var row = 0
                var y = step / 2f
                while (y < fSize) {
                    var x = if (row % 2 == 0) step / 2f else step
                    while (x < fSize) {
                        canvas.drawCircle(x, y, radius, dotPaint)
                        x += step
                    }
                    y += step
                    row++
                }
            }
            else -> {
                bgPaint.color = Color.parseColor(frame.primaryHex)
                canvas.drawRect(0f, 0f, fSize, fSize, bgPaint)
            }
        }

        // 2. Compute 4 slot rectangles
        val outerPad = if (frame.pattern == "filmstrip") fSize * 0.062f else fSize * 0.05f
        val gap = fSize * 0.025f
        val footerHeight = fSize * 0.075f
        val contentLeft = outerPad
        val contentTop = outerPad
        val contentWidth = fSize - outerPad * 2f
        val contentHeight = fSize - outerPad * 2f - footerHeight
        val cornerRadius = fSize * 0.032f

        val rects: List<RectF> = when (layout) {
            PhotoboothLayout.GRID_2X2 -> {
                val cellW = (contentWidth - gap) / 2f
                val cellH = (contentHeight - gap) / 2f
                listOf(
                    RectF(contentLeft, contentTop, contentLeft + cellW, contentTop + cellH),
                    RectF(contentLeft + cellW + gap, contentTop, contentLeft + contentWidth, contentTop + cellH),
                    RectF(contentLeft, contentTop + cellH + gap, contentLeft + cellW, contentTop + contentHeight),
                    RectF(contentLeft + cellW + gap, contentTop + cellH + gap, contentLeft + contentWidth, contentTop + contentHeight)
                )
            }
            PhotoboothLayout.TWIN_STRIPS -> {
                val stripPad = contentWidth * 0.03f
                val colW = (contentWidth - gap * 1.5f - stripPad * 2f) / 2f
                val cellH = (contentHeight - gap) / 2f
                val leftX = contentLeft + stripPad
                val rightX = leftX + colW + gap * 1.5f
                listOf(
                    RectF(leftX, contentTop, leftX + colW, contentTop + cellH),
                    RectF(leftX, contentTop + cellH + gap, leftX + colW, contentTop + contentHeight),
                    RectF(rightX, contentTop, rightX + colW, contentTop + cellH),
                    RectF(rightX, contentTop + cellH + gap, rightX + colW, contentTop + contentHeight)
                )
            }
            PhotoboothLayout.HERO_1_3 -> {
                val heroH = contentHeight * 0.64f
                val smallH = contentHeight - heroH - gap
                val smallW = (contentWidth - gap * 2f) / 3f
                val smallTop = contentTop + heroH + gap
                listOf(
                    RectF(contentLeft, contentTop, contentLeft + contentWidth, contentTop + heroH),
                    RectF(contentLeft, smallTop, contentLeft + smallW, smallTop + smallH),
                    RectF(contentLeft + smallW + gap, smallTop, contentLeft + smallW * 2f + gap, smallTop + smallH),
                    RectF(contentLeft + (smallW + gap) * 2f, smallTop, contentLeft + contentWidth, smallTop + smallH)
                )
            }
        }

        // 3. Draw each shot center-cropped inside its rounded slot
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = fSize * 0.0045f
            color = Color.argb(45, 255, 255, 255)
        }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = fSize * 0.008f
            color = Color.parseColor("#FFB800")
        }

        rects.forEachIndexed { idx, dst ->
            val shot = shots[idx % shots.size]
            val slotRatio = dst.width() / dst.height()
            val bmpW = shot.width
            val bmpH = shot.height
            val srcRect = if (bmpW.toFloat() / bmpH.toFloat() > slotRatio) {
                val cropW = (bmpH * slotRatio).toInt()
                val offsetX = (bmpW - cropW) / 2
                Rect(offsetX, 0, offsetX + cropW, bmpH)
            } else {
                val cropH = (bmpW / slotRatio).toInt()
                val offsetY = (bmpH - cropH) / 2
                Rect(0, offsetY, bmpW, offsetY + cropH)
            }

            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(dst, cornerRadius, cornerRadius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            canvas.drawBitmap(shot, srcRect, dst, bmpPaint)
            canvas.restore()

            canvas.drawRoundRect(
                dst,
                cornerRadius,
                cornerRadius,
                if (highlightedSlot == idx) highlightPaint else borderPaint
            )
        }

        // 4. Footer Branding & Date Stamp
        val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val label = if (footerNote.isNotBlank()) {
            "${footerNote.trim()}  •  $dateStr"
        } else {
            "LOCKET PHOTOBOOTH  •  $dateStr"
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(frame.textColorHex)
            textSize = fSize * 0.025f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
        }
        val footerCenterY = fSize - outerPad * 0.72f
        canvas.drawText(label, fSize / 2f, footerCenterY, textPaint)
    }

    private fun decodeSquareFilteredShot(file: File, targetSide: Int, filter: LocketColorFilter): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val minRaw = min(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1))
        var sample = 1
        while (minRaw / (sample * 2) >= targetSide) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: Bitmap.createBitmap(targetSide, targetSide, Bitmap.Config.ARGB_8888)

        val (rot, flipX) = runCatching {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
                ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
                ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
                ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
                ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
                else -> 0 to false
            }
        }.getOrDefault(0 to false)

        val oriented = if (rot != 0 || flipX) {
            val m = Matrix().apply {
                if (rot != 0) postRotate(rot.toFloat())
                if (flipX) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val side = min(oriented.width, oriented.height)
        val cx = (oriented.width - side) / 2
        val cy = (oriented.height - side) / 2

        val out = Bitmap.createBitmap(targetSide, targetSide, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (!filter.isIdentity) {
                colorFilter = ColorMatrixColorFilter(filter.toAndroidColorMatrix())
            }
        }
        canvas.drawBitmap(
            oriented,
            Rect(cx, cy, cx + side, cy + side),
            Rect(0, 0, targetSide, targetSide),
            paint
        )
        if (!filter.isIdentity) {
            filter.tint?.let { tint ->
                val tintPaint = Paint().apply {
                    color = tint.androidColor
                    xfermode = PorterDuffXfermode(
                        if (tint.isScreenMode) PorterDuff.Mode.SCREEN else PorterDuff.Mode.OVERLAY
                    )
                }
                canvas.drawRect(0f, 0f, targetSide.toFloat(), targetSide.toFloat(), tintPaint)
            }
        }
        oriented.recycle()
        return out
    }
}

