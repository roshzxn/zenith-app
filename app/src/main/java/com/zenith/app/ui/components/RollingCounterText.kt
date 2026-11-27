package com.zenith.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.zenith.app.data.util.AmountFormatter
import com.zenith.app.ui.theme.ZenithTheme

/**
 * 120 FPS Rolling Number Odometer / Ticker for Jetpack Compose.
 *
 * Smoothly animates digit transitions up and down using spring physics,
 * uses tabular figures ("tnum"), and auto-scales font size when placed inside
 * narrow pills/cards so wide numbers never overflow or wrap.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RollingCounterText(
    amount: Double,
    modifier: Modifier = Modifier,
    currencySymbol: String = "₹",
    prefix: String = "",
    decimalPlaces: Int = 2,
    isMasked: Boolean = false,
    fontSize: TextUnit = 32.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = ZenithTheme.textPrimary,
    letterSpacing: TextUnit = 0.sp
) {
    if (isMasked) {
        Text(
            text = "••••••",
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            letterSpacing = letterSpacing,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    val isNegative = amount < 0
    val absAmount = Math.abs(amount)
    val formatStyle = ZenithTheme.numberFormat
    val motionSpec = ZenithTheme.motionSpec
    val formattedNumber = AmountFormatter.formatNumber(
        amount = absAmount,
        decimalPlaces = decimalPlaces,
        style = formatStyle
    )

    val signStr = if (isNegative) "-" else prefix
    val fullText = "$signStr$currencySymbol$formattedNumber"

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val effectiveFontSize = if (constraints.hasBoundedWidth && maxWidth.value > 0f && fullText.isNotEmpty()) {
            // Account for OS accessibility fontScale because Compose scales .sp automatically
            val maxAllowedSp = (maxWidth.value / (fullText.length * 0.62f * fontScale)).coerceAtLeast(9f)
            minOf(fontSize.value, maxAllowedSp).sp
        } else {
            fontSize
        }

        if (motionSpec.isReduced) {
            Text(
                text = fullText,
                fontSize = effectiveFontSize,
                fontWeight = fontWeight,
                color = color,
                letterSpacing = letterSpacing,
                style = TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                fullText.forEach { char ->
                    if (char.isDigit()) {
                        val digit = char.digitToInt()
                        RollingDigit(
                            digit = digit,
                            fontSize = effectiveFontSize,
                            fontWeight = fontWeight,
                            color = color,
                            letterSpacing = letterSpacing
                        )
                    } else {
                        Text(
                            text = char.toString(),
                            fontSize = effectiveFontSize,
                            fontWeight = fontWeight,
                            color = color,
                            letterSpacing = letterSpacing,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun RollingDigit(
    digit: Int,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
    letterSpacing: TextUnit
) {
    AnimatedContent(
        targetState = digit,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                ) { height -> height } + fadeIn()) togetherWith
                (slideOutVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                ) { height -> -height } + fadeOut())
            } else {
                (slideInVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                ) { height -> -height } + fadeIn()) togetherWith
                (slideOutVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                ) { height -> height } + fadeOut())
            }.using(SizeTransform(clip = false))
        },
        label = "RollingDigit"
    ) { targetDigit ->
        Text(
            text = targetDigit.toString(),
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            letterSpacing = letterSpacing,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}
