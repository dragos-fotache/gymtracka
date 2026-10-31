package com.dragosfotache.gymtracka.ui.account

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountRetryEffectTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun backgroundStopsCountdown_foregroundRetriesOnce_andDoesNotReplayOnNextEntry() {
        lateinit var lifecycle: Owner
        composeRule.runOnUiThread { lifecycle = Owner().apply { registry.currentState = Lifecycle.State.STARTED } }
        var now = 0L
        var calls = 0
        val account = Any()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                val seconds = accountRetrySeconds(account, 300_000L, true, { now }) { calls++ }
                Text("remaining=$seconds")
            }
        }
        composeRule.onNodeWithText("remaining=300").assertExists()
        composeRule.runOnIdle {
            lifecycle.registry.currentState = Lifecycle.State.CREATED
            now = 300_000L
        }
        composeRule.runOnIdle { assertEquals(0, calls) }
        composeRule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitUntil(5_000L) { calls == 1 }
        composeRule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        composeRule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.STARTED }
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun cancellationAndOwnerTurnover_disarmOldDeadline_andDisposalCancelsNewWait() {
        var now = 0L
        var calls = 0
        var account by mutableStateOf(Any())
        var enabled by mutableStateOf(true)
        var shown by mutableStateOf(true)
        var deadline by mutableStateOf(300_000L)
        composeRule.setContent {
            if (shown) {
                val seconds = accountRetrySeconds(account, deadline, enabled, { now }) { calls++ }
                Text("remaining=$seconds")
            }
        }
        composeRule.onNodeWithText("remaining=300").assertExists()
        composeRule.runOnIdle { enabled = false }
        composeRule.runOnIdle { now = 300_000L }
        composeRule.runOnIdle { assertEquals(0, calls) }
        composeRule.runOnIdle {
            account = Any()
            deadline = 600_000L
            enabled = true
        }
        composeRule.onNodeWithText("remaining=300").assertExists()
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { now = 600_000L }
        composeRule.runOnIdle { assertEquals(0, calls) }
    }
}
