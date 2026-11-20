package com.dragosfotache.gymtracka.ui.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.theme.GymAccent
import com.dragosfotache.gymtracka.ui.theme.GymBackground
import com.dragosfotache.gymtracka.ui.theme.GymBorder
import com.dragosfotache.gymtracka.ui.theme.GymSelectedTab
import com.dragosfotache.gymtracka.ui.theme.GymSurface
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle

private enum class CatalogueFilter(val label: String) { Equipment("Equipment"), Muscles("Muscles") }

@Composable
internal fun CatalogueDesignScreen(
    state: ExerciseCatalogueState,
    listState: LazyListState,
    modifier: Modifier,
    onCreate: () -> Unit,
    onExerciseSelected: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        Column(modifier.fillMaxSize().padding(top = grid.space(24))) {
            Row(
                Modifier.fillMaxWidth().background(GymSurface).padding(horizontal = grid.space(23)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkoutGridLabel("Exercises", Modifier.weight(1f), fontWeight = FontWeight.Bold, horizontalPadding = 0)
                Box(
                    Modifier.background(GymAccent).clickable(role = Role.Button, onClick = onCreate),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Create",
                        Modifier.heightIn(min = grid.line).padding(horizontal = grid.space(18))
                            .wrapContentHeight(Alignment.CenterVertically),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.height(grid.space(24)))
            ExerciseCatalogueContent(
                summaries = state.summaries,
                filteredExercises = state.filteredExercises,
                searchQuery = state.searchQuery,
                onSearchQueryChanged = state::updateSearchQuery,
                selectedEquipmentIds = state.selectedEquipmentIds,
                onEquipmentIdsChanged = state::updateEquipmentIds,
                selectedMuscleGroupIds = state.selectedMuscleGroupIds,
                onMuscleGroupIdsChanged = state::updateMuscleGroupIds,
                onExerciseSelected = onExerciseSelected,
                onEdit = onEdit,
                onDelete = { state.deleteCustomExercise(it) },
                listState = listState,
                bottomSpacing = grid.space(24),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Shared catalogue browser; the caller owns navigation, applied filters and exercise selection. */
@Composable
internal fun ExerciseCatalogueContent(
    summaries: List<ExerciseSummary>?,
    filteredExercises: List<ExerciseSummary>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    selectedEquipmentIds: Set<String>,
    onEquipmentIdsChanged: (Set<String>) -> Unit,
    selectedMuscleGroupIds: Set<String>,
    onMuscleGroupIdsChanged: (Set<String>) -> Unit,
    onExerciseSelected: (String) -> Unit,
    listState: LazyListState,
    bottomSpacing: Dp,
    modifier: Modifier = Modifier,
    selectedExerciseIds: Set<String>? = null,
    singleSelection: Boolean = false,
    onEdit: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    val grid = LocalWorkoutGrid.current!!
    var expanded by remember { mutableStateOf<CatalogueFilter?>(null) }
    var draft by remember { mutableStateOf(emptySet<String>()) }
    BackHandler(expanded != null) { expanded = null }
    Column(modifier) {
        Column(Modifier.padding(horizontal = grid.space(23))) {
            CatalogueSearch(searchQuery, onSearchQueryChanged)
            Spacer(Modifier.height(grid.space(12)))
            CatalogueFilterControls(
                expanded = expanded,
                equipmentCount = selectedEquipmentIds.size,
                muscleCount = selectedMuscleGroupIds.size,
                onToggle = { filter ->
                    if (expanded == filter) {
                        expanded = null
                    } else {
                        draft = when (filter) {
                            CatalogueFilter.Equipment -> selectedEquipmentIds
                            CatalogueFilter.Muscles -> selectedMuscleGroupIds
                        }
                        expanded = filter
                    }
                },
            )
        }
        Spacer(Modifier.height(grid.space(12)))
        Box(Modifier.weight(1f)) {
            if (summaries == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = grid.space(23),
                        end = grid.space(23),
                        bottom = bottomSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(grid.space(24)),
                ) {
                    items(filteredExercises, key = { it.exercise.id }) { summary ->
                        CataloguePanel(
                            summary = summary,
                            onOpen = { onExerciseSelected(summary.exercise.id) },
                            selected = selectedExerciseIds?.contains(summary.exercise.id),
                            singleSelection = singleSelection,
                            onEdit = onEdit?.let { { it(summary.exercise.id) } },
                            onDelete = onDelete?.let { { it(summary.exercise.id) } },
                        )
                    }
                    if (filteredExercises.isEmpty()) item {
                        WorkoutGridLabel("No exercises found", Modifier.fillMaxWidth(), color = WorkoutMuted)
                    }
                }
            }
            expanded?.let { filter ->
                val options = when (filter) {
                    CatalogueFilter.Equipment -> summaries.orEmpty().flatMap { it.equipment }.map { it.id to it.name }
                    CatalogueFilter.Muscles -> summaries.orEmpty().flatMap { it.muscleGroups }.map { it.id to it.name }
                }.distinctBy { it.first }.sortedBy { it.second }
                key(filter) {
                    CatalogueFilterPanel(
                        options, draft, { draft = it },
                        onApply = {
                            when (filter) {
                                CatalogueFilter.Equipment -> onEquipmentIdsChanged(draft)
                                CatalogueFilter.Muscles -> onMuscleGroupIdsChanged(draft)
                            }
                            expanded = null
                        },
                        modifier = Modifier.padding(horizontal = grid.space(23)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogueFilterControls(
    expanded: CatalogueFilter?,
    equipmentCount: Int,
    muscleCount: Int,
    onToggle: (CatalogueFilter) -> Unit,
) {
    val grid = LocalWorkoutGrid.current!!
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val focus = LocalFocusManager.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The reference reserves 12 units before the label and a 48-unit disclosure slot.
        // At enlarged text sizes, stack full-width controls instead of clipping category names.
        val labelWidth = with(density) { measurer.measure("[Equipment]", style).size.width.toDp() }
        val stack = (maxWidth - grid.space(6)) / 2 < labelWidth + grid.space(60)
        val button: @Composable (CatalogueFilter, Modifier) -> Unit = { filter, modifier ->
            val count = if (filter == CatalogueFilter.Equipment) equipmentCount else muscleCount
            CatalogueFilterButton(
                filter = filter,
                expanded = expanded == filter,
                selectedCount = count,
                modifier = modifier,
                onClick = {
                    focus.clearFocus()
                    onToggle(filter)
                },
            )
        }
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(grid.space(6))) {
                CatalogueFilter.entries.forEach { button(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(grid.space(6))) {
                CatalogueFilter.entries.forEach { button(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CatalogueFilterButton(
    filter: CatalogueFilter,
    expanded: Boolean,
    selectedCount: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val grid = LocalWorkoutGrid.current!!
    Row(
        modifier.background(if (selectedCount > 0) GymSelectedTab else GymSurface)
            .border(grid.space(2), if (selectedCount > 0) GymAccent else GymBorder)
            .testTag(if (filter == CatalogueFilter.Equipment) "filter-equipment" else "filter-muscles")
            .semantics {
                stateDescription = "${if (expanded) "Expanded" else "Collapsed"}, $selectedCount selected"
            }
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No trailing label padding: it already comes from the chevron's disclosure slot.
        Text(
            "[${filter.label}]",
            Modifier.weight(1f).heightIn(min = grid.line)
                .padding(start = grid.space(12)).wrapContentHeight(Alignment.CenterVertically),
            color = GymAccent,
        )
        Box(Modifier.size(grid.line), contentAlignment = Alignment.Center) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = GymAccent,
                modifier = Modifier.size(grid.space(24)),
            )
        }
    }
}

@Composable
private fun CatalogueSearch(query: String, onQueryChanged: (String) -> Unit) {
    val grid = LocalWorkoutGrid.current!!
    Row(
        Modifier.fillMaxWidth().heightIn(min = grid.line).background(GymSurface)
            .border(grid.space(2), GymBorder).padding(horizontal = grid.space(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = GymAccent, modifier = Modifier.size(grid.space(24)))
        BasicTextField(
            query, onQueryChanged, singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(GymAccent),
            modifier = Modifier.weight(1f).padding(horizontal = grid.space(12), vertical = grid.space(12))
                .testTag("exercise-search").semantics { contentDescription = "Search exercises" },
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) Text("Search exercises", color = WorkoutMuted)
                    field()
                }
            },
        )
        if (query.isNotEmpty()) IconButton(onClick = { onQueryChanged("") }) {
            Icon(Icons.Default.Close, "Clear search", tint = GymAccent)
        }
    }
}

@Composable
private fun CatalogueFilterPanel(
    options: List<Pair<String, String>>,
    draft: Set<String>,
    onDraft: (Set<String>) -> Unit,
    onApply: () -> Unit,
    modifier: Modifier,
) {
    val grid = LocalWorkoutGrid.current!!
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    // Overlay the catalogue as in the reference; only the options scroll, keeping Show accessible.
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(max = grid.space(492))) {
        val longestLabel = options.maxOfOrNull { measurer.measure(it.second, style).size.width } ?: 0
        val optionWidth = (maxWidth - grid.space(30)) / 2
        val columns = if (optionWidth >= with(density) { longestLabel.toDp() } + grid.space(24)) 2 else 1
        // Surface also blocks taps in option gaps from reaching catalogue titles underneath.
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("catalogue-filter-panel"),
            color = GymSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RectangleShape,
            border = BorderStroke(grid.space(2), GymBorder),
        ) {
            Column(Modifier.padding(grid.space(12))) {
                LazyColumn(
                    Modifier.weight(1f, fill = false).testTag("catalogue-filter-options"),
                    verticalArrangement = Arrangement.spacedBy(grid.space(12)),
                ) {
                    items(options.chunked(columns), key = { it.first().first }) { row ->
                        CatalogueFilterOptionRow(row, columns, draft, onDraft)
                    }
                }
                Spacer(Modifier.height(grid.space(12)))
                Row(Modifier.fillMaxWidth()) {
                    WorkoutGridLabel(
                        "[Clear]", Modifier.clickable(role = Role.Button) { onDraft(emptySet()) },
                        color = GymAccent, horizontalPadding = 0,
                    )
                    Spacer(Modifier.weight(1f))
                    WorkoutGridLabel(
                        "[Show]", Modifier.clickable(role = Role.Button, onClick = onApply),
                        color = GymAccent, horizontalPadding = 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogueFilterOptionRow(
    options: List<Pair<String, String>>,
    columns: Int,
    draft: Set<String>,
    onDraft: (Set<String>) -> Unit,
) {
    val grid = LocalWorkoutGrid.current!!
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val textWidth = with(density) {
            ((maxWidth - grid.space(6) * (columns - 1)) / columns - grid.space(24)).roundToPx()
        }.coerceAtLeast(1)
        val lines = options.maxOf {
            measurer.measure(it.second, style, constraints = Constraints(maxWidth = textWidth)).lineCount
        }
        val rowHeight = maxOf(grid.line + grid.space(24), grid.textLine * lines + grid.space(48))
        // Explicit grid height avoids intrinsic measurement of BoxWithConstraints and native font-metric drift.
        Row(
            Modifier.height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(grid.space(6)),
        ) {
            options.forEach { (id, name) ->
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .background(if (id in draft) GymSelectedTab else GymBackground)
                        .border(grid.space(2), if (id in draft) GymAccent else GymBorder)
                        .testTag("filter-option-$id")
                        .toggleable(value = id in draft, role = Role.Checkbox) { checked ->
                            onDraft(if (checked) draft + id else draft - id)
                        }
                        .semantics { stateDescription = if (id in draft) "Selected" else "Not selected" },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(name, Modifier.padding(horizontal = grid.space(12)))
                }
            }
            if (options.size < columns) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CataloguePanel(
    summary: ExerciseSummary,
    onOpen: () -> Unit,
    selected: Boolean?,
    singleSelection: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val grid = LocalWorkoutGrid.current!!
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selectionModifier = when {
        selected == null -> Modifier
        singleSelection -> Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onOpen)
        else -> Modifier.toggleable(value = selected, role = Role.Checkbox) { onOpen() }
    }
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected == true) GymSelectedTab else GymBackground)
            .border(grid.space(2), if (selected == true) GymAccent else GymBorder)
            .testTag("exercise-row-${summary.exercise.id}")
            .then(selectionModifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkoutGridLabel(
                "[${summary.exercise.name}]",
                Modifier.weight(1f).then(
                    if (selected == null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier,
                ),
                color = GymAccent,
            )
            if (selected == true) Box(Modifier.size(grid.line), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = GymAccent, modifier = Modifier.size(grid.space(24)))
            }
            if (selected == null && summary.exercise.isUserCreated && onEdit != null && onDelete != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Exercise options", tint = GymAccent) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("[Edit]") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("[Delete]", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        WorkoutGridLabel(muscleGroupText(summary), Modifier.fillMaxWidth(), color = WorkoutMuted)
        WorkoutGridLabel(summary.equipment.joinToString { it.name }.ifEmpty { "None" }, Modifier.fillMaxWidth(), color = WorkoutMuted)
    }
    if (confirmDelete) GymDialog(
        onDismissRequest = { confirmDelete = false },
        title = "Delete exercise?",
        text = { Text("This will permanently delete ${summary.exercise.name}.") },
        confirmAction = GymDialogAction(
            text = "Delete",
            onClick = { confirmDelete = false; onDelete?.invoke() },
            destructive = true,
        ),
        dismissAction = GymDialogAction(
            text = "Cancel",
            onClick = { confirmDelete = false },
        ),
    )
}
