package com.springrs.plugin.codegen

import com.intellij.database.model.DasTable
import com.intellij.database.util.DasUtil
import com.intellij.openapi.diagnostic.logger

/**
 * Type of relationship between two tables.
 */
enum class RelationType {
    HAS_MANY,
    HAS_ONE,
    BELONGS_TO
}

/**
 * A detected relation between two tables.
 *
 * Computed properties (variantName, entity paths) are built in
 * [VelocityTemplateEngine.toContextMap] with table-prefix stripping applied.
 */
data class RelationInfo(
    val relationType: RelationType,
    val targetTable: String,
    val fromColumn: String,
    val toColumn: String
)

/**
 * Detects foreign key relationships between tables using IntelliJ Database API.
 *
 * ## Cross-version compatibility
 *
 * `DasForeignKey.getRefTableName()` and `getRefColumnsRef()` are not available
 * in all IntelliJ platform versions (233/241/251). We use reflection with
 * fallback strategies to support all target platforms:
 *
 * - **Primary**: Reflective call to `getRefTableName()` / `getRefColumnsRef()`
 * - **Fallback**: Infer referenced table from FK constraint naming conventions
 * - **Default**: Assume `"id"` as the referenced column (PK convention)
 */
object RelationDetector {

    private val LOG = logger<RelationDetector>()

    /**
     * Detect all relations for a set of tables.
     *
     * Returns a map: table name (lowercase) → list of [RelationInfo].
     */
    fun detectRelations(tables: List<DasTable>): Map<String, List<RelationInfo>> {
        val tableByName = tables.associateBy { it.name.lowercase() }
        val result = mutableMapOf<String, MutableList<RelationInfo>>()

        for (table in tables) {
            try {
                processTableForeignKeys(table, tableByName, result)
            } catch (ex: Exception) {
                LOG.debug("Failed to detect relations for table: ${table.name}", ex)
            }
        }

        return result
    }

    /**
     * Process all FK constraints for a single table, populating both forward
     * (BELONGS_TO) and reverse (HAS_MANY / HAS_ONE) relations.
     */
    private fun processTableForeignKeys(
        table: DasTable,
        tableByName: Map<String, DasTable>,
        result: MutableMap<String, MutableList<RelationInfo>>
    ) {
        val tableName = table.name.lowercase()
        val uniqueColumns = collectUniqueColumns(table)

        for (fk in DasUtil.getForeignKeys(table)) {
            try {
                val srcCol = fk.columnsRef.names().firstOrNull() ?: continue
                val refTableName = resolveRefTableName(fk, tableName, tableByName.keys) ?: continue
                if (refTableName !in tableByName) continue
                val tgtCol = resolveRefColumnName(fk)

                val isUnique = srcCol.lowercase() in uniqueColumns

                // Source belongs_to target
                result.getOrPut(tableName) { mutableListOf() }
                    .add(RelationInfo(RelationType.BELONGS_TO, refTableName, srcCol, tgtCol))

                // Target has_many/has_one source
                val reverseType = if (isUnique) RelationType.HAS_ONE else RelationType.HAS_MANY
                result.getOrPut(refTableName) { mutableListOf() }
                    .add(RelationInfo(reverseType, tableName, tgtCol, srcCol))
            } catch (ex: Exception) {
                LOG.debug("Failed to process FK constraint: ${fk.name}", ex)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Reflective FK property accessors (cross-version compat)
    // ══════════════════════════════════════════════════════════════

    /**
     * Resolve the referenced table name from a FK constraint.
     *
     * Strategy:
     * 1. Reflective call to `getRefTableName()` (available in newer API versions)
     * 2. Fallback: infer from FK constraint naming convention (e.g. `fk_posts_user_id`)
     */
    private fun resolveRefTableName(fk: Any, sourceName: String, knownTables: Set<String>): String? {
        return reflectiveGetString(fk, "getRefTableName")?.lowercase()
            ?: inferRefTableFromFkName(fk as? com.intellij.database.model.DasObject, sourceName, knownTables)
    }

    /**
     * Resolve the referenced column name from a FK constraint.
     *
     * Strategy:
     * 1. Reflective call to `getRefColumnsRef().names()` (newer API)
     * 2. Default: `"id"` (standard PK convention)
     */
    private fun resolveRefColumnName(fk: Any): String {
        try {
            val refCols = fk.javaClass.getMethod("getRefColumnsRef").invoke(fk) ?: return "id"
            val names = refCols.javaClass.getMethod("names").invoke(refCols)
            @Suppress("UNCHECKED_CAST")
            return (names as? Iterable<String>)?.firstOrNull() ?: "id"
        } catch (_: Exception) {
            return "id"
        }
    }

    /** Invoke a no-arg method by name and return its String result, or null on failure. */
    private fun reflectiveGetString(obj: Any, methodName: String): String? {
        return try {
            obj.javaClass.getMethod(methodName).invoke(obj) as? String
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Fallback heuristics
    // ══════════════════════════════════════════════════════════════

    /**
     * Try to infer referenced table name from FK constraint name.
     * Common patterns: `fk_posts_user_id`, `posts_user_id_fkey`
     */
    private fun inferRefTableFromFkName(
        fk: com.intellij.database.model.DasObject?, sourceName: String, knownTables: Set<String>
    ): String? {
        val fkName = fk?.name ?: return null
        val lower = fkName.lowercase()
        return knownTables.firstOrNull { it != sourceName && lower.contains(it) }
    }

    /** Collect all columns that participate in a unique index for the given table. */
    private fun collectUniqueColumns(table: DasTable): Set<String> {
        val uniqueColumns = mutableSetOf<String>()
        try {
            for (index in DasUtil.getIndices(table)) {
                if (index.isUnique) {
                    index.columnsRef.names().forEach { uniqueColumns.add(it.lowercase()) }
                }
            }
        } catch (_: Exception) { /* ignore */ }
        return uniqueColumns
    }

    /**
     * Merge auto-detected relations with user-defined custom relations.
     *
     * Dedup rule: same (targetTable, fromColumn, toColumn) keeps only one; custom wins.
     */
    fun mergeRelations(
        detected: Map<String, List<RelationInfo>>,
        custom: Map<String, List<RelationInfo>>
    ): Map<String, List<RelationInfo>> {
        val merged = mutableMapOf<String, List<RelationInfo>>()
        val allKeys = (detected.keys + custom.keys).distinct()
        for (key in allKeys) {
            val customRels = custom[key].orEmpty()
            val detectedRels = detected[key].orEmpty()
            val customKeys = customRels.map {
                Triple(it.targetTable.lowercase(), it.fromColumn, it.toColumn)
            }.toSet()
            val filtered = detectedRels.filter {
                Triple(it.targetTable.lowercase(), it.fromColumn, it.toColumn) !in customKeys
            }
            merged[key] = customRels + filtered
        }
        return merged
    }
}
