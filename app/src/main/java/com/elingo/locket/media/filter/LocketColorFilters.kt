package com.elingo.locket.media.filter

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import kotlin.math.roundToInt
import android.graphics.ColorMatrix as AndroidColorMatrix
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

data class FilterTint(
    val colorHex: String,
    val opacity: Float,
    val isScreenMode: Boolean = false
) {
    val androidColor: Int
        get() {
            val base = Color.parseColor(colorHex)
            val alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
            return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
        }

    val composeColor: ComposeColor
        get() = ComposeColor(androidColor)
}

/**
 * All 10 official Locket Photobooth color filters extracted from module 3790 (941.e2a6ca53.chunk.js),
 * mapped directly to Hardware Skia ColorMatrix (Photo/Compose) and OpenGL 4x4 RgbMatrix (Media3 GPU Video).
 */
data class LocketColorFilter(
    val id: String,
    val name: String,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val sepia: Float = 0f,
    val grayscale: Float = 0f,
    val tint: FilterTint? = null
) {
    val isIdentity: Boolean
        get() = id == "none"

    /**
     * Computes a 3x3 linear RGB weight matrix + normalized [0..1] RGB bias vector.
     */
    private fun computeMatrix3x3AndBias(): Pair<FloatArray, FloatArray> {
        // Start with 3x3 identity
        var m = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        fun mul3x3(next: FloatArray, cur: FloatArray): FloatArray {
            val out = FloatArray(9)
            for (r in 0..2) {
                for (c in 0..2) {
                    out[r * 3 + c] =
                        next[r * 3 + 0] * cur[0 * 3 + c] +
                        next[r * 3 + 1] * cur[1 * 3 + c] +
                        next[r * 3 + 2] * cur[2 * 3 + c]
                }
            }
            return out
        }

        if (sepia > 0f) {
            val s = 1f - sepia.coerceIn(0f, 1f)
            val sepMat = floatArrayOf(
                0.393f + 0.607f * s, 0.769f - 0.769f * s, 0.189f - 0.189f * s,
                0.349f - 0.349f * s, 0.686f + 0.314f * s, 0.168f - 0.168f * s,
                0.272f - 0.272f * s, 0.534f - 0.534f * s, 0.131f + 0.869f * s
            )
            m = mul3x3(sepMat, m)
        }

        if (grayscale > 0f) {
            val g = 1f - grayscale.coerceIn(0f, 1f)
            val grayMat = floatArrayOf(
                0.2126f + 0.7874f * g, 0.7152f - 0.7152f * g, 0.0722f - 0.0722f * g,
                0.2126f - 0.2126f * g, 0.7152f + 0.2848f * g, 0.0722f - 0.0722f * g,
                0.2126f - 0.2126f * g, 0.7152f - 0.7152f * g, 0.0722f + 0.9278f * g
            )
            m = mul3x3(grayMat, m)
        }

        if (saturation != 1f) {
            val t = saturation
            val satMat = floatArrayOf(
                0.213f + 0.787f * t, 0.715f - 0.715f * t, 0.072f - 0.072f * t,
                0.213f - 0.213f * t, 0.715f + 0.285f * t, 0.072f - 0.072f * t,
                0.213f - 0.213f * t, 0.715f - 0.715f * t, 0.072f + 0.928f * t
            )
            m = mul3x3(satMat, m)
        }

        // Apply contrast & brightness: out = brightness * (contrast * x + (0.5 - 0.5 * contrast))
        val scale = contrast * brightness
        val baseOffset = brightness * (0.5f - 0.5f * contrast)
        for (i in m.indices) {
            m[i] *= scale
        }

        val bias = floatArrayOf(baseOffset, baseOffset, baseOffset)

        // Fold subtle tint approximation into linear matrix + bias for single-pass GPU shaders
        if (tint != null) {
            val parsed = Color.parseColor(tint.colorHex)
            val tr = Color.red(parsed) / 255f
            val tg = Color.green(parsed) / 255f
            val tb = Color.blue(parsed) / 255f
            val a = (tint.opacity * if (tint.isScreenMode) 0.45f else 0.25f).coerceIn(0f, 0.5f)
            val keep = 1f - a
            for (c in 0..2) {
                m[0 * 3 + c] *= keep
                m[1 * 3 + c] *= keep
                m[2 * 3 + c] *= keep
            }
            bias[0] = bias[0] * keep + tr * a
            bias[1] = bias[1] * keep + tg * a
            bias[2] = bias[2] * keep + tb * a
        }

        return m to bias
    }

    fun to4x5Array(): FloatArray {
        val (m, bias) = computeMatrix3x3AndBias()
        return floatArrayOf(
            m[0], m[1], m[2], 0f, bias[0] * 255f,
            m[3], m[4], m[5], 0f, bias[1] * 255f,
            m[6], m[7], m[8], 0f, bias[2] * 255f,
            0f,   0f,   0f,   1f, 0f
        )
    }

    fun toAndroidColorMatrix(): AndroidColorMatrix = AndroidColorMatrix(to4x5Array())

    fun toComposeColorMatrix(): ComposeColorMatrix = ComposeColorMatrix(to4x5Array())

    /**
     * OpenGL column-major 4x4 matrix for AndroidX Media3 Transformer GPU shader pipeline.
     * Since video frame alpha == 1.0, column 3 holds the normalized RGB bias offset!
     */
    @OptIn(UnstableApi::class)
    fun toMedia3RgbMatrix(): RgbMatrix {
        val (m, bias) = computeMatrix3x3AndBias()
        val glColumnMajor = floatArrayOf(
            m[0],    m[3],    m[6],    0f, // Column 0 (R_in)
            m[1],    m[4],    m[7],    0f, // Column 1 (G_in)
            m[2],    m[5],    m[8],    0f, // Column 2 (B_in)
            bias[0], bias[1], bias[2], 1f  // Column 3 (A_in = 1.0 -> additive RGB offset)
        )
        return RgbMatrix { _, _ -> glColumnMajor }
    }
}

