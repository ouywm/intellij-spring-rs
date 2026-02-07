package com.springrs.plugin.codegen.dialect

/**
 * SQLite dialect.
 *
 * SQLite uses type affinity (5 storage classes: NULL, INTEGER, REAL, TEXT, BLOB).
 * Column type names are flexible. We map common patterns to Rust types.
 *
 * Key characteristics:
 * - `INTEGER` is always 64-bit → `i64`
 * - `REAL` is always 64-bit → `f64`
 * - `timestamp`/`datetime` stored as TEXT → `DateTime`
 */
object SqliteDialect : DatabaseDialect {

    override val type = DatabaseType.SQLITE

    /** SQLite-specific types merged with [DatabaseDialect.COMMON_TYPES]. */
    private val TYPE_MAP: Map<String, String> = DatabaseDialect.COMMON_TYPES + mapOf(
        // SQLite INTEGER is 64-bit
        "integer" to "i64",

        // SQLite aliases
        "int2" to "i16", "int8" to "i64",
        "tinyint" to "i8", "mediumint" to "i32",
        "nvarchar" to "String", "nchar" to "String", "clob" to "String",

        // SQLite float (always 64-bit)
        "float" to "f64",

        // SQLite date/time (stored as TEXT)
        "datetime" to "DateTime",
        "timestamp" to "DateTime",
    )

    override fun toRustType(sqlType: String): String =
        resolveFromMap(sqlType.trim().lowercase(), TYPE_MAP)
}
