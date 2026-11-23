package com.example.feature.sidebar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridEditorLayoutPolicyTest {
    private fun item(id: String, x: Int, y: Int, cols: Int = 1, rows: Int = 1) =
        GridWidgetItem(id, cols, rows, x, y)

    @Test fun edgeAdjacencyIsValid() {
        val items = listOf(item("a", 0, 0, 2, 2), item("b", 2, 0))
        val spans = listOf(GridEditorLayoutPolicy.Span(2, 2), GridEditorLayoutPolicy.Span(1, 1))
        assertTrue(GridEditorLayoutPolicy.canPlace(items, spans, 1, 2, 0, spans[1], 4))
    }
    @Test fun partialAndFullOverlapAreRejected() {
        val items = listOf(item("a", 1, 1, 2, 2), item("b", 0, 0))
        val spans = listOf(GridEditorLayoutPolicy.Span(2, 2), GridEditorLayoutPolicy.Span(1, 1))
        assertFalse(GridEditorLayoutPolicy.canPlace(items, spans, 1, 2, 2,
            GridEditorLayoutPolicy.Span(2, 2), 4))
        assertFalse(GridEditorLayoutPolicy.canPlace(items, spans, 1, 1, 1, spans[1], 4))
    }
    @Test fun movingItemIsExcludedAndBoundsAreChecked() {
        val items = listOf(item("a", 1, 1, 2, 2))
        val spans = listOf(GridEditorLayoutPolicy.Span(2, 2))
        assertTrue(GridEditorLayoutPolicy.canPlace(items, spans, 0, 2, 3, spans[0], 4))
        assertFalse(GridEditorLayoutPolicy.canPlace(items, spans, 0, 3, 3, spans[0], 4))
    }
    @Test fun providerMinimumSpanIsHonoured() {
        val span = GridEditorLayoutPolicy.effectiveSpan(1, 1, 4, 60f, 60f, 121, 121)
        assertTrue(span.cols == 3 && span.rows == 3)
    }
    @Test fun columnReductionChecksEveryEffectiveItem() {
        val items = listOf(item("a", 1, 0, 2, 1), item("b", 0, 4))
        val spans = listOf(GridEditorLayoutPolicy.Span(2, 1), GridEditorLayoutPolicy.Span(1, 1))
        assertFalse(GridEditorLayoutPolicy.canFitColumns(items, spans, 2))
        assertTrue(GridEditorLayoutPolicy.canFitColumns(items, spans, 3))
    }
}
