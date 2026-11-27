package com.zenith.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.zenith.app.data.util.NumberFormatStyle

enum class ZenithThemeMode {
    LIGHT,
    OBSIDIAN_NAVY,
    OLED_BLACK
}

enum class ZenithAccentPreset(
    val displayName: String,
    val darkAccent: Color,
    val lightAccent: Color,
    val solidFill: Color,
    val swatchColor: Color
) {
    INDIGO(
        displayName = "Indigo",
        darkAccent = PastelIndigo,
        lightAccent = LightIndigoPrimary,
        solidFill = Color(0xFF4F46E5),
        swatchColor = Color(0xFF6366F1)
    ),
    EMERALD(
        displayName = "Emerald",
        darkAccent = PastelGreen,
        lightAccent = LightIncomeGreen,
        solidFill = Color(0xFF047857),
        swatchColor = Color(0xFF10B981)
    ),
    ROSHZEN(
        displayName = "RoshZen",
        darkAccent = Color(0xFFC084FC),
        lightAccent = Color(0xFF7C3AED),
        solidFill = Color(0xFF6D28D9),
        swatchColor = Color(0xFF9333EA)
    ),
    CYAN(
        displayName = "Cyan",
        darkAccent = PastelCyan,
        lightAccent = LightCyanAccent,
        solidFill = Color(0xFF0E7490),
        swatchColor = Color(0xFF06B6D4)
    ),
    AMBER(
        displayName = "Gold",
        darkAccent = PastelAmber,
        lightAccent = LightGoalAmber,
        solidFill = Color(0xFFB45309),
        swatchColor = Color(0xFFF59E0B)
    );

    companion object {
        fun fromName(raw: String?): ZenithAccentPreset {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: INDIGO
        }
    }
}

enum class ZenithMotionSpec(val label: String) {
    FLUID("Fluid"),
    CRISP("Crisp"),
    REDUCED("Minimal");

    fun durationMs(baseMs: Int): Int {
        return when (this) {
            FLUID -> baseMs
            CRISP -> (baseMs * 0.65f).toInt().coerceAtLeast(60)
            REDUCED -> 1
        }
    }

    val isReduced: Boolean get() = this == REDUCED

    companion object {
        fun fromName(raw: String?): ZenithMotionSpec {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FLUID
        }
    }
}

enum class ZenithCardDensity(val label: String) {
    COMFORTABLE("Cozy"),
    COMPACT("Compact");

    companion object {
        fun fromName(raw: String?): ZenithCardDensity {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: COMFORTABLE
        }
    }
}

val LocalThemeMode = compositionLocalOf { ZenithThemeMode.OBSIDIAN_NAVY }
val LocalAccentPreset = compositionLocalOf { ZenithAccentPreset.INDIGO }
val LocalMotionSpec = compositionLocalOf { ZenithMotionSpec.FLUID }
val LocalCardDensity = compositionLocalOf { ZenithCardDensity.COMFORTABLE }
val LocalNumberFormatStyle = compositionLocalOf { NumberFormatStyle.INTERNATIONAL }

private val LightColorScheme =
    lightColorScheme(
        primary = LightIndigoPrimary,
        onPrimary = Color.White,
        primaryContainer = LightIndigoContainer,
        onPrimaryContainer = Color(0xFF1E1B4B),
        secondary = LightCyanAccent,
        secondaryContainer = Color(0xFFE0E7FF),
        onSecondaryContainer = Color(0xFF1E1B4B),
        background = LightBackground,
        surface = LightSurface,
        surfaceVariant = LightSurfaceVariant,
        onBackground = LightTextPrimary,
        onSurface = LightTextPrimary,
        onSurfaceVariant = LightTextSecondary,
        outline = LightBorder
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = EmeraldDarkPrimary,
        onPrimary = EmeraldOnPrimary,
        primaryContainer = EmeraldDarkContainer,
        onPrimaryContainer = EmeraldOnContainer,
        secondary = GoldAccent,
        secondaryContainer = GoldContainer,
        onSecondaryContainer = GoldOnContainer,
        background = SlateDarkBackground,
        surface = SlateDarkSurface,
        surfaceVariant = SlateDarkSurfaceVariant,
        onBackground = SlateDarkTextPrimary,
        onSurface = SlateDarkTextPrimary,
        onSurfaceVariant = SlateDarkTextSecondary,
        outline = SlateDarkBorder
    )

