package com.zenith.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ZenithThemeMode {
    OBSIDIAN_NAVY,
    OLED_BLACK
}

val LocalThemeMode = compositionLocalOf { ZenithThemeMode.OBSIDIAN_NAVY }

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
    val backgroundBrush: Brush
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.OLED_BLACK) OledBackgroundBrush else AmbientBackgroundBrush

    val heroGradient: Brush
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.OLED_BLACK) OledHeroCardGradient else HeroCardGradient

    val familyHeroGradient: Brush
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.OLED_BLACK) OledFamilyHeroCardGradient else FamilyHeroCardGradient

    val cardSurface: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.OLED_BLACK) OledPureBlackSurface else SlateDarkSurface

    val cardVariantSurface: Color
        @Composable
        get() = if (LocalThemeMode.current == ZenithThemeMode.OLED_BLACK) OledPureBlackSurfaceVariant else SlateDarkSurfaceVariant

    val isOled: Boolean
        @Composable
        get() = LocalThemeMode.current == ZenithThemeMode.OLED_BLACK
}

@Composable
fun CashFlowTheme(
    isOledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val themeMode = if (isOledBlack) ZenithThemeMode.OLED_BLACK else ZenithThemeMode.OBSIDIAN_NAVY
    val colorScheme = if (isOledBlack) OledColorScheme else DarkColorScheme

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
    CashFlowTheme(isOledBlack = false, content = content)
}
