package com.zenith.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.database.MIGRATION_17_18
import com.zenith.app.data.models.FinanceScope
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.network.ActiveFamilyBundle
import com.zenith.app.data.repository.FamilyRepository
import com.zenith.app.domain.*
import com.zenith.app.ui.components.FamilyManageSheet
import com.zenith.app.ui.components.FamilySetupModal
import com.zenith.app.ui.components.FamilySetupTab
import com.zenith.app.ui.theme.CashFlowTheme
import com.zenith.app.ui.viewmodel.CashFlowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyCustomNamesAndLabelsTest {

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
        runBlocking {
            withContext(Dispatchers.IO) {
                CashFlowDatabase.getDatabase(app).clearAllTables()
            }
        }
        app.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testMigration17To18AddsCustomFamilyMemberAndTransactionLabelsSafely() {
        val dbName = "migration_17_18_test_${UUID.randomUUID()}.db"
        val callback = object : SupportSQLiteOpenHelper.Callback(17) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY NOT NULL, `title` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `families` (`id` TEXT PRIMARY KEY NOT NULL, `name` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `family_members` (`id` TEXT PRIMARY KEY NOT NULL, `displayName` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `family_transactions` (`id` TEXT PRIMARY KEY NOT NULL, `title` TEXT NOT NULL)")
                db.execSQL("INSERT INTO `families` VALUES ('f1', 'Sunset Villa')")
                db.execSQL("INSERT INTO `family_members` VALUES ('m1', 'Alex')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app).name(dbName).callback(callback).build()
        )
        val sqliteDb = helper.writableDatabase
        MIGRATION_17_18.migrate(sqliteDb)

        sqliteDb.query("SELECT name, familyLabel FROM `families`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Sunset Villa", c.getString(0))
            assertEquals("", c.getString(1))
        }
        sqliteDb.query("SELECT displayName, memberLabel FROM `family_members`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Alex", c.getString(0))
            assertEquals("", c.getString(1))
        }
        sqliteDb.query("PRAGMA table_info(`family_transactions`)").use { c ->
            val cols = mutableListOf<String>()
            while (c.moveToNext()) cols.add(c.getString(1))
            assertTrue(cols.contains("customLabel"))
        }
        helper.close()
        app.deleteDatabase(dbName)
    }

    @Test
    fun testSetupModalCapturesCustomFamilyNameLabelAndMemberNameLabelAndEmojis() {
        var capturedFamilyName = ""
        var capturedFamilyEmoji = ""
        var capturedFamilyLabel = ""
        var capturedCreatorName = ""
        var capturedCreatorEmoji = ""
        var capturedCreatorLabel = ""

        composeTestRule.setContent {
            CashFlowTheme {
                FamilySetupModal(
                    initialTab = FamilySetupTab.CREATE,
                    currencySymbol = "₹",
                    currentUserName = "Default User",
                    isAuthenticated = true,
                    onCreateFamily = { _, _, _, _, _ -> },
                    onJoinFamily = { _, _, _, _ -> },
                    onDismiss = {},
                    onCreateFamilyWithCustomProfile = { name, famEmoji, _, _, famLabel, cName, cEmoji, cLabel, onComplete ->
                        capturedFamilyName = name
                        capturedFamilyEmoji = famEmoji
                        capturedFamilyLabel = famLabel
                        capturedCreatorName = cName
                        capturedCreatorEmoji = cEmoji
                        capturedCreatorLabel = cLabel
                        onComplete(Result.success(UUID.randomUUID().toString()))
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("input_family_name").performTextInput("Oceanview Loft")
        composeTestRule.onNodeWithTag("input_family_custom_emoji").performTextReplacement("🏖️")
        composeTestRule.onNodeWithTag("input_family_label").performScrollTo().performTextInput("🛋️ Flatmates")
        composeTestRule.onNodeWithTag("input_creator_display_name").performScrollTo().performTextReplacement("Jordan")
        composeTestRule.onNodeWithTag("input_creator_custom_emoji").performScrollTo().performTextReplacement("🦁")
        composeTestRule.onNodeWithTag("input_creator_member_label").performScrollTo().performTextInput("🌟 Organizer")
        composeTestRule.onNodeWithTag("btn_confirm_create_family").performScrollTo().performClick()

        assertEquals("Oceanview Loft", capturedFamilyName)
        assertEquals("🏖️", capturedFamilyEmoji)
        assertEquals("🛋️ Flatmates", capturedFamilyLabel)
        assertEquals("Jordan", capturedCreatorName)
        assertEquals("🦁", capturedCreatorEmoji)
        assertEquals("🌟 Organizer", capturedCreatorLabel)
    }

    @Test
    fun testManageSheetAllowsEditingCustomFamilyAndMemberNamesAndLabels() {
        val famId = UUID.randomUUID().toString()
        val u1 = UUID.randomUUID().toString()
        var savedFamUpdate: Triple<String, String, String>? = null
        var savedMemberUpdate: Triple<String, String, String>? = null

        val ctx = FinancialContext(
            mode = FinancialMode.FAMILY,
            isFamilyEnabled = true,
            activeFamilyId = famId,
            activeFamily = FamilyInfo(famId, "Green Home", "🌿", u1, 50000.0, familyLabel = "Household"),
            activeMembers = listOf(
                FamilyMemberInfo("m1", famId, u1, "Sam", "🙂", role = FamilyRole.OWNER, memberLabel = "Mom")
            ),
            currentUserId = u1,
            membershipRole = FamilyRole.OWNER,
            familyMembershipStatus = FamilyMembershipStatus.ACTIVE
        )

        composeTestRule.setContent {
            CashFlowTheme {
                FamilyManageSheet(
                    financialContext = ctx,
                    onGenerateInvite = { _, _, _ -> },
                    onUpdateMemberRole = { _, _, _ -> },
                    onRemoveMember = { _, _ -> },
                    onTransferOwnership = { _, _ -> },
                    onLeaveFamily = {},
                    onCloseFamily = {},
                    onDisableFamilyFeature = {},
                    onDismiss = {},
                    onUpdateFamilyProfile = { name, emoji, label, onComplete ->
                        savedFamUpdate = Triple(name, emoji, label)
                        onComplete(Result.success(true))
                    },
                    onUpdateMemberProfile = { uid, displayName, memberLabel, _, onComplete ->
                        savedMemberUpdate = Triple(uid, displayName, memberLabel)
                        onComplete(Result.success(true))
                    }
                )
            }
        }

        // Customize family name & label
        composeTestRule.onNodeWithTag("btn_edit_family_profile").performClick()
        composeTestRule.onNodeWithTag("input_edit_family_name").performTextReplacement("Mountain Cabin")
        composeTestRule.onNodeWithTag("input_edit_family_label").performTextReplacement("Vacation Crew")
        composeTestRule.onNodeWithTag("btn_save_family_profile").performClick()
        assertEquals(Triple("Mountain Cabin", "🌿", "Vacation Crew"), savedFamUpdate)

        // Customize member name & label
        composeTestRule.onNodeWithTag("btn_edit_member_$u1").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("input_edit_member_name").performTextReplacement("Taylor")
        composeTestRule.onNodeWithTag("input_edit_member_label").performTextReplacement("Organizer")
        composeTestRule.onNodeWithTag("btn_save_member_profile").performClick()
        assertEquals(Triple(u1, "Taylor", "Organizer"), savedMemberUpdate)
    }

    @Test
    fun testViewModelFiltersTransactionsByCustomLabelAndCarriesMemberLabels() = runBlocking {
        val singletonDb = CashFlowDatabase.getDatabase(app)
        withContext(Dispatchers.IO) { singletonDb.clearAllTables() }

        val uid = UUID.randomUUID().toString()
        val famId = UUID.randomUUID().toString()
        val vm = CashFlowViewModel(app)
        vm.setAuthenticatedUser("taylor@example.com", "Taylor", userId = uid, isGuest = false)

        val famInfo = FamilyInfo(famId, "Cabin Group", "🏡", uid, familyLabel = "Household")
        val members = listOf(
            FamilyMemberInfo("m1", famId, uid, "Taylor", "🙂", role = FamilyRole.OWNER, memberLabel = "Organizer")
        )
        FamilyRepository(singletonDb.familyDao()).cacheActiveBundle(
            ActiveFamilyBundle(famInfo, members.first(), members)
        )
        vm.applyLocalCachedFamilyContextForTest(famInfo, members, FamilyRole.OWNER, enterFamilyMode = true)

        vm.addTransaction(
            title = "Hotel Booking",
            amount = 4200.0,
            type = TransactionType.EXPENSE,
            category = "Travel",
            paymentMethod = "Card",
            note = "",
            paidByUserId = uid,
            customLabel = "Reimbursable"
        )
        vm.addTransaction(
            title = "Coffee Stop",
            amount = 240.0,
            type = TransactionType.EXPENSE,
            category = "Food & Dining",
            paymentMethod = "UPI",
            note = "",
            paidByUserId = uid,
            customLabel = "Snack"
        )

        val deadline = System.currentTimeMillis() + 3500L
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (vm.uiState.value.allTransactions.size == 2) break
            delay(25)
        }
        assertEquals(2, vm.uiState.value.allTransactions.size)

        vm.setFilterLabel("Reimbursable")
        val labelDeadline = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < labelDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (vm.uiState.value.transactions.size == 1) break
            delay(20)
        }
        assertEquals(1, vm.uiState.value.transactions.size)
        assertEquals("Hotel Booking", vm.uiState.value.transactions.first().title)
        assertEquals("Reimbursable", vm.uiState.value.transactions.first().customLabel)
        val attr = vm.uiState.value.txAttributionByServerId[vm.uiState.value.transactions.first().serverId]
        assertEquals("Organizer", attr?.paidByLabel)
    }
}
