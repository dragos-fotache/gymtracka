package com.dragosfotache.gymtracka.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

@Composable
internal fun ServerConnectionSection(state: ServerConnectionState?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState = lifecycle.currentStateAsState().value
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            state?.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        state?.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            state?.setActive(false)
        }
    }
    val status = state?.status?.collectAsState()?.value ?: ServerConnectionStatus.NOT_CHECKED
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (status) {
                ServerConnectionStatus.CHECKING -> "Checking"
                ServerConnectionStatus.CONNECTED -> "Online"
                else -> "Offline"
            },
            modifier = Modifier.testTag("settings-server-status"),
        )
        Icon(
            Icons.Filled.Circle,
            contentDescription = null,
            tint = when (status) {
                ServerConnectionStatus.CHECKING -> Color(0xFFFBC02D)
                ServerConnectionStatus.CONNECTED -> Color(0xFF2E7D32)
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(10.dp).testTag("settings-server-status-dot"),
        )
    }
    TextButton(
        onClick = { state?.recheck() },
        enabled = state != null && status != ServerConnectionStatus.CHECKING &&
            lifecycleState.isAtLeast(Lifecycle.State.STARTED),
        modifier = Modifier.testTag("settings-server-recheck"),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Text("Check")
    }
}