object LocketColorFilters {
    val ALL: List<LocketColorFilter> = listOf(
        LocketColorFilter(id = "none", name = "Gốc"),
        LocketColorFilter(
            id = "clear",
            name = "Trong trẻo",
            contrast = 1.12f,
            saturation = 1.30f,
            brightness = 1.03f,
            tint = FilterTint("#7EC8FF", opacity = 0.15f)
        ),
        LocketColorFilter(
            id = "warm",
            name = "Ấm áp",
            saturation = 1.15f,
            contrast = 1.05f,
            brightness = 1.04f,
            tint = FilterTint("#FF9D4D", opacity = 0.35f)
        ),
        LocketColorFilter(
            id = "cool",
            name = "Mát lạnh",
            saturation = 0.95f,
            contrast = 1.05f,
            brightness = 1.04f,
            tint = FilterTint("#4D9DFF", opacity = 0.35f)
        ),
        LocketColorFilter(
            id = "golden",
            name = "Nắng",
            sepia = 0.20f,
            saturation = 1.35f,
            contrast = 1.06f,
            brightness = 1.04f,
            tint = FilterTint("#FFBE5C", opacity = 0.40f)
        ),
        LocketColorFilter(
            id = "rosy",
            name = "Hồng",
            saturation = 1.10f,
            contrast = 1.02f,
            brightness = 1.05f,
            tint = FilterTint("#FF6F9F", opacity = 0.35f)
        ),
        LocketColorFilter(
            id = "vintage",
            name = "Hoài cổ",
            sepia = 0.30f,
            contrast = 0.88f,
            brightness = 1.08f,
            saturation = 1.20f,
            tint = FilterTint("#3A2A1A", opacity = 0.50f, isScreenMode = true)
        ),
        LocketColorFilter(
            id = "film",
            name = "Phim",
            contrast = 0.90f,
            saturation = 0.85f,
            brightness = 1.05f,
            tint = FilterTint("#1C2C40", opacity = 0.60f, isScreenMode = true)
        ),
        LocketColorFilter(
            id = "mono",
            name = "Đen trắng",
            grayscale = 1.0f,
            contrast = 1.10f,
            brightness = 1.04f
        ),
        LocketColorFilter(
            id = "noir",
            name = "Noir",
            grayscale = 1.0f,
            contrast = 1.50f,
            brightness = 0.95f
        )
    )

    fun byId(id: String?): LocketColorFilter {
        return ALL.firstOrNull { it.id == id } ?: ALL.first()
    }

    fun next(currentId: String, step: Int): LocketColorFilter {
        val idx = ALL.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val nextIdx = (idx + step % ALL.size + ALL.size) % ALL.size
        return ALL[nextIdx]
    }
}

