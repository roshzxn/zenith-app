package com.zenith.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

// =========================================================================
// ZENITH CLEAN MINIMALIST DESIGN SYSTEM & MULTI-THEME PALETTE
// =========================================================================

// --- Dark Surface Architecture (Obsidian Navy) ---
val SlateDarkBackground = Color(0xFF090D16)      // Deep ambient slate base
val SlateDarkSurface = Color(0xFF0F172A)         // Primary card surface
val SlateDarkSurfaceVariant = Color(0xFF1E293B)  // Elevated container / input fields
val SlateDarkBorder = Color(0xFF334155)          // Clean outline border
val SlateDarkBorderSubtle = Color(0x2E94A3B8)    // 18% subtle glass border

// --- Light Surface Architecture (Crisp Porcelain & Frost) ---
val LightBackground = Color(0xFFF3F6FC)          // Crisp cool porcelain canvas
val LightSurface = Color(0xFFFFFFFF)             // Pure white card surface
val LightSurfaceVariant = Color(0xFFEFF3F9)      // Elevated container / input fields
val LightBorder = Color(0xFFCBD5E1)              // Crisp slate border
val LightBorderSubtle = Color(0xFFD6DFEB)        // Clear card outline on porcelain

// --- Typography Tokens ---
val SlateDarkTextPrimary = Color(0xFFF8FAFC)     // Ultra-crisp primary text (dark)
val SlateDarkTextSecondary = Color(0xFF94A3B8)   // Muted slate subtitle (dark)
val SlateDarkTextMuted = Color(0xFF8394AB)       // Accessible tertiary captions & placeholders (>=4.5:1)

val LightTextPrimary = Color(0xFF0F172A)         // Charcoal primary text (light)
val LightTextSecondary = Color(0xFF475569)       // Slate secondary subtitle (light)
val LightTextMuted = Color(0xFF526075)           // Accessible tertiary captions (>=5.7:1 on frost)

// --- Minimalist Glass & Elevation Tokens ---
val GlassCardBg = Color(0x18FFFFFF)              // 9.5% clean translucent glass
val GlassCardBgElevated = Color(0x24FFFFFF)      // 14% elevated glass
val GlassCardBgHover = Color(0x2EFFFFFF)         // 18% hover
val GlassBorderColor = Color(0x2BFFFFFF)         // 17% crisp border
val GlassBorderHighlight = Color(0x50818CF8)     // Soft pastel indigo glow
val GlassSurfaceDark = Color(0xCC0F172A)         // 80% surface glass

// --- Brand Primary (Electric Indigo) ---
val EmeraldDarkPrimary = Color(0xFF6366F1)       // Electric Indigo primary
val LightIndigoPrimary = Color(0xFF4F46E5)       // Crisp Indigo for light surfaces
val EmeraldDarkContainer = Color(0xFF1E1B4B)     // Deep indigo container
val LightIndigoContainer = Color(0xFFE0E7FF)     // Soft indigo container (light)
val EmeraldOnPrimary = Color(0xFFFFFFFF)
val EmeraldOnContainer = Color(0xFFE0E7FF)
val PastelIndigo = Color(0xFF818CF8)
val PastelIndigoContainer = Color(0x26818CF8)

// --- Secondary Accent (Mint Cyan & Soft Teal) ---
val GoldAccent = Color(0xFF06B6D4)               // Clean Cyan accent
val CyanDarkSecondary = Color(0xFF06B6D4)
val LightCyanAccent = Color(0xFF0891B2)          // Deep readable cyan for light surfaces
val GoldContainer = Color(0xFF083344)
val GoldOnContainer = Color(0xFFCFFAFE)
val PastelCyan = Color(0xFF22D3EE)
val PastelCyanContainer = Color(0x2622D3EE)

// --- Semantic Financial Indicators ---
val IncomeGreen = Color(0xFF10B981)              // Mint Emerald
val LightIncomeGreen = Color(0xFF059669)         // High-contrast emerald for light cards
val IncomeGreenContainer = Color(0xFF064E3B)
val IncomeGreenOnContainer = Color(0xFFA7F3D0)
val PastelGreen = Color(0xFF34D399)
val PastelGreenContainer = Color(0x2634D399)

val ExpenseRed = Color(0xFFF43F5E)               // Coral Rose Red
val LightExpenseRed = Color(0xFFE11D48)          // High-contrast rose for light cards
val ExpenseRedContainer = Color(0xFF881337)
val ExpenseRedOnContainer = Color(0xFFFECDD3)
val PastelRose = Color(0xFFFB7185)
val PastelRoseContainer = Color(0x26FB7185)

val GoalAmber = Color(0xFFF59E0B)                // Warm Sunset Amber
val LightGoalAmber = Color(0xFFD97706)           // High-contrast amber for light cards
val GoalAmberContainer = Color(0xFF78350F)
val GoalAmberOnContainer = Color(0xFFFEF3C7)
val PastelAmber = Color(0xFFFBBF24)
val PastelAmberContainer = Color(0x26FBBF24)

val PastelPurple = Color(0xFFA78BFA)             // Soft Purple for AI & Analytics
val LightPurpleAccent = Color(0xFF7C3AED)        // High-contrast purple for light cards
val PastelPurpleContainer = Color(0x26A78BFA)

// --- Ambient & Gradient Brushes ---
val AmbientBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0E1322), // Soft ambient indigo zenith
        Color(0xFF090D16), // Slate carbon base
        Color(0xFF060910)  // Deep floor
    )
)

val LightBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFEEF2FF), // Soft frosted indigo top wash
        Color(0xFFF4F7FC), // Clean porcelain body
        Color(0xFFEFF3FA)  // Subtle cool slate floor
    )
)

val HeroCardGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF1E1B4B), // Electric Indigo core
        Color(0xFF0F172A), // Slate Navy
        Color(0xFF090D16)  // Base dark
    )
)

val LightHeroCardGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFFFFF), // Crisp white highlight
        Color(0xFFEFF3FF), // Soft indigo tint
        Color(0xFFDDE7FF)  // Layered frosted indigo finish
    )
)

val FamilyHeroCardGradient = HeroCardGradient

val AiCardGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF2E1065), // Deep Violet
        Color(0xFF1E1B4B), // Indigo
        Color(0xFF0E7490)  // Cyan
    )
)

val GlassBorderBrush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.28f),
        Color(0xFF818CF8).copy(alpha = 0.35f),
        Color.White.copy(alpha = 0.08f)
    )
)

// --- OLED Pure Black (#000000) Tokens ---
val OledPureBlackBackground = Color(0xFF000000)      // True #000000 pure black
val OledPureBlackSurface = Color(0xFF080808)         // Pitch card surface
val OledPureBlackSurfaceVariant = Color(0xFF121212)  // Elevated container
val OledPureBlackBorder = Color(0xFF222222)          // Crisp contrast outline
val OledPureBlackBorderSubtle = Color(0x33444444)

val OledBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF000000),
        Color(0xFF000000),
        Color(0xFF000000)
    )
)

val OledHeroCardGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF111119),
        Color(0xFF08080E),
        Color(0xFF000000)
    )
)

val OledFamilyHeroCardGradient = OledHeroCardGradient
