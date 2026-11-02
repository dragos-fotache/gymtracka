package com.dragosfotache.gymtracka.ui.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches MainActivity with empty build configuration and checks privacy/configuration gates
 * without feature or navigation access.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityStartupTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun unconfiguredMainActivity_showsBothGatesWithoutNav() {
        rule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        rule.onNodeWithTag("account-configuration-unavailable").assertIsDisplayed()
        rule.onNodeWithTag("account-sign-in").assertDoesNotExist()
        rule.onNodeWithTag("nav-workout").assertDoesNotExist()
        rule.onNodeWithTag("nav-exercises").assertDoesNotExist()
        rule.onNodeWithTag("nav-history").assertDoesNotExist()
        rule.runOnIdle {
            val window = rule.activity.window
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            assertFalse(bars.isAppearanceLightStatusBars)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }
    }
}
