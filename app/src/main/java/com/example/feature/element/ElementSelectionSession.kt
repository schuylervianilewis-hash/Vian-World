package com.example.feature.element

/**
 * Holds the ordered element IDs selected during one multi-add picker session.
 * The snapshot is detached so callers cannot mutate the active session.
 */
class ElementSelectionSession(initialIds: Collection<String> = emptyList()) {
    private val selectedIds = initialIds.toMutableList()

    val size: Int
        get() = selectedIds.size

    fun add(id: String): Int {
        require(id.isNotBlank()) { "Element ID cannot be blank" }
        selectedIds.add(id)
        return selectedIds.size
    }

    fun snapshot(): List<String> = selectedIds.toList()
}
