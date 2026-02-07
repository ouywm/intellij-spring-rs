package com.springrs.plugin.codegen

import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
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
 */
data class RelationInfo(
    val relationType: RelationType,
    val targetTable: String,
    val fromColumn: String,
    val toColumn: String
) {
    val variantName: String
        get() {
            val base = targetTable.toPascalCase()
            return if (relationType == RelationType.HAS_MANY) "${base}s" else base
        }

    val targetEntityPath: String get() = "super::${targetTable.toSnakeCase()}::Entity"
    val fromColumnPath: String get() = "Column::${fromColumn.toPascalCase()}"
    val toColumnPath: String get() = "super::${targetTable.toSnakeCase()}::Column::${toColumn.toPascalCase()}"
}

/**
 * Detects foreign key relationships between tables using IntelliJ Database API.
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
                val tableName = table.name.lowercase()
                val uniqueColumns = collectUniqueColumns(table)

                // Iterate FK constraints
                for (fk in DasUtil.getForeignKeys(table)) {
                    try {
                        // Get FK column names
                        val srcColumns = fk.columnsRef.names().toList()
                        if (srcColumns.isEmpty()) continue
                        val srcCol = srcColumns.first()

                        // Get referenced table name from the FK's refTableName property
                        // DasForeignKey extends DasTableKey which has getRefTableName()
                        val refTableName = try {
                            val method = fk.javaClass.getMethod("getRefTableName")
                            (method.invoke(fk) as? String)?.lowercase()
                        } catch (_: Exception) {
                            // Fallback: try to infer from FK name convention: fk_<table>_<reftable>
                            inferRefTableFromFkName(fk.name, tableName, tableByName.keys)
                        } ?: continue

                        if (refTableName !in tableByName) continue

                        // Get referenced columns (usually the PK)
                        val tgtCol = try {
                            val method = fk.javaClass.getMethod("getRefColumnsRef")
                            val refCols = method.invoke(fk)
                            val namesMethod = refCols?.javaClass?.getMethod("names")
                            @Suppress("UNCHECKED_CAST")
                            (namesMethod?.invoke(refCols) as? Iterable<String>)?.firstOrNull() ?: "id"
                        } catch (_: Exception) {
                            "id" // Default to "id" as PK
                        }

                        val isUnique = srcCol.lowercase() in uniqueColumns

                        // Source belongs_to target
                        result.getOrPut(tableName) { mutableListOf() }.add(
                            RelationInfo(RelationType.BELONGS_TO, refTableName, srcCol, tgtCol)
                        )

                        // Target has_many/has_one source
                        val reverseType = if (isUnique) RelationType.HAS_ONE else RelationType.HAS_MANY
                        result.getOrPut(refTableName) { mutableListOf() }.add(
                            RelationInfo(reverseType, tableName, tgtCol, srcCol)
                        )
                    } catch (ex: Exception) {
                        LOG.debug("Failed to process FK constraint: ${fk.name}", ex)
                    }
                }
            } catch (ex: Exception) {
                LOG.debug("Failed to detect relations for table: ${table.name}", ex)
            }
        }

        return result
    }

    /**
     * Try to infer referenced table name from FK constraint name.
     * Common patterns: `fk_posts_user_id`, `posts_user_id_fkey`
     */
    private fun inferRefTableFromFkName(
        fkName: String?, sourceName: String, knownTables: Set<String>
    ): String? {
        if (fkName == null) return null
        val lower = fkName.lowercase()
        // Check if any known table name appears in the FK name
        return knownTables.firstOrNull { it != sourceName && lower.contains(it) }
    }

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
}