private val OledColorScheme =
    darkColorScheme(
        primary = EmeraldDarkPrimary,
        onPrimary = EmeraldOnPrimary,
        primaryContainer = Color(0xFF14122E),
        onPrimaryContainer = EmeraldOnContainer,
        secondary = GoldAccent,
        secondaryContainer = Color(0xFF041C24),
        onSecondaryContainer = GoldOnContainer,
        background = OledPureBlackBackground,
        surface = OledPureBlackSurface,
        surfaceVariant = OledPureBlackSurfaceVariant,
        onBackground = SlateDarkTextPrimary,
        onSurface = SlateDarkTextPrimary,
        onSurfaceVariant = SlateDarkTextSecondary,
        outline = OledPureBlackBorder
    )

object ZenithTheme {
    val mode: ZenithThemeMode
        @Composable
        get() = LocalThemeMode.current

    val accentPreset: ZenithAccentPreset
        @Composable
        get() = LocalAccentPreset.current

    val motionSpec: ZenithMotionSpec
        @Composable
        get() = LocalMotionSpec.current

    val cardDensity: ZenithCardDensity
        @Composable
        get() = LocalCardDensity.current

    val numberFormat: NumberFormatStyle
        @Composable
        get() = LocalNumberFormatStyle.current

    val isDark: Boolean
        @Composable
        get() = LocalThemeMode.current != ZenithThemeMode.LIGHT

    val isOled: Boolean
        @Composable
        get() = LocalThemeMode.current == ZenithThemeMode.OLED_BLACK

