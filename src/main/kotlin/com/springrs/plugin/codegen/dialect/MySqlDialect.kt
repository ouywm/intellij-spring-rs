package com.springrs.plugin.codegen.dialect

/**
 * MySQL / MariaDB dialect.
 *
 * Key characteristics:
 * - `timestamp` = WITH timezone → `DateTimeWithTimeZone` (**opposite of PG!**)
 * - `datetime` = WITHOUT timezone → `DateTime`
 * - `tinyint(1)` is conventionally boolean
 * - `unsigned` integer variants → unsigned Rust types
 * - Has `mediumint`, `year`, `enum`, `set`, medium/long text/blob
 */
object MySqlDialect : DatabaseDialect {

    override val type = DatabaseType.MYSQL

    /** MySQL-specific types merged with [DatabaseDialect.COMMON_TYPES]. */
    private val TYPE_MAP: Map<String, String> = DatabaseDialect.COMMON_TYPES + mapOf(
        // MySQL integer extras
        "tinyint" to "i8",
        "mediumint" to "i32",
        "year" to "i16",

        // MySQL float (MySQL `float` is 4-byte, unlike PG where `float` = 8-byte)
        "float" to "f32",

        // MySQL string extras
        "tinytext" to "String", "mediumtext" to "String", "longtext" to "String",
        "enum" to "String", "set" to "String",

        // MySQL timestamp (WITH TZ — opposite of PG!)
        "timestamp" to "DateTimeWithTimeZone",
        "datetime" to "DateTime",

        // MySQL binary extras
        "tinyblob" to "Vec<u8>", "mediumblob" to "Vec<u8>", "longblob" to "Vec<u8>",
    )

    override fun toRustType(sqlType: String): String {
        val normalized = sqlType.trim().lowercase()

        // MySQL: tinyint(1) → bool
        if (normalized == "tinyint(1)") return "bool"

        // MySQL: unsigned integer variants
        if (normalized.contains("unsigned")) {
            val base = normalized.replace("unsigned", "").trim()
            return when {
                base.startsWith("bigint") -> "u64"
                base.startsWith("int") || base.startsWith("integer") -> "u32"
                base.startsWith("mediumint") -> "u32"
                base.startsWith("smallint") -> "u16"
                base.startsWith("tinyint") -> "u8"
                else -> resolveFromMap(base, TYPE_MAP)
            }
        }

        return resolveFromMap(normalized, TYPE_MAP)
    }
}
