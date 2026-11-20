package com.dragosfotache.gymtracka.ui.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.ActiveRestTimer
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.util.formatCountdown
import com.dragosfotache.gymtracka.ui.util.formatRestDuration
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun RestDurationRow(restSeconds: Int, onClick: () -> Unit) {
    val grid = LocalWorkoutGrid.current
    if (grid != null) {
        WorkoutGridLabel(
            "[Rest Timer: ${formatRestDuration(restSeconds)}]",
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            horizontalPadding = 0,
        )
        return
    }
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
            fontWeight = FontWeight.Medium,
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
    GymDialog(
        onDismissRequest = onDismiss,
        title = "Rest timer",
        text = {
            val grid = LocalWorkoutGrid.current!!
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columnUnits = (floor((maxWidth.value / grid.scale - 12) / 12).toInt() * 6).coerceAtLeast(48)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    RestDurationPickerColumn(
                        label = "Minutes",
                        values = (0..9).toList(),
                        selectedValue = selectedMinutes,
                        onValueSelected = { selectedMinutes = it },
                        modifier = Modifier.width(grid.space(columnUnits)),
                    )
                    RestDurationPickerColumn(
                        label = "Seconds",
                        values = (0..55 step 5).toList(),
                        selectedValue = selectedSeconds,
                        onValueSelected = { selectedSeconds = it },
                        modifier = Modifier.width(grid.space(columnUnits)),
                    )
                }
            }
        },
        confirmAction = GymDialogAction(
            text = "Done",
            onClick = { onDone(selectedMinutes * 60 + selectedSeconds) },
        ),
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
    val grid = LocalWorkoutGrid.current!!
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
        WorkoutGridLabel(
            label,
            color = WorkoutMuted,
            contentAlignment = Alignment.Center,
            horizontalPadding = 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(grid.line * 3),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(grid.line)
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("rest-picker-${label.lowercase()}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = grid.line),
            ) {
                items(values, key = { it }) { value ->
                    val isSelected = values[centeredIndex] == value
                    WorkoutGridLabel(
                        value.toString(),
                        contentAlignment = Alignment.Center,
                        horizontalPadding = 0,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth(),
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
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        (remainingSeconds.toFloat() / timer.durationSeconds.coerceAtLeast(1)).coerceIn(0f, 1f),
                        0f..1f,
                    )
                }
                .heightIn(min = grid.line + grid.space(24))
                .padding(horizontal = grid.space(23), vertical = grid.space(12)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkoutActionButton(
                "-15", { onDurationAdjusted(-15) },
                Modifier.width(grid.space(60)).testTag("rest-timer-minus"),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(grid.space(if (grid.fontScale > 1.2f) 12 else 24)))
            Text(
                formatCountdown(remainingSeconds),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(grid.space(
                    if (grid.fontScale > 1.2f) (72 * grid.fontScale).roundToInt() else 60,
                )),
                maxLines = 1,
            )
            Spacer(Modifier.width(grid.space(if (grid.fontScale > 1.2f) 12 else 24)))
            WorkoutActionButton(
                "+15", { onDurationAdjusted(15) },
                Modifier.width(grid.space(60)).testTag("rest-timer-plus"),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            WorkoutActionButton(
                "Skip", onSkip,
                Modifier.width(grid.space(108)).testTag("rest-timer-skip"),
            )
        }
    }
}
