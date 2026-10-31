package com.dragosfotache.gymtracka.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Waits without owning account/run locks. Wall-clock changes recheck the durable deadline. */
internal suspend fun awaitAccountRetry(
    retryAtMillis: Long,
    clockMillis: () -> Long,
    onRemainingSeconds: (Long) -> Unit,
) {
    while (true) {
        val remaining = (retryAtMillis - clockMillis().coerceAtLeast(0L)).coerceAtLeast(0L)
        onRemainingSeconds(remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L)
        if (remaining == 0L) return
        delay(minOf(remaining, 1_000L))
    }
}

/** One retry per exact owner/deadline, suspended outside STARTED and cancelled on turnover. */
@Composable
internal fun accountRetrySeconds(
    owner: Any?,
    retryAtMillis: Long?,
    enabled: Boolean,
    clockMillis: () -> Long,
    onRetry: () -> Unit,
): Long? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var remaining by remember(owner, retryAtMillis) { mutableStateOf<Long?>(null) }
    var attempted by remember(owner, retryAtMillis) { mutableStateOf(false) }
    val clock by rememberUpdatedState(clockMillis)
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(owner, retryAtMillis, enabled, lifecycle) {
        if (owner == null || retryAtMillis == null || !enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (attempted) return@repeatOnLifecycle
            awaitAccountRetry(retryAtMillis, { clock() }) { remaining = it }
            attempted = true
            retry()
        }
    }
    return if (enabled && retryAtMillis != null) remaining else null
}

internal fun accountRetryText(seconds: Long): String {
    val minutes = seconds / 60L
    val remainder = seconds % 60L
    val duration = if (minutes == 0L) "${remainder}s" else "${minutes}m ${remainder}s"
    return "Sync will retry in $duration while the app is open."
}
