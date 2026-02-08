package com.springrs.plugin.codegen

/**
 * Per-table override configuration.
 *
 * Allows customizing generated output for individual tables:
 * - Custom entity name (overrides prefix-stripping / auto-infer)
 * - Excluded columns (not generated in Entity/DTO/VO)
 * - Custom column → Rust type mappings
 * - Per-table layer switches
 * - **Custom (virtual) columns** not in the database
 * - **Ext properties** per column (accessible as `$column.ext.key` in templates)
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

    /** Custom column comment overrides: column name → comment text. */
    var columnCommentOverrides: MutableMap<String, String> = mutableMapOf()

    /** Custom column rename: column name → custom Rust field name. */
    var columnNameOverrides: MutableMap<String, String> = mutableMapOf()

    /** Per-table layer switches (null = follow global setting). */
    var generateEntity: Boolean? = null
    var generateDto: Boolean? = null
    var generateVo: Boolean? = null
    var generateService: Boolean? = null
    var generateRoute: Boolean? = null

    /**
     * Virtual/custom columns not in the database.
     *
     * These columns are appended to the real columns during code generation.
     * Use case: adding computed fields like `is_admin`, `full_name`, etc.
     */
    var customColumns: MutableList<CustomColumnConfig> = mutableListOf()

    /**
     * Per-column ext properties: column name → map of ext key → value.
     *
     * Accessible in Velocity templates as `$column.ext.myKey`.
     * Use case: adding custom metadata like validation rules, UI hints, etc.
     */
    var columnExtProperties: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    // ── Per-table output directory overrides (null = follow global default) ──
    var entityOutputDir: String? = null
    var dtoOutputDir: String? = null
    var voOutputDir: String? = null
    var serviceOutputDir: String? = null
    var routeOutputDir: String? = null

    // ── Per-table extra derives overrides (null = follow global default) ──
    var entityExtraDerives: MutableSet<String>? = null
    var dtoExtraDerives: MutableSet<String>? = null
    var voExtraDerives: MutableSet<String>? = null

    /** User-defined logical foreign key relations. */
    var customRelations: MutableList<CustomRelationConfig> = mutableListOf()

    /** Whether a column should be included in generation. */
    fun isColumnIncluded(columnName: String): Boolean = columnName !in excludedColumns

    /** Get effective Rust type for a column (custom override or original). */
    fun getEffectiveRustType(columnName: String, originalType: String): String =
        columnTypeOverrides[columnName] ?: originalType

    /** Get effective Rust field name for a column (custom override or original). */
    fun getEffectiveFieldName(columnName: String, originalFieldName: String): String =
        columnNameOverrides[columnName] ?: originalFieldName

    /** Get effective comment for a column (custom override or original). */
    fun getEffectiveComment(columnName: String, originalComment: String?): String? =
        columnCommentOverrides[columnName] ?: originalComment

    /** Get ext properties for a column (empty map if none). */
    fun getColumnExt(columnName: String): Map<String, String> =
        columnExtProperties[columnName] ?: emptyMap()

    /** Set ext properties for a column. */
    fun setColumnExt(columnName: String, ext: Map<String, String>) {
        if (ext.isEmpty()) {
            columnExtProperties.remove(columnName)
        } else {
            columnExtProperties[columnName] = ext.toMutableMap()
        }
    }
}

/**
 * Configuration for a virtual/custom column.
 *
 * Must be a JavaBean for XML serialization.
 */
class CustomColumnConfig() {

    var name: String = ""
    var rustType: String = "String"
    var sqlType: String = "VIRTUAL"
    var isNullable: Boolean = false
    var defaultValue: String = ""
    var comment: String = ""

    constructor(name: String, rustType: String, comment: String = "") : this() {
        this.name = name
        this.rustType = rustType
        this.comment = comment
    }

    /** Convert to [ColumnInfo] for code generation. */
    fun toColumnInfo(): ColumnInfo = ColumnInfo(
        name = name,
        sqlType = sqlType,
        rustType = rustType,
        isPrimaryKey = false,
        isNullable = isNullable,
        isAutoIncrement = false,
        comment = comment.ifEmpty { null },
        defaultValue = defaultValue.ifEmpty { null },
        isVirtual = true
    )
}

/**
 * User-defined logical foreign key relation.
 *
 * JavaBean format for XML serialization.
 */
class CustomRelationConfig() {
    var relationType: String = "BELONGS_TO"  // HAS_MANY / HAS_ONE / BELONGS_TO
    var targetTable: String = ""
    var fromColumn: String = ""
    var toColumn: String = "id"

    constructor(relationType: String, targetTable: String, fromColumn: String, toColumn: String) : this() {
        this.relationType = relationType
        this.targetTable = targetTable
        this.fromColumn = fromColumn
        this.toColumn = toColumn
    }

    /** Convert to [RelationInfo] for code generation. */
    fun toRelationInfo(): RelationInfo = RelationInfo(
        relationType = RelationType.valueOf(relationType),
        targetTable = targetTable,
        fromColumn = fromColumn,
        toColumn = toColumn
    )
}
