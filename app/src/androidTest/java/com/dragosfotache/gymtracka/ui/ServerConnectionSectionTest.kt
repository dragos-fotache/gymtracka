package com.dragosfotache.gymtracka.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.sync.api.ServerChecker
import com.dragosfotache.gymtracka.data.sync.api.ServerCheckResult
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionState
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionStatus
import com.dragosfotache.gymtracka.ui.settings.SettingsScreen
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerConnectionSectionTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val scope = MainScope()
    private val shown = mutableStateOf(true)
    private var connection: ServerConnectionState? = null

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @After
    fun close() {
        composeRule.runOnIdle {
            shown.value = false
            connection?.dispose()
            scope.cancel()
        }
        runBlocking { withTimeout(5_000L) { scope.coroutineContext[Job]?.join() } }
    }

    @Test
    fun entryForegroundReturnRecheckAndDisposalOwnOnlyOneProbe() {
        lateinit var owner: Owner
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        }
        var calls = 0
        var cancelled = 0
        var answer = CompletableDeferred<ServerCheckResult>()
        val state = ServerConnectionState(
            ServerChecker {
                calls++
                try { answer.await() } finally { cancelled++ }
            },
            scope,
        ).also { connection = it }
        composeRule.setContent {
            GymTrackaTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (shown.value) SettingsScreen(serverConnectionState = state, accountContent = { Text("Account controls") })
                }
            }
        }
        composeRule.waitUntil(5_000L) { calls == 1 }
        composeRule.onNodeWithTag("settings-server-status").assertTextEquals("Checking")
        composeRule.onNodeWithTag("settings-server-status-dot").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-server-progress").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-server-recheck").assertIsNotEnabled()
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitUntil(5_000L) { cancelled == 1 }
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitUntil(5_000L) { calls == 2 }
        composeRule.runOnIdle { answer.complete(ServerCheckResult.CONNECTED) }
        composeRule.waitUntil(5_000L) { state.status.value == ServerConnectionStatus.CONNECTED }
        composeRule.onNodeWithTag("settings-server-status").assertTextEquals("Online")
        composeRule.runOnIdle { answer = CompletableDeferred() }
        composeRule.onNodeWithTag("settings-server-recheck").performScrollTo().performClick()
        composeRule.waitUntil(5_000L) { calls == 3 }
        composeRule.runOnIdle { shown.value = false }
        composeRule.waitUntil(5_000L) { cancelled == 3 }
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
        composeRule.runOnIdle { shown.value = true }
        composeRule.waitUntil(5_000L) { calls == 4 }
        composeRule.runOnIdle { answer.complete(ServerCheckResult.UNREACHABLE) }
        composeRule.waitUntil(5_000L) { state.status.value == ServerConnectionStatus.UNREACHABLE }
        composeRule.onNodeWithTag("settings-server-status").assertTextEquals("Offline")
        assertTrue(scope.coroutineContext[Job]?.isActive == true)
    }
}
