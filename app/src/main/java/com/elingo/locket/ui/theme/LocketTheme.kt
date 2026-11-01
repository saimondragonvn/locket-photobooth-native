package com.elingo.locket.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class LocketThemeSpec(
    val id: String,
    val name: String,
    val accent: Color,
    val accentSecondary: Color,
    val background: Color,
    val surface: Color,
    val onAccent: Color = Color(0xFF101014)
)

@Immutable
data class LocketRuntimePalette(
    val spec: LocketThemeSpec,
    val isGlass: Boolean,
    val cardContainer: Color,
    val cardBorder: Color,
    val mutedText: Color = Color(0xFFA2A4B0)
)

val LocalLocketPalette = staticCompositionLocalOf {
    LocketRuntimePalette(
        spec = LocketThemes.GOLD,
        isGlass = true,
        cardContainer = Color(0xCC1C1D24),
        cardBorder = Color(0x33FFB800)
    )
}

/**
 * All 14 official Locket Photobooth themes ported natively to Jetpack Compose Material 3.
 */
object LocketThemes {
    val GOLD = LocketThemeSpec("gold", "Vàng Locket", Color(0xFFFFB800), Color(0xFFFF8A00), Color(0xFF0A0A0D), Color(0xFF15161C))
    val OBSIDIAN = LocketThemeSpec("obsidian", "Đá đen", Color(0xFFF4F4F6), Color(0xFF9EA2B0), Color(0xFF070709), Color(0xFF131317))
    val ROSE = LocketThemeSpec("rose", "Hồng phấn", Color(0xFFFF6B9E), Color(0xFFFF94B8), Color(0xFF100A0E), Color(0xFF1D131A))
    val LAVENDER = LocketThemeSpec("lavender", "Oải hương", Color(0xFFB388FF), Color(0xFF8C9EFF), Color(0xFF0C0A14), Color(0xFF171424))
    val OCEAN = LocketThemeSpec("ocean", "Đại dương", Color(0xFF38BDF8), Color(0xFF0EA5E9), Color(0xFF070E14), Color(0xFF101C26))
    val EMERALD = LocketThemeSpec("emerald", "Ngọc lục bảo", Color(0xFF34D399), Color(0xFF10B981), Color(0xFF07110D), Color(0xFF10211A))
    val SUNSET = LocketThemeSpec("sunset", "Hoàng hôn", Color(0xFFFF7A59), Color(0xFFFFB347), Color(0xFF120B09), Color(0xFF201512))
    val MATCHA = LocketThemeSpec("matcha", "Trà xanh", Color(0xFFA3E635), Color(0xFF65A30D), Color(0xFF0B1008), Color(0xFF162011))
    val CHERRY = LocketThemeSpec("cherry", "Anh đào", Color(0xFFFB7185), Color(0xFFE11D48), Color(0xFF12080B), Color(0xFF221116))
    val MOCHA = LocketThemeSpec("mocha", "Cà phê", Color(0xFFD6A77A), Color(0xFFA47551), Color(0xFF100C0A), Color(0xFF1E1713))
    val CYBER = LocketThemeSpec("cyber", "Neon Cyber", Color(0xFF22D3EE), Color(0xFFF43F5E), Color(0xFF070913), Color(0xFF111629))
    val FROST = LocketThemeSpec("frost", "Băng tuyết", Color(0xFF93C5FD), Color(0xFFE0F2FE), Color(0xFF080D14), Color(0xFF131C28))
    val AMBER = LocketThemeSpec("amber", "Hổ phách", Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFF110D07), Color(0xFF20190E))
    val SLATE = LocketThemeSpec("slate", "Xám khói", Color(0xFF94A3B8), Color(0xFFCBD5E1), Color(0xFF090B0E), Color(0xFF151920))

    val ALL: List<LocketThemeSpec> = listOf(
        GOLD, OBSIDIAN, ROSE, LAVENDER, OCEAN, EMERALD, SUNSET,
        MATCHA, CHERRY, MOCHA, CYBER, FROST, AMBER, SLATE
    )

    fun byId(id: String?): LocketThemeSpec {
        return ALL.firstOrNull { it.id == id } ?: GOLD
    }
}

@Composable
fun LocketAppTheme(
    themeId: String,
    isGlassStyle: Boolean,
    content: @Composable () -> Unit
) {
    val spec = LocketThemes.byId(themeId)
    val cardContainer = if (isGlassStyle) {
        spec.surface.copy(alpha = 0.76f)
    } else {
        spec.surface
    }
    val cardBorder = if (isGlassStyle) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.07f)
    }

    val colorScheme = darkColorScheme(
        primary = spec.accent,
        onPrimary = spec.onAccent,
        secondary = spec.accentSecondary,
        background = spec.background,
        onBackground = Color(0xFFF7F7FA),
        surface = spec.surface,
        onSurface = Color(0xFFF7F7FA),
        surfaceVariant = cardContainer,
        onSurfaceVariant = Color(0xFFB5B8C5),
        outline = cardBorder,
        error = Color(0xFFFF5C6C)
    )

    val palette = LocketRuntimePalette(
        spec = spec,
        isGlass = isGlassStyle,
        cardContainer = cardContainer,
        cardBorder = cardBorder
    )

    CompositionLocalProvider(LocalLocketPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

