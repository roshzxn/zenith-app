package com.zenith.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * 120 FPS Rolling Number Odometer / Ticker for Jetpack Compose.
 *
 * Smoothly animates digit transitions up and down using spring physics,
 * mimicking high-end fintech apps (Robinhood, Apple Stocks).
 *
 * Uses tabular figures ("tnum") to prevent horizontal layout shift during roll transitions.
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
    color: Color = Color.White,
    letterSpacing: TextUnit = 0.sp
) {
    if (isMasked) {
        Text(
            text = "••••••",
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            letterSpacing = letterSpacing,
            modifier = modifier
        )
        return
    }

    val isNegative = amount < 0
    val absAmount = Math.abs(amount)
    val formattedNumber = if (decimalPlaces > 0) {
        String.format(Locale.US, "%,.${decimalPlaces}f", absAmount)
    } else {
        String.format(Locale.US, "%,.0f", absAmount)
    }

    val signStr = if (isNegative) "-" else prefix
    val fullText = "$signStr$currencySymbol$formattedNumber"

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        fullText.forEach { char ->
            if (char.isDigit()) {
                val digit = char.digitToInt()
                RollingDigit(
                    digit = digit,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    color = color,
                    letterSpacing = letterSpacing
                )
            } else {
                Text(
                    text = char.toString(),
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    color = color,
                    letterSpacing = letterSpacing
                )
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
