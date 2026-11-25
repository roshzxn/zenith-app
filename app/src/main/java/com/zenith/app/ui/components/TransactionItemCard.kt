package com.zenith.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.util.CategoryIconHelper
import com.zenith.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionItemCard(
    transaction: TransactionEntity,
    currencySymbol: String,
    modifier: Modifier = Modifier,
    isMasked: Boolean = false,
    onClick: ((TransactionEntity) -> Unit)? = null,
    onLongClick: ((TransactionEntity) -> Unit)? = null
) {
    val isExpense = transaction.type == TransactionType.EXPENSE
    val icon = categoryVectorFor(transaction.categoryIconName, transaction.category)
    val formattedDate = rememberFormattedDate(transaction.dateMillis)
    val hasReceiptAttached = !transaction.receiptImageUri.isNullOrBlank() || transaction.note.contains("Receipt", ignoreCase = true)

    val typeColor = if (isExpense) ZenithTheme.accentRed else ZenithTheme.accentGreen
    val containerAlpha = if (ZenithTheme.isDark) 0.16f else 0.11f

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ZenithTheme.cardGlassBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ZenithTheme.borderColor),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = { onClick?.invoke(transaction) },
                onLongClick = { onLongClick?.invoke(transaction) }
            )
            .testTag("transaction_card_${transaction.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Icon Badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(typeColor.copy(alpha = containerAlpha))
                    .border(
                        1.dp,
                        typeColor.copy(alpha = 0.35f),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = transaction.category,
                    tint = typeColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details (Title, Category, Payment Method, Receipt attached tag)
            Column(modifier = Modifier.weight(1f, fill = true)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = transaction.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (hasReceiptAttached) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ZenithTheme.accentPurple.copy(alpha = containerAlpha),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ZenithTheme.accentPurple.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Receipt",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.accentPurple,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Category Badge Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ZenithTheme.cardVariantSurface,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = transaction.category,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZenithTheme.textSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = "• ${transaction.paymentMethod}",
                        fontSize = 11.sp,
                        color = ZenithTheme.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Amount, Sync Status, and Date
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(min = 65.dp)
            ) {
                Text(
                    text = if (isMasked) {
                        "${if (isExpense) "-" else "+"}$currencySymbol••••"
                    } else {
                        "${if (isExpense) "-" else "+"}$currencySymbol${String.format(Locale.US, "%,.2f", transaction.amount)}"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = typeColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (transaction.syncStatus != "SYNCED") {
                        Box(
                            modifier = Modifier
                                .padding(end = 5.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ZenithTheme.accentAmber)
                        )
                    }
                    Text(
                        text = formattedDate,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ZenithTheme.textMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun rememberFormattedDate(millis: Long): String {
    val date = Date(millis)
    val now = Calendar.getInstance()
    val txCal = Calendar.getInstance().apply { time = date }

    return when {
        now.get(Calendar.YEAR) == txCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == txCal.get(Calendar.DAY_OF_YEAR) -> "Today"

        now.get(Calendar.YEAR) == txCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - txCal.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"

        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(date)
    }
}

internal fun categoryVectorFor(iconName: String, category: String = ""): ImageVector {
    val normalized = iconName.trim().lowercase().replace("_", "")
    val resolvedName = if (
        normalized.isBlank() ||
        normalized == "category" ||
        (normalized == "receipt" && !category.equals("Bills & Utilities", ignoreCase = true))
    ) {
        CategoryIconHelper.iconNameForCategory(category).lowercase().replace("_", "")
    } else {
        normalized
    }

    return when (resolvedName) {
        "restaurant", "food", "dining" -> Icons.Default.Restaurant
        "shoppingbag", "shoppingcart", "shopping", "groceries" -> Icons.Default.ShoppingBag
        "directionscar", "transport", "transportation", "petrol", "fuel" -> Icons.Default.DirectionsCar
        "flight", "travel" -> Icons.Default.Flight
        "home", "housing", "rent" -> Icons.Default.Home
        "bolt", "utilities", "bills", "receipt" -> Icons.Default.Bolt
        "movie", "entertainment" -> Icons.Default.Movie
        "medicalservices", "fitnesscenter", "health", "healthcare" -> Icons.Default.MedicalServices
        "school", "education" -> Icons.Default.School
        "work", "freelance" -> Icons.Default.Work
        "accountbalance", "salary", "income", "payments" -> Icons.Default.AccountBalance
        "trendingup", "investments", "investment" -> Icons.AutoMirrored.Filled.TrendingUp
        else -> when (category.trim().lowercase()) {
            "food & dining" -> Icons.Default.Restaurant
            "shopping", "groceries" -> Icons.Default.ShoppingBag
            "transportation" -> Icons.Default.DirectionsCar
            "housing & rent" -> Icons.Default.Home
            "bills & utilities" -> Icons.Default.Bolt
            "entertainment" -> Icons.Default.Movie
            "healthcare", "health", "health & fitness" -> Icons.Default.MedicalServices
            "education" -> Icons.Default.School
            "freelance / business", "freelance" -> Icons.Default.Work
            "salary & income", "salary" -> Icons.Default.AccountBalance
            "investments", "investment" -> Icons.AutoMirrored.Filled.TrendingUp
            else -> Icons.AutoMirrored.Filled.ReceiptLong
        }
    }
}
