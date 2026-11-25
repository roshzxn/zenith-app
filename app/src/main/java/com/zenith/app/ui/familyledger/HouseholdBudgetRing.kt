package com.zenith.app.ui.familyledger

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HouseholdBudgetRing(
    totalSpent: Double,
    budgetLimit: Double,
    currencySymbol: String = "₹",
    modifier: Modifier = Modifier
) {
    val effectiveLimit = if (budgetLimit <= 0.0) 50000.0 else budgetLimit
    val fraction = (totalSpent / effectiveLimit).coerceIn(0.0, 1.25).toFloat()
    val percentInt = ((totalSpent / effectiveLimit) * 100).toInt()
    val remaining = (effectiveLimit - totalSpent).coerceAtLeast(0.0)

    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "budget_ring_sweep"
    )

    // Dynamic gradient based on percentage
    val ringGradient = when {
        fraction > 0.95f -> Brush.sweepGradient(listOf(Color(0xFFEF4444), Color(0xFFF43F5E), Color(0xFFEF4444)))
        fraction > 0.75f -> Brush.sweepGradient(listOf(Color(0xFFF59E0B), Color(0xFFF97316), Color(0xFFF59E0B)))
        else -> Brush.sweepGradient(listOf(Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFF10B981)))
    }

    Box(
        modifier = modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val strokeWidth = 14.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)

            // Background Track
            drawArc(
                color = Color(0xFF1E2536).copy(alpha = 0.5f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active Dynamic Progress Arc
            val sweep = (animatedFraction * 360f).coerceAtMost(360f)
            if (sweep > 0f) {
                drawArc(
                    brush = ringGradient,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Center Typography
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$percentInt%",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "$currencySymbol%,.0f left".format(remaining),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (remaining > 0) Color(0xFF38BDF8) else Color(0xFFEF4444)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Pool Limit: $currencySymbol%,.0f".format(effectiveLimit),
                fontSize = 9.sp,
                color = Color(0xFF64748B),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
