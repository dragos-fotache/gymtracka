package com.dragosfotache.gymtracka.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import kotlin.math.ceil

internal data class HistorySetRow(
    val id: String,
    val weight: String,
    val repetitions: String,
    val rest: String? = null,
)

/** One column plan per screen keeps every panel on the same six-unit horizontal grid. */
@Composable
internal fun historySetColumnWidths(
    sets: List<HistorySetRow>,
    showRest: Boolean = false,
): List<Dp> {
    val grid = LocalWorkoutGrid.current!!
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold)
    val headings = listOf("#", "WEIGHT", "REPS") + if (showRest) listOf("REST") else emptyList()
    // The 402-unit panel has 12-unit text insets: 378 units of column space.
    val referenceWidths = if (showRest) listOf(48, 132, 84, 114) else listOf(48, 192, 138)
    val values = listOf(headings) + sets.mapIndexed { index, set ->
        listOf((index + 1).toString(), set.weight, set.repetitions) + if (showRest) listOf(set.rest) else emptyList()
    }
    return referenceWidths.mapIndexed { column, referenceWidth ->
        val measuredWidth = values.mapNotNull { it[column] }.maxOf { measurer.measure(it, style).size.width }
        val requiredUnits = with(density) { measuredWidth.toDp().value } / grid.scale + 12
        val snappedUnits = ceil(requiredUnits / 6).toInt() * 6
        grid.space(maxOf(referenceWidth, snappedUnits))
    }
}

/** Headings and values begin at shared column origins instead of independently centering strings. */
@Composable
internal fun HistorySetTable(
    sets: List<HistorySetRow>,
    rowTagPrefix: String,
    columnWidths: List<Dp>,
    modifier: Modifier = Modifier,
    showRest: Boolean = false,
) {
    val grid = LocalWorkoutGrid.current!!
    val headings = listOf("#", "WEIGHT", "REPS") + if (showRest) listOf("REST") else emptyList()
    val rows = sets.mapIndexed { index, set ->
        listOf((index + 1).toString(), set.weight, set.repetitions) + if (showRest) listOf(set.rest) else emptyList()
    }
    Column(modifier.fillMaxWidth().padding(horizontal = grid.space(12))) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(headings) + rows).forEachIndexed { index, values ->
                val heading = index == 0
                Row(
                    Modifier.width(columnWidths.fold(0.dp) { total, width -> total + width })
                        .then(if (heading) Modifier else Modifier.testTag("$rowTagPrefix-${sets[index - 1].id}"))
                        .semantics(mergeDescendants = true) {},
                ) {
                    values.forEachIndexed { column, value ->
                        if (value == null) {
                            Spacer(Modifier.width(columnWidths[column]).height(grid.textLine))
                        } else {
                            WorkoutGridLabel(
                                value,
                                modifier = Modifier.width(columnWidths[column]),
                                color = if (heading) WorkoutMuted else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
                                horizontalPadding = 0,
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
