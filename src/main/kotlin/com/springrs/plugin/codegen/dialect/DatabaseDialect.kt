package com.springrs.plugin.codegen.dialect

import com.intellij.database.model.DasTable
import com.intellij.database.util.DasUtil
import com.springrs.plugin.utils.SpringRsConstants.TYPE_DATE_TIME
import com.springrs.plugin.utils.SpringRsConstants.TYPE_DATE_TIME_WITH_TZ

/**
 * Supported database types.
 */
enum class DatabaseType(val displayName: String) {
    POSTGRESQL("PostgreSQL"),
    MYSQL("MySQL"),
    SQLITE("SQLite"),
    UNKNOWN("Unknown");
}

/**
 * Strategy interface for database-specific behavior.
 *
 * Each database dialect provides its own:
 * - SQL → Rust type mapping (via [toRustType])
 * - Array/special type handling (via [supportsArrayTypes])
 *
 * Type mappings are aligned with `sea-orm-codegen` `Column::get_rs_type`.
 */
interface DatabaseDialect {

    val type: DatabaseType

    /** Map SQL type name to Rust type name. */
    fun toRustType(sqlType: String): String

    /** Whether this dialect supports array column types (e.g., PG `int4[]`). */
    fun supportsArrayTypes(): Boolean = false

    companion object {

        fun forType(type: DatabaseType): DatabaseDialect = when (type) {
            DatabaseType.POSTGRESQL -> PostgresDialect
            DatabaseType.MYSQL -> MySqlDialect
            DatabaseType.SQLITE -> SqliteDialect
            DatabaseType.UNKNOWN -> PostgresDialect
        }

        /** Auto-detect dialect from a DasTable's column types. */
        fun detect(table: DasTable): DatabaseDialect = forType(detectType(table))

        private fun detectType(table: DasTable): DatabaseType {
            val allTypes = DasUtil.getColumns(table)
                .map { it.dasType.typeClass.name.lowercase() }
                .toSet()

            return when {
                allTypes.any { it in PG_INDICATORS } -> DatabaseType.POSTGRESQL
                allTypes.any { it in MYSQL_INDICATORS } -> DatabaseType.MYSQL
                allTypes.any { it in SQLITE_INDICATORS } -> DatabaseType.SQLITE
                else -> DatabaseType.UNKNOWN
            }
        }

        private val PG_INDICATORS = setOf(
            "int4", "int8", "int2", "timestamptz", "serial", "bigserial",
            "smallserial", "bytea", "jsonb", "citext", "bpchar"
        )
        private val MYSQL_INDICATORS = setOf(
            "datetime", "tinyint", "mediumint", "mediumtext", "longtext",
            "tinyblob", "mediumblob", "longblob", "year"
        )
        private val SQLITE_INDICATORS = setOf("clob", "nvarchar", "nchar")

        // ══════════════════════════════════════════════════════════════
        // ── Common types shared by all databases (aligned with sea-orm-codegen)
        // ══════════════════════════════════════════════════════════════

        /**
         * Common SQL → Rust type mapping.
         *
         * Types that have the SAME semantics across PG / MySQL / SQLite.
         * **Excludes** `timestamp` (semantics differ between PG and MySQL!).
         *
         * Aligned with `sea-orm-codegen` `Column::get_rs_type`.
         */
        val COMMON_TYPES: Map<String, String> = mapOf(
            // Integer
            "smallint" to "i16",
            "integer" to "i32", "int" to "i32",
            "bigint" to "i64",

            // Float
            "real" to "f32",
            "double precision" to "f64", "double" to "f64",

            // Decimal
            "numeric" to "Decimal", "decimal" to "Decimal",

            // Boolean
            "bool" to "bool", "boolean" to "bool",

            // String
            "varchar" to "String", "character varying" to "String",
            "text" to "String", "char" to "String", "character" to "String",

            // Date / Time (non-ambiguous)
            "date" to "Date",
            "time" to "Time",

            // JSON
            "json" to "Json",

            // Binary
            "blob" to "Vec<u8>", "binary" to "Vec<u8>", "varbinary" to "Vec<u8>",
        )
    }
}

// ── Shared utilities ──

/** Compiled regex for stripping parameterized types like `varchar(255)`. */
private val PARAM_STRIP_REGEX = Regex("\\(.*\\)")

/**
 * Resolve a SQL type from a type map.
 * Handles: parameterized types `varchar(255)` → `varchar`, unknown → `String`.
 */
fun resolveFromMap(sqlType: String, typeMap: Map<String, String>): String {
    val normalized = sqlType.trim().lowercase()
    typeMap[normalized]?.let { return it }

    val baseName = PARAM_STRIP_REGEX.replace(normalized, "").trim()
    typeMap[baseName]?.let { return it }

    return "String"
}

/**
 * Resolve the `chrono` now-expression for `before_save` timestamp auto-fill.
 *
 * Database-agnostic: only depends on the column's Rust type (already correctly set by dialect).
 */
fun resolveTimestampNowExpr(rustType: String?): String = when (rustType) {
    TYPE_DATE_TIME -> "Utc::now().naive_utc()"
    TYPE_DATE_TIME_WITH_TZ -> "Utc::now().fixed_offset()"
    else -> "Utc::now().fixed_offset()"
}
