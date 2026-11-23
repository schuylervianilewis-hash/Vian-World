package com.example.feature.sidebar

import kotlin.math.ceil

/** Pure grid geometry shared by both grid editors. Rectangles use half-open bounds. */
object GridEditorLayoutPolicy {
    data class Span(val cols: Int, val rows: Int)

    fun effectiveSpan(itemCols: Int, itemRows: Int, totalCols: Int,
        cellWidthDp: Float, cellHeightDp: Float,
        providerMinWidthDp: Int = 0, providerMinHeightDp: Int = 0): Span {
        val requestedCols = if (providerMinWidthDp > 0 && cellWidthDp > 0f)
            ceil(providerMinWidthDp / cellWidthDp).toInt() else 1
        val requestedRows = if (providerMinHeightDp > 0 && cellHeightDp > 0f)
            ceil(providerMinHeightDp / cellHeightDp).toInt() else 1
        return Span(minOf(totalCols.coerceAtLeast(1), maxOf(1, itemCols, requestedCols)),
            maxOf(1, itemRows, requestedRows))
    }

    fun canPlace(items: List<GridWidgetItem>, spans: List<Span>, movingIndex: Int,
        x: Int, y: Int, targetSpan: Span, totalCols: Int): Boolean {
        if (movingIndex !in items.indices || spans.size != items.size || totalCols < 1
            || x < 0 || y < 0 || targetSpan.cols < 1 || targetSpan.rows < 1
            || x + targetSpan.cols > totalCols) return false
        for (index in items.indices) {
            if (index == movingIndex) continue
            val other = items[index]
            val span = spans[index]
            if (x < other.x + span.cols && x + targetSpan.cols > other.x
                && y < other.y + span.rows && y + targetSpan.rows > other.y) return false
        }
        return true
    }

    fun canFitColumns(items: List<GridWidgetItem>, spans: List<Span>, columns: Int): Boolean {
        if (columns < 1 || spans.size != items.size) return false
        return items.indices.all { items[it].x >= 0 && items[it].x + spans[it].cols <= columns }
    }
}
