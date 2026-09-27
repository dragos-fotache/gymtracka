package com.dragosfotache.gymtracka.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.routines.RoutineSection

@Composable
internal fun WorkoutScreen(
    modifier: Modifier = Modifier,
    onStartEmptyWorkout: () -> Unit,
    onNewRoutine: () -> Unit,
    onRoutineSelected: (String) -> Unit,
    onEditRoutine: (String) -> Unit,
    onStartRoutine: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Workout", style = MaterialTheme.typography.titleLarge) }
        item {
            Button(onClick = onStartEmptyWorkout, modifier = Modifier.fillMaxWidth()) {
                Text("Start empty workout")
            }
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
