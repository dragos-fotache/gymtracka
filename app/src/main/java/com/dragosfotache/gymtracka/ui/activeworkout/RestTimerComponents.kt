package com.dragosfotache.gymtracka.ui.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.ActiveRestTimer
import com.dragosfotache.gymtracka.ui.util.formatCountdown
import com.dragosfotache.gymtracka.ui.util.formatRestDuration
import kotlin.math.abs

@Composable
internal fun RestDurationRow(restSeconds: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            "Rest Timer: ${formatRestDuration(restSeconds)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
internal fun RestDurationDialog(
    initialRestSeconds: Int,
    onDismiss: () -> Unit,
    onDone: (Int) -> Unit,
) {
    var selectedMinutes by remember(initialRestSeconds) {
        mutableIntStateOf((initialRestSeconds / 60).coerceIn(0, 9))
    }
    var selectedSeconds by remember(initialRestSeconds) {
        mutableIntStateOf(((initialRestSeconds % 60) / 5 * 5).coerceIn(0, 55))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rest timer", style = MaterialTheme.typography.titleMedium) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                RestDurationPickerColumn(
                    label = "Minutes",
                    values = (0..9).toList(),
                    selectedValue = selectedMinutes,
                    onValueSelected = { selectedMinutes = it },
                    modifier = Modifier.width(84.dp),
                )
                RestDurationPickerColumn(
                    label = "Seconds",
                    values = (0..55 step 5).toList(),
                    selectedValue = selectedSeconds,
                    onValueSelected = { selectedSeconds = it },
                    modifier = Modifier.width(84.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDone(selectedMinutes * 60 + selectedSeconds) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun RestDurationPickerColumn(
    label: String,
    values: List<Int>,
    selectedValue: Int,
    onValueSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = values.indexOf(selectedValue).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    var centeredIndex by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.centeredItemIndex() }.collect { index ->
            if (index != null && index != centeredIndex) {
                centeredIndex = index
                onValueSelected(values[index])
            }
        }
    }
    // Stable listState-keyed idle snap: sequential collection (never
    // collectLatest, which would cancel its own animation) coalesces our own
    // true/false while settling. On idle the centered index is found, the
    // current callback is notified, and animate runs only when actually
    // misaligned — so the dialog quiesces on opening and after settling
    // instead of repeatedly scheduling/cancelling no-op snap work.
    val currentOnValueSelected by rememberUpdatedState(onValueSelected)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val index = listState.centeredItemIndex() ?: return@collect
            if (index != centeredIndex) {
                centeredIndex = index
                currentOnValueSelected(values[index])
            }
            if (listState.firstVisibleItemIndex != index ||
                listState.firstVisibleItemScrollOffset != 0
            ) {
                listState.animateScrollToItem(index)
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 40.dp),
            ) {
                items(values, key = { it }) { value ->
                    val isSelected = values[centeredIndex] == value
                    Text(
                        value.toString(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().height(40.dp).padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListState.centeredItemIndex(): Int? {
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return layoutInfo.visibleItemsInfo.minByOrNull { item ->
        abs((item.offset + item.size / 2) - viewportCenter)
    }?.index
}

@Composable
internal fun RestTimerBar(
    timer: ActiveRestTimer,
    remainingSeconds: Int,
    onSkip: () -> Unit,
    onDurationAdjusted: (Int) -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(
                            (remainingSeconds.toFloat() / timer.durationSeconds.coerceAtLeast(1))
                                .coerceIn(0f, 1f),
                        )
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RestTimerAdjustmentButton("−15", "rest-timer-minus") { onDurationAdjusted(-15) }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            formatCountdown(remainingSeconds),
                            style = MaterialTheme.typography.displaySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    RestTimerAdjustmentButton("+15", "rest-timer-plus") { onDurationAdjusted(15) }
                    Spacer(Modifier.width(24.dp))
                    Button(onClick = onSkip, modifier = Modifier.testTag("rest-timer-skip")) { Text("Skip") }
                }
            }
        }
    }
}

@Composable
private fun RestTimerAdjustmentButton(text: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.width(56.dp).height(44.dp).testTag(tag),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
