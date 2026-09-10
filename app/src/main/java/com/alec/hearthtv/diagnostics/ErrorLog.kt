package com.alec.hearthtv.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime

data class ErrorEntry(val at: String, val source: String, val message: String)

/**
 * The last [capacity] failures, newest first, each with a local timestamp and the device it came from. Shown on
 * the Diagnostics screen and pasted into the Copy report — the owner's "what went wrong" for a house nobody can
 * visit to debug (SCOPE.md §4.2). Pinned by ErrorLogTest.
 */
class ErrorLog(
    private val capacity: Int = 20,
    private val clock: () -> String = { LocalDateTime.now().withNano(0).toString() },
) {
    private val _entries = MutableStateFlow<List<ErrorEntry>>(emptyList())
    val entries: StateFlow<List<ErrorEntry>> = _entries.asStateFlow()

    fun record(source: String, message: String) {
        _entries.update { (listOf(ErrorEntry(clock(), source, message)) + it).take(capacity) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** One line per entry for the Copy report; says so when there is nothing to report. */
    fun text(): String {
        val list = _entries.value
        if (list.isEmpty()) return "no errors recorded"
        return list.joinToString("\n") { "${it.at}  ${it.source}: ${it.message}" }
    }
}
