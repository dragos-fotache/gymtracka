package com.dragosfotache.gymtracka.ui.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.dragosfotache.gymtracka.ui.routines.RoutineSection
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle

@Composable
internal fun WorkoutScreen(
    modifier: Modifier = Modifier,
    onStartEmptyWorkout: () -> Unit,
    onNewRoutine: () -> Unit,
    onRoutineSelected: (String) -> Unit,
    onEditRoutine: (String) -> Unit,
    onStartRoutine: (String) -> Unit,
) {
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        Column(modifier.fillMaxSize().padding(top = grid.space(24))) {
            WorkoutGridLabel(
                "Workout",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                fontWeight = FontWeight.Bold,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = grid.space(23), end = grid.space(23),
                    top = grid.space(24), bottom = grid.space(120),
                ),
                verticalArrangement = Arrangement.spacedBy(grid.space(24)),
            ) {
                item {
                    WorkoutActionButton(
                        "Start empty workout", onStartEmptyWorkout,
                        Modifier.fillMaxWidth().testTag("start-empty-workout"),
                    )
                }
                item {
                    RoutineSection(
                        onNewRoutine = onNewRoutine,
                        onRoutineSelected = onRoutineSelected,
                        onEditRoutine = onEditRoutine,
                        onStartRoutine = onStartRoutine,
                    )
                }
            }
        }
    }
}
