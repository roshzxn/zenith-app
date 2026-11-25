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
    fun testOledColorTokens() {
        assertEquals(Color(0xFF000000), OledPureBlackBackground)
        assertEquals(Color(0xFF080808), OledPureBlackSurface)
        assertEquals(Color(0xFF121212), OledPureBlackSurfaceVariant)
    }

    @Test
    fun testZenithThemeModes() {
        assertEquals(2, ZenithThemeMode.values().size)
        assertTrue(ZenithThemeMode.values().contains(ZenithThemeMode.OBSIDIAN_NAVY))
        assertTrue(ZenithThemeMode.values().contains(ZenithThemeMode.OLED_BLACK))
    }

    @Test
    fun testRollingCounterText_RendersCorrectly() {
        composeTestRule.setContent {
            CashFlowTheme(isOledBlack = false) {
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
            CashFlowTheme(isOledBlack = true) {
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
    fun testOledThemeState_ToggleInViewModel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = CashFlowViewModel(app)

        viewModel.setOledBlack(true)
        assertTrue(viewModel.isOledBlack.value)

        viewModel.setOledBlack(false)
        assertEquals(false, viewModel.isOledBlack.value)
    }
}
