package com.springrs.plugin.codegen

import java.io.File

/**
 * Handles incremental code generation — avoids overwriting user-written code.
 *
 * Strategy per file type:
 * - **Entity files**: Use marker-based region replacement.
 *   Code between `// === spring-rs generated START ===` and `// === spring-rs generated END ===`
 *   is replaced; code outside the markers is preserved.
 * - **Other files (DTO/VO/Service/Route)**: Handled by [FileConflictDialog] (Skip/Overwrite/Backup).
 * - **mod.rs / prelude.rs**: Always smart-merged (append only, no overwrite).
 */
object IncrementalMerger {

    private const val MARKER_START = "// === spring-rs generated START ==="
    private const val MARKER_END = "// === spring-rs generated END ==="

    /**
     * Merge new generated content into an existing Entity file.
     *
     * If the existing file contains the START/END markers, only the region between
     * the markers is replaced. User code outside the markers is preserved.
     *
     * If the file has no markers (e.g., hand-written or from Phase 1), returns
     * the new content as-is (full replacement — the file conflict dialog handles this).
     *
     * @param existingContent Current file content (may contain markers)
     * @param newContent Newly generated content (always contains markers)
     * @return Merged content
     */
    fun mergeEntityContent(existingContent: String, newContent: String): String? {
        val existingStartIdx = existingContent.indexOf(MARKER_START)
        val existingEndIdx = existingContent.indexOf(MARKER_END)

        // No markers in existing file → can't do incremental merge
        if (existingStartIdx < 0 || existingEndIdx < 0 || existingEndIdx <= existingStartIdx) {
            return null
        }

        // Extract the new generated region (between markers in new content)
        val newStartIdx = newContent.indexOf(MARKER_START)
        val newEndIdx = newContent.indexOf(MARKER_END)
        if (newStartIdx < 0 || newEndIdx < 0) return null

        val newRegion = newContent.substring(newStartIdx, newEndIdx + MARKER_END.length)

        // Replace only the marked region in the existing file
        val before = existingContent.substring(0, existingStartIdx)
        val after = existingContent.substring(existingEndIdx + MARKER_END.length)

        return before + newRegion + after
    }

    /**
     * Write content to a file with incremental merge support for Entity files.
     *
     * @param file Target file
     * @param newContent Newly generated content
     * @param isEntityFile Whether this is an Entity file (uses marker-based merge)
     * @return `true` if the file was written (or merged), `false` if skipped
     */
    fun writeWithMerge(file: File, newContent: String, isEntityFile: Boolean): Boolean {
        if (!file.exists()) {
            file.writeText(newContent)
            return true
        }

        if (isEntityFile) {
            val existingContent = file.readText()
            val merged = mergeEntityContent(existingContent, newContent)
            if (merged != null) {
                file.writeText(merged)
                return true
            }
        }

        // Non-entity or no markers: let the caller handle via FileConflictDialog
        return false
    }
}
