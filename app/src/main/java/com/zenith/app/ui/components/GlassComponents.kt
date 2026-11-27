package com.zenith.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.ui.theme.*

/**
 * Adds tactile spring press-scale feedback while respecting the user's [ZenithMotionSpec].
 */
fun Modifier.pressScaleClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = ZenithTheme.motionSpec.isReduced
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !reducedMotion) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press_scale"
    )
    this
        .scale(scale)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = ripple(bounded = true),
            onClick = onClick
        )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    backgroundColor: Color = ZenithTheme.glassCardBg,
    borderColor: Color = ZenithTheme.borderColor,
    borderWidth: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = ZenithTheme.isDark
    val effectiveBg = if (!isDark && backgroundColor == ZenithTheme.glassCardBg) {
        ZenithTheme.cardSurface
    } else {
        backgroundColor
    }
    val effectiveElevation = if (isDark || effectiveBg.alpha < 0.98f) 0.dp else 3.dp
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = effectiveBg),
        modifier = modifier
            .shadow(
                elevation = effectiveElevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.10f)
            )
            .border(borderWidth, borderColor, shape)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = content
        )
    }
}

@Composable
fun CleanCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    backgroundColor: Color = ZenithTheme.cardSurface,
    borderColor: Color = ZenithTheme.borderColor,
    borderWidth: Dp = 1.dp,
    elevation: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = ZenithTheme.isDark
    val effectiveElevation = if (ZenithTheme.isOled || backgroundColor.alpha < 0.98f) 0.dp else elevation
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        modifier = modifier
            .shadow(
                elevation = effectiveElevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.30f else 0.08f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.40f else 0.12f)
            )
            .border(borderWidth, borderColor, shape)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun PillBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    backgroundColor: Color = ZenithTheme.accentGreen.copy(alpha = 0.15f),
    contentColor: Color = ZenithTheme.accentGreen,
    borderColor: Color = contentColor.copy(alpha = 0.3f)
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = ZenithTheme.textSecondary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = ZenithTheme.textMuted
                )
            }
        }

        if (!actionText.isNullOrBlank() && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    text = actionText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTheme.accentIndigo
                )
            }
        }
    }
}

@Composable
fun ZenithLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
    val primaryTint = ZenithTheme.accentPreset.swatchColor
    val secondaryTint = ZenithTheme.accentCyan
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        primaryTint,
                        secondaryTint
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Z",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.52f).sp
        )
    }
}
