package com.zenith.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.app.ui.theme.ZenithTheme
import java.util.Locale

/**
 * Shared personal category fields used across UPI entry points.
 */
@Composable
fun UpiTransactionFields(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    purpose: String
) {
    Text(
        text = "Category",
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = ZenithTheme.textSecondary,
        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
    )
    val suggestedCategory = remember(purpose) { suggestCategoryForMerchant(purpose) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            "Food & Dining",
            "Shopping",
            "Transportation",
            "Bills & Utilities",
            "Entertainment",
            "Healthcare",
            "Other"
        ).forEach { cat ->
            FilterChip(
                selected = selectedCategory == cat,
                onClick = { onCategorySelected(cat) },
                label = { Text(cat, fontSize = 11.sp) }
            )
        }
    }
    if (suggestedCategory != null && suggestedCategory != selectedCategory) {
        TextButton(
            onClick = { onCategorySelected(suggestedCategory) },
            contentPadding = PaddingValues(horizontal = 0.dp)
        ) {
            Text(
                text = "Suggested: $suggestedCategory  ·  Apply",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ZenithTheme.accentCyan
            )
        }
    }
}

internal fun suggestCategoryForMerchant(purpose: String): String? {
    val text = purpose.lowercase(Locale.ROOT)
    val keywords = listOf(
        "food" to "Food & Dining", "swiggy" to "Food & Dining", "zomato" to "Food & Dining",
        "restaurant" to "Food & Dining", "dining" to "Food & Dining", "cafe" to "Food & Dining", "hotel" to "Food & Dining",
        "grocery" to "Food & Dining",
        "uber" to "Transportation", "ola" to "Transportation", "cab" to "Transportation", "taxi" to "Transportation",
        "fuel" to "Transportation", "petrol" to "Transportation", "transport" to "Transportation", "metro" to "Transportation",
        "amazon" to "Shopping", "flipkart" to "Shopping", "myntra" to "Shopping", "shop" to "Shopping", "mall" to "Shopping",
        "entertainment" to "Entertainment", "movie" to "Entertainment", "netflix" to "Entertainment", "spotify" to "Entertainment",
        "hospital" to "Healthcare", "pharmacy" to "Healthcare", "medical" to "Healthcare", "doctor" to "Healthcare",
        "rent" to "Housing & Rent",
        "electricity" to "Bills & Utilities", "water" to "Bills & Utilities", "internet" to "Bills & Utilities",
        "mobile" to "Bills & Utilities", "phone" to "Bills & Utilities", "subscription" to "Subscriptions"
    )
    return keywords.firstOrNull { (keyword, _) -> text.contains(keyword) }?.second
}
