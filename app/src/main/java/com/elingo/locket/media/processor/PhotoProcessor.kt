package com.elingo.locket.media.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.elingo.locket.media.filter.LocketColorFilter
import com.elingo.locket.media.filter.LocketColorFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Off-main-thread 1:1 Photo Processor & Binary-Search Compressor (matches module 5524 in 941.e2a6ca53.chunk.js):
 * - Reads EXIF rotation & mirror flags accurately.
 * - Center-crops exact 1:1 square Locket ratio.
 * - Applies hardware-accelerated Skia ColorMatrix + Blend tint for all 10 Locket filters.
 * - Compresses under 1,040,384 bytes (< 1 MB) at up to 1920x1920 so uploads go straight via Tier-1 Direct GCS PUT.
 */
object PhotoProcessor {

    const val MAX_INPUT_IMAGE_BYTES = 20_971_520L // 20 MB
    private const val TARGET_PUBLIC_MAX_BYTES = 1_040_384 // ~1.0 MB (Tier-1 GCS threshold is 1,048,575 B)
    private const val MAX_PUBLIC_DIMENSION = 1920

    suspend fun processImageFile(
        context: Context,
        sourceFile: File,
        filterId: String = "none",
        forPrivate: Boolean = false
    ): File = withContext(Dispatchers.Default) {
        val filter = LocketColorFilters.byId(filterId)

        // Fast bypass if already 1:1 JPEG < 1 MB with no filter
        if (!forPrivate && filter.isIdentity && sourceFile.length() < TARGET_PUBLIC_MAX_BYTES) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            val exifRot = readExifTransform(sourceFile.absolutePath)
            if (bounds.outWidth > 0 && bounds.outWidth == bounds.outHeight &&
                bounds.outWidth <= MAX_PUBLIC_DIMENSION && exifRot.first == 0 && !exifRot.second
            ) {
                return@withContext sourceFile
            }
        }

        val maxTargetDim = if (forPrivate) 3072 else MAX_PUBLIC_DIMENSION
        val decoded = decodeSampledBitmapFromFile(sourceFile.absolutePath, maxTargetDim)
            ?: throw IllegalStateException("Không giải mã được tệp ảnh")

        val (rotationDeg, flipHorizontal) = readExifTransform(sourceFile.absolutePath)
        val squareFiltered = cropSquareAndFilter(
            source = decoded,
            rotationDegrees = rotationDeg,
            flipHorizontal = flipHorizontal,
            maxDimension = maxTargetDim,
            filter = filter,
            forPrivate = forPrivate
        )
        if (squareFiltered !== decoded) {
            decoded.recycle()
        }

