package com.springrs.plugin.codegen

/**
 * Per-table override configuration.
 *
 * Allows customizing generated output for individual tables:
 * - Custom entity name (overrides prefix-stripping / auto-infer)
 * - Excluded columns (not generated in Entity/DTO/VO)
 * - Custom column → Rust type mappings
 * - Per-table layer switches
 *
 * Stored in [CodeGenSettingsState.tableOverrides], keyed by original table name.
 * Must be a JavaBean (mutable + no-arg constructor) for XML serialization.
 */
class TableOverrideConfig() {

    /** Custom entity name. Null = auto-infer from table name. */
    var customEntityName: String? = null

    /** Column names to exclude from generation. */
    var excludedColumns: MutableSet<String> = mutableSetOf()

    /** Custom column type overrides: column name → Rust type. */
    var columnTypeOverrides: MutableMap<String, String> = mutableMapOf()

    /** Custom column rename: column name → custom Rust field name. */
    var columnNameOverrides: MutableMap<String, String> = mutableMapOf()

    /** Per-table layer switches (null = follow global setting). */
    var generateEntity: Boolean? = null
    var generateDto: Boolean? = null
    var generateVo: Boolean? = null
    var generateService: Boolean? = null
    var generateRoute: Boolean? = null

    constructor(tableName: String) : this() {
        // Convenience constructor; tableName is the map key, not stored here.
    }

    /** Whether a column should be included in generation. */
    fun isColumnIncluded(columnName: String): Boolean = columnName !in excludedColumns

    /** Get effective Rust type for a column (custom override or original). */
    fun getEffectiveRustType(columnName: String, originalType: String): String =
        columnTypeOverrides[columnName] ?: originalType

    /** Get effective Rust field name for a column (custom override or original). */
    fun getEffectiveFieldName(columnName: String, originalFieldName: String): String =
        columnNameOverrides[columnName] ?: originalFieldName
}
