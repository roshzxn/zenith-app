package com.zenith.app

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.data.database.MIGRATION_19_20
import com.zenith.app.data.util.AmountFormatter
import com.zenith.app.data.util.NumberFormatStyle
import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyMembershipStatus
import com.zenith.app.domain.FamilyRole
import com.zenith.app.domain.FinancialContext
import com.zenith.app.domain.FinancialMode
import com.zenith.app.ui.screens.HomeScreen
import com.zenith.app.ui.theme.CashFlowTheme
import com.zenith.app.ui.theme.ZenithAccentPreset
import com.zenith.app.ui.theme.ZenithCardDensity
import com.zenith.app.ui.theme.ZenithMotionSpec
import com.zenith.app.ui.theme.ZenithTheme
import com.zenith.app.ui.viewmodel.CashFlowUiState
import com.zenith.app.ui.viewmodel.CashFlowViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomizationAndPublishReadinessTest {

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
    fun testAmountFormatterSupportsIndianInternationalAndCurrencyCodes() {
        assertEquals("1,00,000", AmountFormatter.formatNumber(100000.0, 0, NumberFormatStyle.INDIAN))
        assertEquals("12,34,567.89", AmountFormatter.formatNumber(1234567.89, 2, NumberFormatStyle.INDIAN))
        assertEquals("100,000", AmountFormatter.formatNumber(100000.0, 0, NumberFormatStyle.INTERNATIONAL))
        assertEquals("-₹1,00,000", AmountFormatter.formatCurrency(-100000.0, "₹", 0, NumberFormatStyle.INDIAN))

        assertEquals("INR", AmountFormatter.currencyCodeForSymbol("₹"))
        assertEquals("USD", AmountFormatter.currencyCodeForSymbol("$"))
        assertEquals("EUR", AmountFormatter.currencyCodeForSymbol("€"))
        assertEquals("GBP", AmountFormatter.currencyCodeForSymbol("£"))
        assertEquals("JPY", AmountFormatter.currencyCodeForSymbol("¥"))
    }

    @Test
    fun testThemeCompositionLocalsExposeCustomizationTokens() {
        var accent: ZenithAccentPreset? = null
        var motion: ZenithMotionSpec? = null
        var density: ZenithCardDensity? = null
        var format: NumberFormatStyle? = null

        composeTestRule.setContent {
            CashFlowTheme(
                isDarkMode = true,
                isOledBlack = false,
                accentPreset = ZenithAccentPreset.ROSHZEN,
                motionSpec = ZenithMotionSpec.CRISP,
                cardDensity = ZenithCardDensity.COMPACT,
                numberFormatStyle = NumberFormatStyle.INDIAN
            ) {
                accent = ZenithTheme.accentPreset
                motion = ZenithTheme.motionSpec
                density = ZenithTheme.cardDensity
                format = ZenithTheme.numberFormat
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(ZenithAccentPreset.ROSHZEN, accent)
        assertEquals(ZenithMotionSpec.CRISP, motion)
        assertEquals(ZenithCardDensity.COMPACT, density)
        assertEquals(NumberFormatStyle.INDIAN, format)
    }

    @Test
    fun testViewModelPersistsCustomizationStudioPreferences() {
        val vm1 = CashFlowViewModel(app)
        vm1.setAccentPreset(ZenithAccentPreset.ROSHZEN)
        vm1.setMotionSpec(ZenithMotionSpec.CRISP)
        vm1.setCardDensity(ZenithCardDensity.COMPACT)
        vm1.setNumberFormatStyle(NumberFormatStyle.INDIAN)
        vm1.setDefaultStartTab(2)
        vm1.setMaskBalancesOnLaunch(true)
        vm1.setRecentsPrivacyEnabled(true)
        vm1.setBudgetAlertThresholdPct(90)

        val vm2 = CashFlowViewModel(app)
        assertEquals(ZenithAccentPreset.ROSHZEN, vm2.accentPreset.value)
        assertEquals(ZenithMotionSpec.CRISP, vm2.motionSpec.value)
        assertEquals(ZenithCardDensity.COMPACT, vm2.cardDensity.value)
        assertEquals(NumberFormatStyle.INDIAN, vm2.numberFormatStyle.value)
        assertEquals(2, vm2.defaultStartTab.value)
        assertTrue(vm2.maskBalancesOnLaunch.value)
        assertTrue(vm2.recentsPrivacyEnabled.value)
        assertEquals(90, vm2.budgetAlertThresholdPct.value)
        assertTrue(vm2.isBalanceHidden.value)
    }

    @Test
    fun testHomeFamilyActionStripShowsUniformLabeledButtonsWhenAdminAndWhenSetup() {
        var ctx = FinancialContext(
            mode = FinancialMode.FAMILY,
            isFamilyEnabled = true,
            activeFamilyId = "fam-1",
            activeFamily = FamilyInfo(id = "fam-1", name = "Roshan Family", avatarEmoji = "🏡"),
            membershipRole = FamilyRole.ADMIN,
            familyMembershipStatus = FamilyMembershipStatus.ACTIVE
        )

        composeTestRule.setContent {
            CashFlowTheme {
                HomeScreen(
                    state = CashFlowUiState(),
                    totalIncome = 10000.0,
                    totalExpense = 2500.0,
                    netBalance = 7500.0,
                    onOpenAddTransaction = {},
                    onOpenVoiceAssistant = {},
                    onOpenReceiptScanner = {},
                    onDeleteTransaction = {},
                    onNavigateToActivity = {},
                    onNavigateToProfile = {},
                    financialContext = ctx,
                    onOpenFamilyInviteQr = {},
                    onOpenFamilyManage = {},
                    onOpenFamilyChat = {},
                    onOpenScanFamilyQr = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("home_family_action_strip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_home_family_chat").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_home_family_invite_qr").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_home_family_manage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Invite QR").assertExists()
    }

    @Test
    fun testMigration19To20AddsCustomPeriodNameAndDropsDeadTables() {
        val dbFile = app.getDatabasePath("test_migration_19_20.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL("CREATE TABLE family_budgets (id TEXT NOT NULL PRIMARY KEY, familyId TEXT NOT NULL, category TEXT NOT NULL, monthlyLimit REAL NOT NULL, periodType TEXT NOT NULL DEFAULT 'MONTHLY', monthYear TEXT NOT NULL DEFAULT '', updatedAt INTEGER NOT NULL DEFAULT 0)")
        rawDb.execSQL("CREATE TABLE ledger_transactions (id INTEGER PRIMARY KEY)")
        rawDb.execSQL("CREATE TABLE family_vaults (id INTEGER PRIMARY KEY)")
        rawDb.execSQL("CREATE TABLE family_vault_members (id INTEGER PRIMARY KEY)")
        rawDb.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val supportDb = helper.writableDatabase
        MIGRATION_19_20.migrate(supportDb)

        val cursor = supportDb.query("PRAGMA table_info(`family_budgets`)")
        val cols = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            cols.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()
        assertTrue("Expected customPeriodName on family_budgets after MIGRATION_19_20", cols.contains("customPeriodName"))

        val tablesCursor = supportDb.query("SELECT name FROM sqlite_master WHERE type='table'")
        val remainingTables = mutableSetOf<String>()
        while (tablesCursor.moveToNext()) {
            remainingTables.add(tablesCursor.getString(0))
        }
        tablesCursor.close()
        assertFalse(remainingTables.contains("ledger_transactions"))
        assertFalse(remainingTables.contains("family_vaults"))
        assertFalse(remainingTables.contains("family_vault_members"))

        helper.close()
        dbFile.delete()
    }
}
