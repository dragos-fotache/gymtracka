package com.dragosfotache.gymtracka.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.abs
import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Check actual text placement, including character advances, rather than just its enclosing column. */
internal fun ComposeTestRule.assertHistoryColumnGrid(
    panelTag: String,
    rowTag: String,
    heading: String,
    value: String,
): Float {
    fun textLayout(text: String, ancestor: String): Pair<Rect, TextLayoutResult> {
        val node = onNode(hasText(text) and hasAnyAncestor(hasTestTag(ancestor)), useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return node.fetchSemanticsNode().boundsInRoot to results.single()
    }
    val panel = onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
    val scale = panel.width / 402f
    val horizontalStep = 6 * scale
    val verticalStep = 12 * scale
    val origin = panel.left + 12 * scale
    val (headingBounds, headingLayout) = textLayout(heading, panelTag)
    val (valueBounds, valueLayout) = textLayout(value, rowTag)
    val headingStart = headingBounds.left + headingLayout.getLineLeft(0)
    val valueStart = valueBounds.left + valueLayout.getLineLeft(0)
    assertEquals("$heading and $value must start in the same column", headingStart, valueStart, 1f)
    fun assertGridOffset(description: String, offset: Float, step: Float) {
        val expected = round(offset / step) * step
        assertTrue("$description must follow the grid: $offset, expected $expected", abs(offset - expected) <= 1.5f)
    }
    listOf(headingBounds to headingLayout, valueBounds to valueLayout).forEach { (bounds, layout) ->
        assertEquals("Table values must stay on one line", 1, layout.lineCount)
        layout.layoutInput.text.indices.forEach { index ->
            val characterStart = bounds.left + layout.getBoundingBox(index).left
            assertGridOffset("Character $index in ${layout.layoutInput.text}", characterStart - origin, horizontalStep)
        }
    }
    assertGridOffset(
        "$value baseline",
        valueBounds.top + valueLayout.firstBaseline - headingBounds.top - headingLayout.firstBaseline,
        verticalStep,
    )
    return headingStart
}

internal fun ComposeTestRule.assertHistoryActionGrid(actionTag: String, text: String) {
    val action = onNodeWithTag(actionTag).fetchSemanticsNode().boundsInRoot
    val scale = action.width / 402f
    val label = onNode(hasText(text) and hasAnyAncestor(hasTestTag(actionTag)), useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot
    val horizontalOffset = label.left - action.left
    val horizontalStep = 6 * scale
    assertEquals(
        "Action text must start on the same horizontal grid",
        round(horizontalOffset / horizontalStep) * horizontalStep, horizontalOffset, 1.5f,
    )
    val verticalStep = 12 * scale
    assertEquals(
        "Action height must follow the vertical grid",
        round(action.height / verticalStep) * verticalStep, action.height, 1.5f,
    )
}
