package com.springrs.plugin.codegen

import com.springrs.plugin.utils.RustTypeUtils

/**
 * Table metadata model.
 */
data class TableInfo(
    val name: String,
    val comment: String?,
    val columns: List<ColumnInfo>,
    val primaryKeys: List<String>,
    /** Table name prefix to strip (e.g., "t_"). Empty = no stripping. */
    val tableNamePrefix: String = "",
    /** Column name prefix to strip (e.g., "f_"). Empty = no stripping. */
    val columnNamePrefix: String = "",
    /** PostgreSQL schema name (e.g., "public", "app"). Empty = default schema. */
    val schemaName: String = ""
) {
    /** Whether this table is in a non-default schema that needs a subdirectory. */
    val hasNonDefaultSchema: Boolean get() = schemaName.isNotEmpty() && schemaName != "public"

    /** Schema subdirectory (e.g., "app" or "" for public). */
    val schemaSubDir: String get() = if (hasNonDefaultSchema) schemaName else ""

    /** Table name after prefix stripping: `t_user` → `user` */
    val strippedName: String get() {
        if (tableNamePrefix.isEmpty()) return name
        return if (name.startsWith(tableNamePrefix, ignoreCase = true))
            name.removePrefix(tableNamePrefix) else name
    }

    /** PascalCase entity name (from stripped name) */
    val entityName: String get() = strippedName.toPascalCase()

    /** snake_case module name (from stripped name) */
    val moduleName: String get() = strippedName.toSnakeCase()

    /** Service class name */
    val serviceName: String get() = "${entityName}Service"

    /** Create DTO class name */
    val dtoCreateName: String get() = "Create${entityName}Dto"

    /** Update DTO class name */
    val dtoUpdateName: String get() = "Update${entityName}Dto"

    /** VO class name */
    val voName: String get() = "${entityName}Vo"

    /** Query DTO class name */
    val queryName: String get() = "Query${entityName}Dto"

    /** Queryable columns (non-PK, non-timestamp — used for search/filter) */
    val queryColumns: List<ColumnInfo>
        get() = columns.filter {
            !it.isPrimaryKey && !it.isAutoIncrement && it.name !in EXCLUDED_INSERT_COLUMNS
        }

    /** Primary key column (first one if composite) */
    val primaryKeyColumn: ColumnInfo? get() = columns.firstOrNull { it.isPrimaryKey }

    /** Primary key Rust type */
    val primaryKeyType: String get() = primaryKeyColumn?.rustType ?: "i32"

    /** Primary key column name */
    val primaryKeyName: String get() = primaryKeyColumn?.name ?: "id"

    /** Insertable columns (exclude auto-increment PK, created_at, etc.) */
    val insertColumns: List<ColumnInfo>
        get() = columns.filter {
            !it.isAutoIncrement && it.name !in EXCLUDED_INSERT_COLUMNS
        }

    /** Updatable columns (exclude PK, created_at, updated_at) */
    val updateColumns: List<ColumnInfo>
        get() = columns.filter {
            !it.isPrimaryKey && it.name !in EXCLUDED_UPDATE_COLUMNS
        }

    /**
     * Apply per-table override: filter excluded columns, apply type/name overrides,
     * merge ext properties, and append virtual columns.
     * Returns a new [TableInfo] with overrides applied.
     */
    fun applyOverride(override: TableOverrideConfig?): TableInfo {
        if (override == null) return this

        // 1. Filter excluded columns + apply type overrides + inject ext properties
        val filteredColumns = columns
            .filter { override.isColumnIncluded(it.name) }
            .map { col ->
                col.copy(
                    rustType = override.getEffectiveRustType(col.name, col.rustType),
                    comment = override.getEffectiveComment(col.name, col.comment),
                    ext = override.getColumnExt(col.name)
                )
            }

        // 2. Append virtual/custom columns
        val virtualColumns = override.customColumns.map { it.toColumnInfo() }

        return copy(
            columns = filteredColumns + virtualColumns
        )
    }

    companion object {
        val EXCLUDED_INSERT_COLUMNS = setOf("created_at", "updated_at", "create_time", "update_time")
        val EXCLUDED_UPDATE_COLUMNS = setOf("id", "created_at", "create_time")
    }
}

/**
 * Column metadata model.
 */
data class ColumnInfo(
    val name: String,
    val sqlType: String,
    val rustType: String,
    val isPrimaryKey: Boolean,
    val isNullable: Boolean,
    val isAutoIncrement: Boolean,
    val comment: String?,
    val defaultValue: String?,
    /** Whether this column has a single-column UNIQUE constraint (excluding PK). */
    val isUnique: Boolean = false,
    /** Custom ext properties accessible in templates as `$column.ext.key`. */
    val ext: Map<String, String> = emptyMap(),
    /** Whether this is a virtual/custom column (not in the database). */
    val isVirtual: Boolean = false
) {
    /** Rust field name (snake_case, handles Rust keywords) */
    val fieldName: String
        get() {
            val snake = name.toSnakeCase()
            return if (RustTypeUtils.isKeyword(snake)) "r#$snake" else snake
        }

    /** Full Rust type including Option wrapper for nullable columns */
    val fullRustType: String
        get() = if (isNullable) "Option<$rustType>" else rustType
}

// ── Naming Utilities ──

/**
 * Convert "user_accounts" or "userAccounts" to "UserAccounts".
 */
fun String.toPascalCase(): String {
    return this.replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .split("_", "-")
        .filter { it.isNotEmpty() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
}

/**
 * Convert "UserAccounts" or "user-accounts" to "user_accounts".
 */
fun String.toSnakeCase(): String {
    return this.replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .lowercase()
        .replace("-", "_")
}
