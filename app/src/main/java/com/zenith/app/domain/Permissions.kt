package com.zenith.app.domain

import com.zenith.app.data.models.FamilyRole
import com.zenith.app.data.models.FinanceScope

object Permissions {
    fun canAddTransaction(scope: FinanceScope, userRole: FamilyRole?): Boolean {
        if (scope == FinanceScope.PERSONAL) return true
        return userRole == FamilyRole.ADMIN || userRole == FamilyRole.MEMBER
    }

    fun canDeleteTransaction(scope: FinanceScope, userRole: FamilyRole?, isCreator: Boolean): Boolean {
        if (scope == FinanceScope.PERSONAL) return true
        if (userRole == FamilyRole.ADMIN) return true
        if (userRole == FamilyRole.MEMBER && isCreator) return true
        return false
    }

    fun canManageBudgetsAndGoals(scope: FinanceScope, userRole: FamilyRole?): Boolean {
        if (scope == FinanceScope.PERSONAL) return true
        return userRole == FamilyRole.ADMIN
    }

    fun canManageMembers(userRole: FamilyRole?): Boolean {
        return userRole == FamilyRole.ADMIN
    }
}
