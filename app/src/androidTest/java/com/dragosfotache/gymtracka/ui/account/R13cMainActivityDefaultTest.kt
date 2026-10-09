package com.dragosfotache.gymtracka.ui.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R13c independent QA: actual MainActivity default composition.
 *
 * Derived ONLY from docs/archive/remaining-work/R13c-task.md plus the coordinator
 * appendix (MainActivity is a ComponentActivity whose theme setup calls no-arg
 * GymApp; the new unconfigured default with empty checked-in BuildConfig must
 * display both account-privacy-gate and account-configuration-unavailable with
 * no workout/catalogue/nav access). No sleeps; real Activity launch.
 */
@RunWith(AndroidJUnit4::class)
class R13cMainActivityDefaultTest {
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
    }
}
