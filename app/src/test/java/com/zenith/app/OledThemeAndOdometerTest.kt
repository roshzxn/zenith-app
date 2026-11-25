package com.zenith.app

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.zenith.app.ui.components.RollingCounterText
import com.zenith.app.ui.theme.*
import com.zenith.app.ui.viewmodel.CashFlowViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OledThemeAndOdometerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testOledAndLightColorTokens() {
        assertEquals(Color(0xFF000000), OledPureBlackBackground)
        assertEquals(Color(0xFF080808), OledPureBlackSurface)
        assertEquals(Color(0xFF121212), OledPureBlackSurfaceVariant)
        assertEquals(Color(0xFFF3F6FC), LightBackground)
        assertEquals(Color(0xFFFFFFFF), LightSurface)
        assertEquals(Color(0xFFEFF3F9), LightSurfaceVariant)
        assertEquals(Color(0xFFD6DFEB), LightBorderSubtle)
    }

    @Test
    fun testZenithThemeModes() {
        assertEquals(3, ZenithThemeMode.values().size)
        assertTrue(ZenithThemeMode.values().contains(ZenithThemeMode.LIGHT))
        assertTrue(ZenithThemeMode.values().contains(ZenithThemeMode.OBSIDIAN_NAVY))
        assertTrue(ZenithThemeMode.values().contains(ZenithThemeMode.OLED_BLACK))
    }

    @Test
    fun testThemeCompositionLocalsAcrossAllThreeModes() {
        var lightModeObserved: ZenithThemeMode? = null
        var darkModeObserved: ZenithThemeMode? = null
        var oledModeObserved: ZenithThemeMode? = null
        var lightSolidGreen: Color? = null
        var lightSolidRed: Color? = null
        var lightAccentGreen: Color? = null
        var darkAccentGreen: Color? = null

        composeTestRule.setContent {
            CashFlowTheme(isDarkMode = false, isOledBlack = true) {
                lightModeObserved = ZenithTheme.mode
                lightSolidGreen = ZenithTheme.solidGreen
                lightSolidRed = ZenithTheme.solidRed
                lightAccentGreen = ZenithTheme.accentGreen
            }
            CashFlowTheme(isDarkMode = true, isOledBlack = false) {
                darkModeObserved = ZenithTheme.mode
                darkAccentGreen = ZenithTheme.accentGreen
            }
            CashFlowTheme(isDarkMode = true, isOledBlack = true) {
                oledModeObserved = ZenithTheme.mode
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(ZenithThemeMode.LIGHT, lightModeObserved)
        assertEquals(ZenithThemeMode.OBSIDIAN_NAVY, darkModeObserved)
        assertEquals(ZenithThemeMode.OLED_BLACK, oledModeObserved)
        assertEquals(Color(0xFF047857), lightSolidGreen)
        assertEquals(Color(0xFFBE123C), lightSolidRed)
        assertTrue(lightAccentGreen != null && darkAccentGreen != null && lightAccentGreen != darkAccentGreen)
    }

    @Test
    fun testRollingCounterText_RendersCorrectly() {
        composeTestRule.setContent {
            CashFlowTheme(isDarkMode = false, isOledBlack = false) {
                RollingCounterText(
                    amount = 12500.50,
                    currencySymbol = "₹",
                    decimalPlaces = 2
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("₹").assertExists()
        composeTestRule.onNodeWithText(",").assertExists()
        composeTestRule.onNodeWithText(".").assertExists()
    }

    @Test
    fun testRollingCounterText_Masked() {
        composeTestRule.setContent {
            CashFlowTheme(isDarkMode = true, isOledBlack = true) {
                RollingCounterText(
                    amount = 5400.0,
                    isMasked = true
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("••••••").assertExists()
    }

    @Test
    fun testThemeModeAndAppLockState_TogglesInViewModel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = CashFlowViewModel(app)

        viewModel.setOledBlack(true)
        assertTrue(viewModel.isOledBlack.value)
        assertTrue(viewModel.isDarkMode.value)

        viewModel.setDarkMode(false)
        assertFalse(viewModel.isDarkMode.value)
        assertFalse(viewModel.isOledBlack.value)

        viewModel.setDarkMode(true)
        assertTrue(viewModel.isDarkMode.value)
        assertFalse(viewModel.isOledBlack.value)

        viewModel.setAppLockEnabled(true)
        assertTrue(viewModel.isAppLockEnabled.value)
        viewModel.setAppLockEnabled(false)
        assertFalse(viewModel.isAppLockEnabled.value)
    }
}
