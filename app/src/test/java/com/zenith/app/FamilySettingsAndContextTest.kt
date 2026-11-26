package com.zenith.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.network.FamilySupabaseClient
import com.zenith.app.domain.*
import com.zenith.app.ui.screens.HomeScreen
import com.zenith.app.ui.theme.CashFlowTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import com.zenith.app.ui.viewmodel.CashFlowViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilySettingsAndContextTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testFamilyFeatureDefaultsDisabledAndPersistsAcrossRestartsWithoutAutoCreatingFamily() {
        val uid = UUID.randomUUID().toString()
        val vm1 = CashFlowViewModel(app)
        vm1.setAuthenticatedUser("arun@example.com", "Arun", userId = uid, isGuest = false)

        // Default must be disabled & strictly Personal
        assertFalse(vm1.financialContext.value.isFamilyEnabled)
        assertEquals(FinancialMode.PERSONAL, vm1.financialContext.value.mode)
        assertEquals(FamilyMembershipStatus.DISABLED, vm1.financialContext.value.familyMembershipStatus)
        assertFalse(vm1.selectFinancialMode(FinancialMode.FAMILY))

        // Enabling sets preference true, never auto-creates a fake family
        vm1.setFamilyFeatureEnabled(true)
        assertTrue(vm1.financialContext.value.isFamilyEnabled)
        assertNull(vm1.financialContext.value.activeFamilyId)
        assertNotEquals(FamilyMembershipStatus.ACTIVE, vm1.financialContext.value.familyMembershipStatus)
        assertFalse(vm1.selectFinancialMode(FinancialMode.FAMILY))

        // Simulated app restart preserves enabled state for authenticated user
        val vm2 = CashFlowViewModel(app)
        assertTrue(vm2.financialContext.value.isFamilyEnabled)
        assertEquals(FinancialMode.PERSONAL, vm2.financialContext.value.mode)

        // Disabling resets to Personal and persists disabled
        vm2.setFamilyFeatureEnabled(false)
        assertFalse(vm2.financialContext.value.isFamilyEnabled)
        assertEquals(FamilyMembershipStatus.DISABLED, vm2.financialContext.value.familyMembershipStatus)

        val vm3 = CashFlowViewModel(app)
        assertFalse(vm3.financialContext.value.isFamilyEnabled)
    }

    @Test
    fun testHomeScreenHidesSwitcherWhenDisabledAndPreventsFakeDashboardWhenNoMembership() {
        var setupOpened = false
        var ctxState by mutableStateOf(
            FinancialContext(
                mode = FinancialMode.PERSONAL,
                isFamilyEnabled = false,
                familyMembershipStatus = FamilyMembershipStatus.DISABLED
            )
        )

        composeTestRule.setContent {
            CashFlowTheme {
                HomeScreen(
                    state = CashFlowUiState(),
                    totalIncome = 5000.0,
                    totalExpense = 1200.0,
                    netBalance = 3800.0,
                    onOpenAddTransaction = {},
                    onOpenVoiceAssistant = {},
                    onOpenReceiptScanner = {},
                    onDeleteTransaction = {},
                    onNavigateToActivity = {},
                    onNavigateToProfile = {},
                    financialContext = ctxState,
                    onOpenFamilySetup = { setupOpened = true }
                )
            }
        }

        // 1. Disabled -> switcher must NOT exist
        composeTestRule.onNodeWithTag("home_mode_switcher").assertDoesNotExist()
        composeTestRule.onNodeWithText("TOTAL NET BALANCE").assertIsDisplayed()

        // 2. Enabled but no membership -> tapping Family routes to Setup instead of fake dashboard
        composeTestRule.runOnIdle {
            ctxState = FinancialContext(
                mode = FinancialMode.PERSONAL,
                isFamilyEnabled = true,
                familyMembershipStatus = FamilyMembershipStatus.NONE
            )
        }
        composeTestRule.onNodeWithTag("home_mode_switcher").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_chip_family").performClick()
        assertTrue(setupOpened)
    }

    @Test
    fun testHomeScreenShowsEmojiFamilyBalanceAndChatWhenActive() {
        composeTestRule.setContent {
            CashFlowTheme {
                HomeScreen(
                    state = CashFlowUiState(),
                    totalIncome = 20000.0,
                    totalExpense = 5000.0,
                    netBalance = 15000.0,
                    onOpenAddTransaction = {},
                    onOpenVoiceAssistant = {},
                    onOpenReceiptScanner = {},
                    onDeleteTransaction = {},
                    onNavigateToActivity = {},
                    onNavigateToProfile = {},
                    financialContext = FinancialContext(
                        mode = FinancialMode.FAMILY,
                        isFamilyEnabled = true,
                        activeFamilyId = "fam-uuid",
                        activeFamily = FamilyInfo(id = "fam-uuid", name = "Kapoor Family", avatarEmoji = "🏡"),
                        membershipRole = FamilyRole.OWNER,
                        familyMembershipStatus = FamilyMembershipStatus.ACTIVE
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("🏡 KAPOOR FAMILY BALANCE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_home_family_chat").assertIsDisplayed()
    }

    @Test
    fun testFamilySupabaseClientParsesBundleInvitationAndInviteLinks() {
        val raw = """
            {
              "family": {
                "id": "11111111-1111-1111-1111-111111111111",
                "name": "Home Vault",
                "avatar_emoji": "👨‍👩‍👧‍👦",
                "owner_id": "22222222-2222-2222-2222-222222222222",
                "monthly_budget_limit": 45000.0
              },
              "membership": {
                "id": "33333333-3333-3333-3333-333333333333",
                "family_id": "11111111-1111-1111-1111-111111111111",
                "user_id": "22222222-2222-2222-2222-222222222222",
                "display_name": "Arun",
                "avatar_emoji": "👑",
                "role": "OWNER",
                "status": "ACTIVE"
              },
              "members": [
                {
                  "id": "33333333-3333-3333-3333-333333333333",
                  "family_id": "11111111-1111-1111-1111-111111111111",
                  "user_id": "22222222-2222-2222-2222-222222222222",
                  "display_name": "Arun",
                  "avatar_emoji": "👑",
                  "role": "OWNER",
                  "status": "ACTIVE"
                }
              ]
            }
        """.trimIndent()

        val parsed = FamilySupabaseClient.parseActiveFamilyResponse(raw)
        assertNotNull(parsed)
        assertEquals("Home Vault", parsed!!.family.name)
        assertEquals("👨‍👩‍👧‍👦", parsed.family.avatarEmoji)
        assertEquals(FamilyRole.OWNER, parsed.membership.role)
        assertEquals(45000.0, parsed.family.monthlyBudgetLimit, 0.001)
        assertEquals(1, parsed.members.size)

        assertNull(FamilySupabaseClient.parseActiveFamilyResponse("null"))
        assertEquals(
            "abc-999-token",
            FamilySupabaseClient.extractInviteTokenOrCode("https://zenith.app/join?token=abc-999-token&ref=share")
        )
        assertEquals(
            "FAM-8F3K9A",
            FamilySupabaseClient.extractInviteTokenOrCode("https://zenith.app/join?code=FAM-8F3K9A")
        )
    }
}
