package com.springrs.plugin.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * JSON-to-struct history persistence service.
 *
 * Stores recently used JSON inputs for quick reuse.
 */
@State(
    name = "JsonToStructHistory",
    storages = [Storage("springrs-json-to-struct-history.xml")]
)
class JsonToStructHistoryService : PersistentStateComponent<JsonToStructHistoryService.State> {

    companion object {
        private const val MAX_HISTORY_SIZE = 10

        fun getInstance(): JsonToStructHistoryService {
            return ApplicationManager.getApplication().service()
        }
    }

    data class HistoryItem(
        var json: String = "",
        var preview: String = "",  // Preview text for UI display.
        var timestamp: Long = 0
    )

    class State {
        var historyItems: MutableList<HistoryItem> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * Get history items.
     */
    fun getHistory(): List<HistoryItem> {
        return myState.historyItems.sortedByDescending { it.timestamp }
    }

    /**
     * Add JSON to history.
     */
    fun addToHistory(json: String) {
        val trimmedJson = json.trim()
        if (trimmedJson.isEmpty()) return

        // Generate preview text.
        val preview = generatePreview(trimmedJson)

        // Remove duplicates.
        myState.historyItems.removeIf { it.json == trimmedJson }

        // Add newest item to the front.
        myState.historyItems.add(0, HistoryItem(
            json = trimmedJson,
            preview = preview,
            timestamp = System.currentTimeMillis()
        ))

        // Cap history size.
        while (myState.historyItems.size > MAX_HISTORY_SIZE) {
            myState.historyItems.removeAt(myState.historyItems.size - 1)
        }
    }

    /**
     * Clear history.
     */
    fun clearHistory() {
        myState.historyItems.clear()
    }

    /**
     * Generate preview text.
     */
    private fun generatePreview(json: String): String {
        // Remove newlines and extra whitespace.
        val singleLine = json.replace(Regex("\\s+"), " ").trim()

        // Truncate to 60 characters.
        return if (singleLine.length > 60) {
            singleLine.substring(0, 57) + "..."
        } else {
            singleLine
        }
    }
}
