package com.zenith.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ZenithThemeMode {
    LIGHT,
    OBSIDIAN_NAVY,
    OLED_BLACK
}

val LocalThemeMode = compositionLocalOf { ZenithThemeMode.OBSIDIAN_NAVY }

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
        get() = if (LocalThemeMode.current == ZenithThemeMode.LIGHT) LightIndigoPrimary else PastelIndigo

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
        get() = LightIndigoPrimary // Indigo 600 (6.29:1 with white text)

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
    content: @Composable () -> Unit,
) {
    val themeMode = when {
        !isDarkMode -> ZenithThemeMode.LIGHT
        isOledBlack -> ZenithThemeMode.OLED_BLACK
        else -> ZenithThemeMode.OBSIDIAN_NAVY
    }
    val colorScheme = when (themeMode) {
        ZenithThemeMode.LIGHT -> LightColorScheme
        ZenithThemeMode.OLED_BLACK -> OledColorScheme
        ZenithThemeMode.OBSIDIAN_NAVY -> DarkColorScheme
    }

    CompositionLocalProvider(LocalThemeMode provides themeMode) {
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
