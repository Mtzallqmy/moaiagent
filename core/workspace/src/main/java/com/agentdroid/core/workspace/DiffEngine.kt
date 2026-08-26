package com.agentdroid.core.workspace

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.ToolRegistryException
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.patch.DeltaType
import kotlinx.serialization.Serializable

@Serializable
enum class LineChangeType { ADDED, REMOVED, MODIFIED }

@Serializable
data class LineChange(
    val type: LineChangeType,
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val oldText: String? = null,
    val newText: String? = null
)

@Serializable
data class DiffResult(
    val unifiedDiff: String,
    val changes: List<LineChange>,
    val added: Int,
    val removed: Int,
    val modified: Int
)

class DiffEngine {
    fun diff(path: String, before: String, after: String, contextLines: Int = 3): DiffResult {
        val original = splitLines(before)
        val revised = splitLines(after)
        val patch = DiffUtils.diff(original, revised)
        val unified = UnifiedDiffUtils.generateUnifiedDiff(
            "a/$path",
            "b/$path",
            original,
            patch,
            contextLines.coerceIn(0, 20)
        ).joinToString("\n")
        val changes = buildList {
            patch.deltas.forEach { delta ->
                val oldStart = delta.source.position + 1
                val newStart = delta.target.position + 1
                val oldLines = delta.source.lines
                val newLines = delta.target.lines
                when (delta.type) {
                    DeltaType.INSERT -> newLines.forEachIndexed { index, text -> add(LineChange(LineChangeType.ADDED, newLine = newStart + index, newText = text)) }
                    DeltaType.DELETE -> oldLines.forEachIndexed { index, text -> add(LineChange(LineChangeType.REMOVED, oldLine = oldStart + index, oldText = text)) }
                    DeltaType.CHANGE -> {
                        val paired = maxOf(oldLines.size, newLines.size)
                        repeat(paired) { index ->
                            val old = oldLines.getOrNull(index)
                            val new = newLines.getOrNull(index)
                            when {
                                old != null && new != null -> add(LineChange(LineChangeType.MODIFIED, oldStart + index, newStart + index, old, new))
                                old != null -> add(LineChange(LineChangeType.REMOVED, oldStart + index, oldText = old))
                                new != null -> add(LineChange(LineChangeType.ADDED, newLine = newStart + index, newText = new))
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        return DiffResult(
            unifiedDiff = unified,
            changes = changes,
            added = changes.count { it.type == LineChangeType.ADDED },
            removed = changes.count { it.type == LineChangeType.REMOVED },
            modified = changes.count { it.type == LineChangeType.MODIFIED }
        )
    }

    fun applyUnifiedDiff(current: String, unifiedDiff: String): String {
        val original = splitLines(current)
        val patch = try {
            UnifiedDiffUtils.parseUnifiedDiff(unifiedDiff.lineSequence().toList())
        } catch (error: Throwable) {
            throw ToolRegistryException(AgentError.patchConflict("Invalid unified diff: ${error.message}"))
        }
        return try {
            DiffUtils.patch(original, patch).joinToString("\n")
        } catch (error: Throwable) {
            throw ToolRegistryException(AgentError.patchConflict("Unified diff does not match the current file: ${error.message}"))
        }
    }

    private fun splitLines(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        return value.split('\n')
    }
}
