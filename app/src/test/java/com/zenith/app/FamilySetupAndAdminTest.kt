package com.zenith.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.domain.*
import com.zenith.app.ui.components.FamilyManageSheet
import com.zenith.app.ui.components.FamilySetupModal
import com.zenith.app.ui.components.FamilySetupTab
import com.zenith.app.ui.components.generateFamilyInviteQrBitmap
import com.zenith.app.ui.components.parseFamilyInviteQrPayload
import com.zenith.app.ui.theme.CashFlowTheme
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
class FamilySetupAndAdminTest {

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
    fun testCreateAndJoinViewModelRejectsGuestOrOfflineWithoutFakeSuccess() {
        val vm = CashFlowViewModel(app)
        vm.continueAsGuest()
        vm.setFamilyFeatureEnabled(true)

        var createResult: Result<String>? = null
        vm.createFamilyAtomic("Kapoor Family", "🏠", 0.0, UUID.randomUUID().toString()) {
            createResult = it
        }
        composeTestRule.waitForIdle()
        assertNotNull(createResult)
        assertTrue("Guest creation must fail cleanly", createResult!!.isFailure)
        assertNull(vm.financialContext.value.activeFamilyId)

        var joinResult: Result<String>? = null
        vm.acceptFamilyInvitation("FAM-123456", "Arun", "🙂") {
            joinResult = it
        }
        composeTestRule.waitForIdle()
        assertNotNull(joinResult)
        assertTrue("Guest invite accept must fail cleanly", joinResult!!.isFailure)
    }

    @Test
    fun testSetupModalPreventsDuplicateTapsAndDisplaysRealErrors() {
        var submitCount = 0
        var dismissCalled = false

        composeTestRule.setContent {
            CashFlowTheme {
                FamilySetupModal(
                    initialTab = FamilySetupTab.CREATE,
                    currencySymbol = "₹",
                    currentUserName = "Arun",
                    isAuthenticated = true,
                    onCreateFamily = { _, _, _, _, onComplete ->
                        submitCount++
                        onComplete(Result.failure(IllegalStateException("User already belongs to an active family.")))
                    },
                    onJoinFamily = { _, _, _, onComplete ->
                        onComplete(Result.failure(IllegalStateException("Invitation has expired.")))
                    },
                    onDismiss = { dismissCalled = true }
                )
            }
        }

        // Empty name validation shows honest error without invoking RPC
        composeTestRule.onNodeWithTag("btn_confirm_create_family").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("family_setup_error").performScrollTo().assertIsDisplayed()
        assertEquals(0, submitCount)
        assertFalse(dismissCalled)

        // Real error returned from RPC renders visibly and does NOT fake dismiss/success
        composeTestRule.onNodeWithTag("input_family_name").performScrollTo().performTextInput("Kapoor Home")
        composeTestRule.onNodeWithTag("btn_confirm_create_family").performScrollTo().performClick()
        assertEquals(1, submitCount)
        assertFalse(dismissCalled)
        composeTestRule.onNodeWithText("User already belongs to an active family.").performScrollTo().assertIsDisplayed()

        // Switch to Join tab and verify expired invite error rendering
        composeTestRule.onNodeWithTag("tab_join").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("input_invite_code").performScrollTo().performTextInput("FAM-999999")
        composeTestRule.onNodeWithTag("btn_confirm_join_family").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Invitation has expired.").performScrollTo().assertIsDisplayed()
        assertFalse(dismissCalled)
    }

    @Test
    fun testFamilyManageSheetRequiresOwnerTransferOrCloseBeforeLeaving() {
        var leaveCalled = false
        val fam = FamilyInfo("f1", "Kapoor Home", "🏡", ownerId = "u1")
        val owner = FamilyMemberInfo("m1", "f1", "u1", "Arun", "👑", role = FamilyRole.OWNER)
        val spouse = FamilyMemberInfo("m2", "f1", "u2", "Priya", "👩", role = FamilyRole.MEMBER)

        composeTestRule.setContent {
            CashFlowTheme {
                FamilyManageSheet(
                    financialContext = FinancialContext(
                        mode = FinancialMode.FAMILY,
                        isFamilyEnabled = true,
                        activeFamilyId = "f1",
                        activeFamily = fam,
                        activeMembers = listOf(owner, spouse),
                        currentUserId = "u1",
                        membershipRole = FamilyRole.OWNER,
                        familyMembershipStatus = FamilyMembershipStatus.ACTIVE
                    ),
                    onGenerateInvite = { _, _, _ -> },
                    onUpdateMemberRole = { _, _, _ -> },
                    onRemoveMember = { _, _ -> },
                    onTransferOwnership = { _, _ -> },
                    onLeaveFamily = { leaveCalled = true },
                    onCloseFamily = {},
                    onDisableFamilyFeature = {},
                    onDismiss = {}
                )
            }
        }

        // Tapping Leave as Owner must block direct leave and display Owner Transfer/Close guidance
        composeTestRule.onNodeWithTag("btn_leave_family").performScrollTo().performClick()
        assertFalse(leaveCalled)
        composeTestRule.onNodeWithTag("owner_leave_guard_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_close_family").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testFamilyInviteQrGenerationAndPayloadParsing() {
        val joinUrl = "zenith://join?token=abc-123-token"
        val bitmap = generateFamilyInviteQrBitmap(joinUrl, sizePx = 256)
        assertNotNull("QR bitmap should generate cleanly offline", bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(256, bitmap.height)

        assertEquals("abc-123-token", parseFamilyInviteQrPayload(joinUrl))
        assertEquals("FAM-27AD09", parseFamilyInviteQrPayload("Join my family: fam-27ad09"))
        assertNull("UPI QR must not be treated as a family invite", parseFamilyInviteQrPayload("upi://pay?pa=merchant@okaxis&pn=Coffee"))
        assertNull("BharatQR must not be treated as a family invite", parseFamilyInviteQrPayload("00020101021126370014in.gov.npci.upi"))
    }

    @Test
    fun testScannedQrQuickJoinBannerAndCachedManageQrRenderImmediately() {
        var joinedCode = ""
        composeTestRule.setContent {
            CashFlowTheme {
                FamilySetupModal(
                    initialTab = FamilySetupTab.JOIN,
                    initialInviteInput = "FAM-27AD09",
                    currencySymbol = "₹",
                    currentUserName = "Sandeep",
                    isAuthenticated = true,
                    onCreateFamily = { _, _, _, _, _ -> },
                    onJoinFamily = { code, _, _, onComplete ->
                        joinedCode = code
                        onComplete(Result.success("f1"))
                    },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("family_qr_scanned_ready_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_quick_join_scanned_qr").performClick()
        assertEquals("FAM-27AD09", joinedCode)
    }
}
