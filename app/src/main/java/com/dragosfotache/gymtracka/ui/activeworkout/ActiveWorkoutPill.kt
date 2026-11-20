package com.dragosfotache.gymtracka.ui.activeworkout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutContentStyle
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.util.formatCountdown
import com.dragosfotache.gymtracka.ui.util.formatElapsedTime
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Composable
internal fun ActiveWorkoutPill(
    workout: WorkoutEntity,
    restTimerRemainingSeconds: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDiscard: () -> Unit,
) {
    var elapsedSeconds by remember(workout.id, workout.startedAtMillis) {
        mutableLongStateOf((System.currentTimeMillis() - workout.startedAtMillis).coerceAtLeast(0L) / 1_000)
    }
    LaunchedEffect(workout.id, workout.startedAtMillis) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - workout.startedAtMillis).coerceAtLeast(0L) / 1_000
            delay(1.seconds)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val grid = WorkoutGrid(maxWidth.value / 448f, LocalDensity.current.fontScale)
        WorkoutContentStyle(grid) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = grid.space(23), vertical = grid.space(12))
                    .testTag("active-workout-pill")
                    .clickable(role = Role.Button, onClickLabel = "Resume workout", onClick = onClick),
                shape = RectangleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(grid.space(2), MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.padding(horizontal = grid.space(12))) {
                    WorkoutGridLabel(
                        "[${workout.name}]",
                        modifier = Modifier.fillMaxWidth().testTag("active-workout-name"),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        horizontalPadding = 0,
                    )
                    ActiveWorkoutPillDetails(formatElapsedTime(elapsedSeconds), restTimerRemainingSeconds, onDiscard)
                }
            }
        }
    }
}

@Composable
private fun ActiveWorkoutPillDetails(elapsed: String, restSeconds: Int?, onDiscard: () -> Unit) {
    val grid = LocalWorkoutGrid.current!!
    Layout(
        modifier = Modifier.fillMaxWidth().testTag("active-workout-metrics"),
        content = {
            WorkoutGridLabel(
                elapsed, Modifier.testTag("active-workout-elapsed"), color = WorkoutMuted,
                horizontalPadding = 0,
            )
            restSeconds?.let {
                WorkoutGridLabel(
                    "Rest ${formatCountdown(it)}", Modifier.testTag("active-workout-rest"),
                    color = MaterialTheme.colorScheme.primary, horizontalPadding = 0,
                )
            }
            WorkoutGridLabel(
                "[Discard]",
                modifier = Modifier.testTag("active-workout-discard")
                    .semantics { contentDescription = "Discard current workout" }
                    .clickable(role = Role.Button, onClick = onDiscard),
                color = MaterialTheme.colorScheme.error,
                horizontalPadding = 0,
            )
        },
    ) { measurables, constraints ->
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val elapsedLabel = items.first()
        val restLabel = if (restSeconds != null) items[1] else null
        val discard = items.last()
        val gap = grid.space(12).roundToPx()
        val inline = items.sumOf { it.width } + gap * (items.size - 1) <= constraints.maxWidth
        val pairedMetrics = restLabel == null || elapsedLabel.width + restLabel.width + gap <= constraints.maxWidth
        val metricsHeight = if (pairedMetrics) {
            maxOf(elapsedLabel.height, restLabel?.height ?: 0)
        } else {
            elapsedLabel.height + restLabel.height
        }
        val height = if (inline) items.maxOf { it.height } else metricsHeight + discard.height
        layout(constraints.maxWidth, height) {
            elapsedLabel.placeRelative(0, if (inline) (height - elapsedLabel.height) / 2 else 0)
            restLabel?.placeRelative(
                x = when {
                    inline -> elapsedLabel.width + gap
                    pairedMetrics -> constraints.maxWidth - restLabel.width
                    else -> 0
                },
                y = when {
                    inline -> (height - restLabel.height) / 2
                    pairedMetrics -> 0
                    else -> elapsedLabel.height
                },
            )
            discard.placeRelative(constraints.maxWidth - discard.width, height - discard.height)
        }
    }
}