    val backgroundBrush: Brush
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> LightBackgroundBrush
            ZenithThemeMode.OBSIDIAN_NAVY -> AmbientBackgroundBrush
            ZenithThemeMode.OLED_BLACK -> OledBackgroundBrush
        }

    val heroGradient: Brush
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> LightHeroCardGradient
            ZenithThemeMode.OBSIDIAN_NAVY -> HeroCardGradient
            ZenithThemeMode.OLED_BLACK -> OledHeroCardGradient
        }

    val cardSurface: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> LightSurface
            ZenithThemeMode.OBSIDIAN_NAVY -> SlateDarkSurface
            ZenithThemeMode.OLED_BLACK -> OledPureBlackSurface
        }

    val cardGlassBackground: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> Color(0xF5FFFFFF)
            ZenithThemeMode.OBSIDIAN_NAVY -> SlateDarkSurface.copy(alpha = 0.85f)
            ZenithThemeMode.OLED_BLACK -> OledPureBlackSurface
        }

    val cardVariantSurface: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> LightSurfaceVariant
            ZenithThemeMode.OBSIDIAN_NAVY -> SlateDarkSurfaceVariant
            ZenithThemeMode.OLED_BLACK -> OledPureBlackSurfaceVariant
        }

    val glassCardBg: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> LightSurface.copy(alpha = 0.92f)
            ZenithThemeMode.OBSIDIAN_NAVY -> GlassCardBg
            ZenithThemeMode.OLED_BLACK -> OledPureBlackSurface
        }

    val glassCardBgElevated: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> LightSurfaceVariant
            ZenithThemeMode.OBSIDIAN_NAVY -> GlassCardBgElevated
            ZenithThemeMode.OLED_BLACK -> OledPureBlackSurfaceVariant
        }

    val borderColor: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> LightBorderSubtle
            ZenithThemeMode.OBSIDIAN_NAVY -> GlassBorderColor
            ZenithThemeMode.OLED_BLACK -> OledPureBlackBorder
        }

    val textPrimary: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightTextPrimary else SlateDarkTextPrimary

    val textSecondary: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightTextSecondary else SlateDarkTextSecondary

    val textMuted: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightTextMuted else SlateDarkTextMuted

    val accentIndigo: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) {
            LocalAccentPreset.current.lightAccent
        } else {
            LocalAccentPreset.current.darkAccent
        }

    val accentCyan: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightCyanAccent else PastelCyan

    val accentGreen: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightIncomeGreen else PastelGreen

    val accentRed: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightExpenseRed else PastelRose

    val accentAmber: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightGoalAmber else PastelAmber

    val accentPurple: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightPurpleAccent else PastelPurple

    val heroBorderColor: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> Color(0xFFBFCBFF)
            ZenithThemeMode.OBSIDIAN_NAVY -> GlassBorderColor
            ZenithThemeMode.OLED_BLACK -> OledPureBlackBorder
        }

    // Accessible deep solid action fills for white text/icons across all 3 themes (WCAG AA >= 5.0:1)
    val solidGreen: Color
        @Composable
        get() = Color(0xFF047857) // Emerald 700 (5.48:1 with white text)

    val solidIndigo: Color
        @Composable
        get() = LocalAccentPreset.current.solidFill

    val solidRed: Color
        @Composable
        get() = Color(0xFFBE123C) // Rose 700 (6.29:1 with white text)

    val solidAmber: Color
        @Composable
        get() = Color(0xFFB45309) // Amber 700 (5.02:1 with white text)

    val scrimFloor: Color
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> Color(0xFFEFF3FA)
            ZenithThemeMode.OBSIDIAN_NAVY -> Color(0xFF060910)
            ZenithThemeMode.OLED_BLACK -> OledPureBlackBackground
        }

    val navBarBrush: Brush
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> Brush.verticalGradient(
                listOf(Color(0xFCFFFFFF), Color(0xFCF8FAFC))
            )
            ZenithThemeMode.OBSIDIAN_NAVY -> Brush.verticalGradient(
                listOf(Color(0xFA101726), Color(0xFF090D16))
            )
            ZenithThemeMode.OLED_BLACK -> Brush.verticalGradient(
                listOf(Color(0xFC0C0C0C), Color(0xFF000000))
            )
        }

    val navBarBorderBrush: Brush
        @Composable
        get() = when (LocalThemeMode.current) {
            ZenithThemeMode.LIGHT -> Brush.verticalGradient(
                listOf(Color(0xFFCBD5E1), Color(0xFFE2E8F0))
            )
            ZenithThemeMode.OLED_BLACK -> Brush.verticalGradient(
                listOf(Color(0xFF2D2D2D), Color(0xFF1A1A1A))
            )
            ZenithThemeMode.OBSIDIAN_NAVY -> Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.28f),
                    Color(0x33818CF8),
                    Color.White.copy(alpha = 0.06f)
                )
            )
        }
}

@Composable
fun CashFlowTheme(
    isDarkMode: Boolean = true,
    isOledBlack: Boolean = false,
    accentPreset: ZenithAccentPreset = ZenithAccentPreset.INDIGO,
    motionSpec: ZenithMotionSpec = ZenithMotionSpec.FLUID,
    cardDensity: ZenithCardDensity = ZenithCardDensity.COMFORTABLE,
    numberFormatStyle: NumberFormatStyle = NumberFormatStyle.INTERNATIONAL,
    content: @Composable () -> Unit,
) {
    val themeMode = when {
        !isDarkMode -> ZenithThemeMode.LIGHT
        isOledBlack -> ZenithThemeMode.OLED_BLACK
        else -> ZenithThemeMode.OBSIDIAN_NAVY
    }
    val baseScheme = when (themeMode) {
        ZenithThemeMode.LIGHT -> LightColorScheme
        ZenithThemeMode.OLED_BLACK -> OledColorScheme
        ZenithThemeMode.OBSIDIAN_NAVY -> DarkColorScheme
    }
    val colorScheme = baseScheme.copy(
        primary = if (themeMode == ZenithThemeMode.LIGHT) accentPreset.lightAccent else accentPreset.swatchColor
    )

    CompositionLocalProvider(
        LocalThemeMode provides themeMode,
        LocalAccentPreset provides accentPreset,
        LocalMotionSpec provides motionSpec,
        LocalCardDensity provides cardDensity,
        LocalNumberFormatStyle provides numberFormatStyle
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    CashFlowTheme(isDarkMode = darkTheme, isOledBlack = false, content = content)
}
