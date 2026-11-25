package com.zenith.app.data.util

/**
 * Shared single-source mapping from transaction category names to Material icon identifiers.
 */
object CategoryIconHelper {
    fun iconNameForCategory(category: String): String = when (category.trim()) {
        "Food & Dining" -> "Restaurant"
        "Shopping" -> "ShoppingBag"
        "Housing & Rent" -> "Home"
        "Transportation" -> "DirectionsCar"
        "Bills & Utilities" -> "Receipt"
        "Entertainment" -> "Movie"
        "Healthcare", "Health" -> "MedicalServices"
        "Salary & Income", "Income" -> "Payments"
        "Freelance / Business" -> "Work"
        "Investments" -> "TrendingUp"
        "Education" -> "School"
        else -> "Category"
    }
}
