package io.github.marzouq.calc

import android.content.Context
import java.io.File

/** One completed calculation. */
data class HistoryEntry(
    val expression: String,
    val result: String,
    val timestamp: Long,
)

/**
 * Append-only calculation history, like the stock Android calculator's.
 *
 * Stored as a plain TSV file in the app's private [Context.getFilesDir] — no
 * database dependency, no permission, never leaves the device. Capped so the
 * file can't grow without bound.
 */
class History(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun add(expression: String, result: String) = synchronized(lock) {
        if (expression.isBlank() || result.isBlank()) return
        val line = buildString {
            append(System.currentTimeMillis())
            append('\t')
            append(expression.sanitize())
            append('\t')
            append(result.sanitize())
            append('\n')
        }
        file.appendText(line)
        trimIfNeeded()
    }

    /** Newest first. */
    fun all(): List<HistoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines().asReversed().mapNotNull { it.parse() }
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size > MAX_ENTRIES) {
            file.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
        }
    }

    private fun String.sanitize() = replace('\t', ' ').replace('\n', ' ')

    private fun String.parse(): HistoryEntry? {
        val parts = split('\t')
        if (parts.size != 3) return null
        val ts = parts[0].toLongOrNull() ?: return null
        return HistoryEntry(parts[1], parts[2], ts)
    }

    private companion object {
        const val FILE_NAME = "history.tsv"
        const val MAX_ENTRIES = 200
    }
}