        val outDir = File(context.filesDir, "outbox_media").apply { mkdirs() }
        val resultFile = compressBitmapToTarget(
            bitmap = squareFiltered,
            outDir = outDir,
            forPrivate = forPrivate
        )
        squareFiltered.recycle()
        resultFile
    }

    suspend fun copyUriToTempFile(context: Context, uri: Uri, prefix: String = "picked"): File =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val ext = when {
                mime.contains("mp4") -> "mp4"
                mime.contains("quicktime") || mime.contains("mov") -> "mov"
                mime.contains("webm") -> "webm"
                mime.contains("video") -> "mp4"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val dir = File(context.cacheDir, "picked").apply { mkdirs() }
            val out = File(dir, "${prefix}_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Không đọc được tệp đã chọn")
            out
        }

    fun applyFilterToBitmap(source: Bitmap, filter: LocketColorFilter): Bitmap {
        if (filter.isIdentity) return source.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(filter.toAndroidColorMatrix())
        }
        canvas.drawBitmap(source, 0f, 0f, paint)

        filter.tint?.let { tint ->
            val tintPaint = Paint().apply {
                color = tint.androidColor
                xfermode = PorterDuffXfermode(
                    if (tint.isScreenMode) PorterDuff.Mode.SCREEN else PorterDuff.Mode.OVERLAY
                )
            }
            canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), tintPaint)
        }
        return out
    }

    private fun cropSquareAndFilter(
        source: Bitmap,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
        maxDimension: Int,
        filter: LocketColorFilter,
        forPrivate: Boolean
    ): Bitmap {
        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (flipHorizontal) {
            matrix.postScale(-1f, 1f)
        }

        val oriented = if (rotationDegrees != 0 || flipHorizontal) {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }

        // Crop exact 1:1 center square for Locket card format
        val side = min(oriented.width, oriented.height)
        val cropX = (oriented.width - side) / 2
        val cropY = (oriented.height - side) / 2

        val targetSide = if (forPrivate) {
            val max5MpSide = (side * sqrt(5_000_000.0 / (side.toDouble() * side.toDouble()))).roundToInt()
            min(side, min(maxDimension, max(1080, max5MpSide)))
        } else {
            min(side, maxDimension)
        }

        val out = Bitmap.createBitmap(targetSide, targetSide, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val srcRect = android.graphics.Rect(cropX, cropY, cropX + side, cropY + side)
        val dstRect = android.graphics.Rect(0, 0, targetSide, targetSide)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (!filter.isIdentity) {
                colorFilter = ColorMatrixColorFilter(filter.toAndroidColorMatrix())
            }
        }
        canvas.drawBitmap(oriented, srcRect, dstRect, paint)

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

        if (oriented !== source) {
            oriented.recycle()
        }
        return out
    }

    private fun compressBitmapToTarget(
        bitmap: Bitmap,
        outDir: File,
        forPrivate: Boolean
    ): File {
        if (forPrivate) {
            val outFile = File(outDir, "locket_priv_${System.currentTimeMillis()}.jpg")
            outFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            return outFile
        }

        // Binary search JPEG quality at current resolution (up to 1920x1920)
        val qualities = intArrayOf(96, 93, 90, 86, 82, 78, 74)
        var lo = 0
        var hi = qualities.lastIndex
        var bestBytes: ByteArray? = null
        var smallestBytes: ByteArray? = null

        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, qualities[mid], baos)
            val bytes = baos.toByteArray()
            if (smallestBytes == null || bytes.size < smallestBytes.size) {
                smallestBytes = bytes
            }
            if (bytes.size <= TARGET_PUBLIC_MAX_BYTES) {
                bestBytes = bytes
                hi = mid - 1 // Try higher quality (earlier index)
            } else {
                lo = mid + 1 // Need stronger compression (later index)
            }
        }

        if (bestBytes == null) {
            // Fallback downscale ladder matching c = [[1600, 80], [1440, 76], [1280, 72], [1080, 68]]
            val ladder = listOf(1600 to 80, 1440 to 76, 1280 to 72, 1080 to 68)
            for ((dim, q) in ladder) {
                if (bitmap.width <= dim) continue
                val scaled = Bitmap.createScaledBitmap(bitmap, dim, dim, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, baos)
                scaled.recycle()
                val bytes = baos.toByteArray()
                if (smallestBytes == null || bytes.size < smallestBytes.size) {
                    smallestBytes = bytes
                }
                if (bytes.size <= TARGET_PUBLIC_MAX_BYTES) {
                    bestBytes = bytes
                    break
                }
            }
        }

        val finalBytes = bestBytes ?: smallestBytes ?: ByteArray(0)
        val outFile = File(outDir, "locket_img_${System.currentTimeMillis()}.jpg")
        outFile.writeBytes(finalBytes)
        return outFile
    }

    private fun decodeSampledBitmapFromFile(path: String, reqMaxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val minSide = min(opts.outWidth, opts.outHeight)
        var sample = 1
        while (minSide / (sample * 2) >= reqMaxSide) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, decodeOpts)
    }

    private fun readExifTransform(path: String): Pair<Int, Boolean> {
        return runCatching {
            val exif = ExifInterface(path)
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
    }
}

