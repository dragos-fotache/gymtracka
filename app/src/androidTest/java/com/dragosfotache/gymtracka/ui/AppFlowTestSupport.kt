package com.dragosfotache.gymtracka.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onRoot
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule

abstract class AppFlowTestSupport : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private var compositionScopeJob: Job? = null
    private var appEnabled: MutableState<Boolean>? = null
    private var rememberedAppState: GymAppState? = null

    protected fun editingRoutineId(): String? = rememberedAppState?.editingRoutineId

    protected fun setUpGymApp() {
        composeRule.setContent {
            GymTrackaTheme {
                val enabled = remember { mutableStateOf(true) }
                appEnabled = enabled
                if (enabled.value) {
                    CompositionLocalProvider(LocalGymRepositoryOverride provides repository) {
                        val scope = rememberCoroutineScope()
                        DisposableEffect(scope) {
                            compositionScopeJob = scope.coroutineContext[Job]
                            onDispose { }
                        }
                        val state = remember(repository, scope) { GymAppState(repository, scope) }
                        rememberedAppState = state
                        GymApp(state)
                    }
                }
            }
        }
    }

    protected fun waitForUi(description: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000, condition = condition)
        } catch (failure: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("AppFlowTest-$description")
            throw failure
        }
    }

    @After
    fun disposeAppCompositionBeforeDatabase() {
        composeRule.runOnIdle { appEnabled?.value = false }
        composeRule.waitForIdle()
        compositionScopeJob?.let { job ->
            runBlocking { withTimeout(5_000) { job.join() } }
        }
    }
}
