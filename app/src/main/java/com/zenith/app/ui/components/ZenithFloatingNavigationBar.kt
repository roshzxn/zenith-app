package com.zenith.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.ui.theme.*

enum class ZenithNavTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME("Home", Icons.Default.Home, Icons.Outlined.Home, "nav_home"),
    TRANSACTIONS("Activity", Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong, "nav_transactions"),
    BUDGETS("Budgets", Icons.Default.PieChart, Icons.Outlined.PieChart, "nav_budgets"),
    ANALYTICS("Analytics", Icons.Default.BarChart, Icons.Outlined.BarChart, "nav_analytics"),
    PROFILE("Profile", Icons.Default.Person, Icons.Outlined.Person, "nav_profile")
}

@Composable
fun ZenithFloatingNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val isDark = ZenithTheme.isDark
    val isOled = ZenithTheme.isOled

    // Bottom fade scrim + Floating Capsule Container so scrolled list items fade cleanly behind/under the bar
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        ZenithTheme.scrimFloor.copy(alpha = 0.88f),
                        ZenithTheme.scrimFloor
                    )
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {

        // Floating Frosted Glass Capsule (allows taller growth for OS large font scales)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .shadow(
                    elevation = if (isOled) 0.dp else if (isDark) 18.dp else 10.dp,
                    shape = RoundedCornerShape(32.dp),
                    spotColor = if (isDark) Color(0x99000000) else Color(0x1F0F172A),
                    ambientColor = if (isDark) Color(0x40000000) else Color(0x100F172A)
                )
                .clip(RoundedCornerShape(32.dp))
                .background(brush = ZenithTheme.navBarBrush)
                .border(
                    BorderStroke(
                        width = 1.dp,
                        brush = ZenithTheme.navBarBorderBrush
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZenithNavTab.entries.forEachIndexed { index, tab ->
                    val isSelected = selectedTab == index
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    val reducedMotion = ZenithTheme.motionSpec.isReduced

                    val animatedScale by animateFloatAsState(
                        targetValue = if (isSelected && !reducedMotion) 1.06f else 1.0f,
                        animationSpec = if (reducedMotion) {
                            tween(1)
                        } else {
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        },
                        label = "tab_scale"
                    )

                    val selectedTextColor = if (isDark) Color(0xFFF1F5F9) else ZenithTheme.accentIndigo
                    val unselectedTextColor = ZenithTheme.textSecondary

                    val activeContentColor by animateColorAsState(
                        targetValue = if (isSelected) selectedTextColor else unselectedTextColor,
                        animationSpec = tween(ZenithTheme.motionSpec.durationMs(180)),
                        label = "tab_content_color"
                    )
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (isSelected) 16.dp else 0.dp,
                        animationSpec = if (reducedMotion) tween(1) else spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "tab_indicator_width"
                    )
                    val activePillTop by animateColorAsState(
                        targetValue = if (isSelected) {
                            ZenithTheme.accentPreset.swatchColor.copy(alpha = if (isDark) 0.28f else 0.16f)
                        } else {
                            Color.Transparent
                        },
                        animationSpec = tween(ZenithTheme.motionSpec.durationMs(200)),
                        label = "tab_pill_top"
                    )
                    val activePillBottom by animateColorAsState(
                        targetValue = if (isSelected) {
                            ZenithTheme.accentIndigo.copy(alpha = if (isDark) 0.12f else 0.06f)
                        } else {
                            Color.Transparent
                        },
                        animationSpec = tween(ZenithTheme.motionSpec.durationMs(200)),
                        label = "tab_pill_bottom"
                    )
                    val activeBorderColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            ZenithTheme.accentIndigo.copy(alpha = if (isDark) 0.42f else 0.30f)
                        } else {
                            Color.Transparent
                        },
                        animationSpec = tween(ZenithTheme.motionSpec.durationMs(200)),
                        label = "tab_pill_border"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 52.dp)
                            .scale(animatedScale)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                brush = Brush.verticalGradient(listOf(activePillTop, activePillBottom))
                            )
                            .border(
                                width = 1.dp,
                                color = activeBorderColor,
                                shape = RoundedCornerShape(22.dp)
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(bounded = true, radius = 28.dp)
                            ) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onTabSelected(index)
                            }
                            .testTag(tab.testTag),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.title,
                                tint = activeContentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = activeContentColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            // Glowing micro-indicator bar under active tab
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn(tween(ZenithTheme.motionSpec.durationMs(150))),
                                exit = fadeOut(tween(ZenithTheme.motionSpec.durationMs(120)))
                            ) {
                                Column {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(width = indicatorWidth, height = 2.5.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                brush = Brush.horizontalGradient(
                                                    listOf(
                                                        ZenithTheme.accentIndigo,
                                                        ZenithTheme.accentCyan
                                                    )
                                                )
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
