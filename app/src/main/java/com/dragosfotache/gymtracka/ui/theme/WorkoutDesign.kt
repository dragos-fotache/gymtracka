package com.dragosfotache.gymtracka.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragosfotache.gymtracka.R
import kotlin.math.ceil
import kotlin.math.max

private val WorkoutFont = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_extrabold, FontWeight.ExtraBold),
)

/** The workout references share a 6 by 12 grid within a 448-wide screen. */
@Immutable
internal data class WorkoutGrid(val scale: Float = 1f, val fontScale: Float = 1f) {
    fun space(pixels: Int): Dp = (pixels * scale).dp

    // Keep the 48/24 rhythm at normal text size; grow by grid steps for accessibility.
    val line: Dp get() = space(max(48, ceil((24 * fontScale + 24) / 12).toInt() * 12))
    val textLine: Dp get() = space(ceil(24 * fontScale / 12).toInt() * 12)
    val input: Dp get() = maxOf(space(36), textLine)
}

internal val LocalWorkoutGrid = staticCompositionLocalOf<WorkoutGrid?> { null }

@Composable
internal fun WorkoutScreenStyle(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val grid = WorkoutGrid(maxWidth.value / 448f, LocalDensity.current.fontScale)
        val typography = remember(grid.scale) {
            val style = TextStyle(
                fontFamily = WorkoutFont,
                fontWeight = FontWeight.Normal,
                fontSize = (20 * grid.scale).sp,
                lineHeight = (24 * grid.scale).sp,
                letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
            Typography(
                displayLarge = style,
                displayMedium = style,
                displaySmall = style,
                headlineLarge = style,
                headlineMedium = style,
                headlineSmall = style,
                titleLarge = style,
                titleMedium = style,
                titleSmall = style,
                bodyLarge = style,
                bodyMedium = style,
                bodySmall = style,
                labelLarge = style,
                labelMedium = style,
                labelSmall = style,
            )
        }
        CompositionLocalProvider(LocalWorkoutGrid provides grid) {
            MaterialTheme(colorScheme = GymDarkColorScheme, typography = typography, content = content)
        }
    }
}

@Composable
internal fun WorkoutActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    WorkoutGridLabel(
        text = text,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.onPrimary,
        fontWeight = fontWeight,
        contentAlignment = Alignment.Center,
        horizontalPadding = 0,
    )
}

/** Measure wrapping first so native font metrics cannot push rows off the 12-unit grid. */
@Composable
internal fun WorkoutGridLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.Normal,
    contentAlignment: Alignment = Alignment.CenterStart,
    horizontalPadding: Int = 12,
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontWeight = fontWeight)
    BoxWithConstraints(modifier, contentAlignment = contentAlignment) {
        val padding = grid.space(horizontalPadding)
        val textWidth = (constraints.maxWidth - with(density) { (padding * 2).roundToPx() }).coerceAtLeast(1)
        val lines = measurer.measure(text, style, constraints = Constraints(maxWidth = textWidth)).lineCount
        val rowHeight = maxOf(grid.line, grid.textLine * lines + grid.space(24))
        Text(
            text,
            Modifier.height(rowHeight).padding(horizontal = padding).wrapContentHeight(Alignment.CenterVertically),
            color = color,
            style = style,
            textAlign = if (contentAlignment == Alignment.Center) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
internal fun WorkoutSeparator() {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    Box(Modifier.fillMaxWidth().height(grid.space(24))) {
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(grid.space(1))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

internal val WorkoutPlaceholder = Color(0xFF434F81)
internal val WorkoutMuted = Color(0xFF878787)
internal val WorkoutCancel = Color(0xFF903F3F)
