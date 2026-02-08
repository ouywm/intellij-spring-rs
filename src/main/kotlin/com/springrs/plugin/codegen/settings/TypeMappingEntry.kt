package com.springrs.plugin.codegen.settings

import com.springrs.plugin.codegen.dialect.DatabaseDialect
import com.springrs.plugin.codegen.dialect.DatabaseType

/**
 * Match strategy for a type mapping rule.
 */
enum class MatchType(val displayName: String) {
    EXACT("Exact"),
    REGEX("Regex");

    companion object {
        fun fromString(s: String): MatchType = entries.find { it.name == s } ?: REGEX
    }
}

/**
 * A single type mapping entry: columnType pattern → Rust type.
 *
 * - [columnType]: SQL column type pattern (plain string or regex)
 * - [rustType]: Target Rust type (e.g., "String", "i32", "DateTime")
 * - [matchType]: How to match the columnType — exact string match or regex
 *
 * Supports XML serialization for IntelliJ PersistentStateComponent.
 */
class TypeMappingEntry() {

    var columnType: String = ""
    var rustType: String = "String"
    var matchType: String = MatchType.REGEX.name

    constructor(columnType: String, rustType: String, matchType: MatchType = MatchType.REGEX) : this() {
        this.columnType = columnType
        this.rustType = rustType
        this.matchType = matchType.name
    }

    /** Check if this entry matches a given SQL type. */
    fun matches(sqlType: String): Boolean {
        val normalized = sqlType.trim().lowercase()
        return when (MatchType.fromString(matchType)) {
            MatchType.EXACT -> normalized == columnType.trim().lowercase()
            MatchType.REGEX -> {
                try {
                    Regex(columnType, RegexOption.IGNORE_CASE).matches(normalized)
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    fun copy(): TypeMappingEntry = TypeMappingEntry(columnType, rustType, MatchType.fromString(matchType))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TypeMappingEntry) return false
        return columnType == other.columnType && rustType == other.rustType && matchType == other.matchType
    }

    override fun hashCode(): Int {
        var result = columnType.hashCode()
        result = 31 * result + rustType.hashCode()
        result = 31 * result + matchType.hashCode()
        return result
    }

    companion object {

        /**
         * Generate default type mapping entries for a given database dialect.
         * These are derived from the existing [DatabaseDialect] implementations.
         */
        fun defaultsForDialect(dbType: DatabaseType): List<TypeMappingEntry> = when (dbType) {
            DatabaseType.POSTGRESQL -> postgresDefaults()
            DatabaseType.MYSQL -> mysqlDefaults()
            DatabaseType.SQLITE -> sqliteDefaults()
            DatabaseType.UNKNOWN -> postgresDefaults()
        }

        private fun postgresDefaults(): List<TypeMappingEntry> = listOf(
            // String types (regex)
            TypeMappingEntry("varchar(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("character varying(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("char(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("character(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("text", "String", MatchType.EXACT),
            TypeMappingEntry("citext", "String", MatchType.EXACT),
            TypeMappingEntry("name", "String", MatchType.EXACT),
            TypeMappingEntry("bpchar", "String", MatchType.EXACT),

            // Integer types
            TypeMappingEntry("int2", "i16", MatchType.EXACT),
            TypeMappingEntry("smallint", "i16", MatchType.EXACT),
            TypeMappingEntry("smallserial", "i16", MatchType.EXACT),
            TypeMappingEntry("int4", "i32", MatchType.EXACT),
            TypeMappingEntry("integer", "i32", MatchType.EXACT),
            TypeMappingEntry("int", "i32", MatchType.EXACT),
            TypeMappingEntry("serial", "i32", MatchType.EXACT),
            TypeMappingEntry("serial4", "i32", MatchType.EXACT),
            TypeMappingEntry("int8", "i64", MatchType.EXACT),
            TypeMappingEntry("bigint", "i64", MatchType.EXACT),
            TypeMappingEntry("bigserial", "i64", MatchType.EXACT),
            TypeMappingEntry("serial8", "i64", MatchType.EXACT),

            // Float types
            TypeMappingEntry("float4", "f32", MatchType.EXACT),
            TypeMappingEntry("real", "f32", MatchType.EXACT),
            TypeMappingEntry("float8", "f64", MatchType.EXACT),
            TypeMappingEntry("float", "f64", MatchType.EXACT),
            TypeMappingEntry("double precision", "f64", MatchType.EXACT),

            // Decimal (regex for precision)
            TypeMappingEntry("numeric(\\(\\d+(,\\d+)?\\))?", "Decimal", MatchType.REGEX),
            TypeMappingEntry("decimal(\\(\\d+(,\\d+)?\\))?", "Decimal", MatchType.REGEX),
            TypeMappingEntry("money", "Decimal", MatchType.EXACT),

            // Boolean
            TypeMappingEntry("bool", "bool", MatchType.EXACT),
            TypeMappingEntry("boolean", "bool", MatchType.EXACT),

            // Date / Time
            TypeMappingEntry("date", "Date", MatchType.EXACT),
            TypeMappingEntry("time", "Time", MatchType.EXACT),
            TypeMappingEntry("time without time zone", "Time", MatchType.EXACT),
            TypeMappingEntry("timetz", "Time", MatchType.EXACT),
            TypeMappingEntry("time with time zone", "Time", MatchType.EXACT),
            TypeMappingEntry("timestamp", "DateTime", MatchType.EXACT),
            TypeMappingEntry("timestamp without time zone", "DateTime", MatchType.EXACT),
            TypeMappingEntry("timestamptz", "DateTimeWithTimeZone", MatchType.EXACT),
            TypeMappingEntry("timestamp with time zone", "DateTimeWithTimeZone", MatchType.EXACT),

            // Special PG types
            TypeMappingEntry("uuid", "Uuid", MatchType.EXACT),
            TypeMappingEntry("json", "Json", MatchType.EXACT),
            TypeMappingEntry("jsonb", "Json", MatchType.EXACT),
            TypeMappingEntry("bytea", "Vec<u8>", MatchType.EXACT),
            TypeMappingEntry("oid", "u32", MatchType.EXACT),

            // Network / Geo / Others → String
            TypeMappingEntry("inet", "String", MatchType.EXACT),
            TypeMappingEntry("macaddr", "String", MatchType.EXACT),
            TypeMappingEntry("xml", "String", MatchType.EXACT),
            TypeMappingEntry("interval", "String", MatchType.EXACT),
        )

        private fun mysqlDefaults(): List<TypeMappingEntry> = listOf(
            // String types (regex)
            TypeMappingEntry("varchar(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("char(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("text", "String", MatchType.EXACT),
            TypeMappingEntry("tinytext", "String", MatchType.EXACT),
            TypeMappingEntry("mediumtext", "String", MatchType.EXACT),
            TypeMappingEntry("longtext", "String", MatchType.EXACT),
            TypeMappingEntry("enum", "String", MatchType.EXACT),
            TypeMappingEntry("set", "String", MatchType.EXACT),

            // Integer types
            TypeMappingEntry("tinyint(1)", "bool", MatchType.EXACT),  // must be before tinyint
            TypeMappingEntry("tinyint(\\(\\d+\\))?", "i8", MatchType.REGEX),
            TypeMappingEntry("smallint(\\(\\d+\\))?", "i16", MatchType.REGEX),
            TypeMappingEntry("mediumint(\\(\\d+\\))?", "i32", MatchType.REGEX),
            TypeMappingEntry("int(\\(\\d+\\))?", "i32", MatchType.REGEX),
            TypeMappingEntry("integer(\\(\\d+\\))?", "i32", MatchType.REGEX),
            TypeMappingEntry("bigint(\\(\\d+\\))?", "i64", MatchType.REGEX),
            TypeMappingEntry("year", "i16", MatchType.EXACT),

            // Unsigned variants (regex)
            TypeMappingEntry("tinyint(\\(\\d+\\))?\\s*unsigned", "u8", MatchType.REGEX),
            TypeMappingEntry("smallint(\\(\\d+\\))?\\s*unsigned", "u16", MatchType.REGEX),
            TypeMappingEntry("(int|integer|mediumint)(\\(\\d+\\))?\\s*unsigned", "u32", MatchType.REGEX),
            TypeMappingEntry("bigint(\\(\\d+\\))?\\s*unsigned", "u64", MatchType.REGEX),

            // Float types
            TypeMappingEntry("float(\\(\\d+(,\\d+)?\\))?", "f32", MatchType.REGEX),
            TypeMappingEntry("double(\\(\\d+(,\\d+)?\\))?", "f64", MatchType.REGEX),
            TypeMappingEntry("real", "f32", MatchType.EXACT),
            TypeMappingEntry("double precision", "f64", MatchType.EXACT),

            // Decimal (regex)
            TypeMappingEntry("decimal(\\(\\d+(,\\d+)?\\))?", "Decimal", MatchType.REGEX),
            TypeMappingEntry("numeric(\\(\\d+(,\\d+)?\\))?", "Decimal", MatchType.REGEX),

            // Boolean
            TypeMappingEntry("bool", "bool", MatchType.EXACT),
            TypeMappingEntry("boolean", "bool", MatchType.EXACT),

            // Date / Time
            TypeMappingEntry("date", "Date", MatchType.EXACT),
            TypeMappingEntry("time", "Time", MatchType.EXACT),
            TypeMappingEntry("datetime", "DateTime", MatchType.EXACT),
            TypeMappingEntry("timestamp", "DateTimeWithTimeZone", MatchType.EXACT),

            // Binary
            TypeMappingEntry("blob", "Vec<u8>", MatchType.EXACT),
            TypeMappingEntry("tinyblob", "Vec<u8>", MatchType.EXACT),
            TypeMappingEntry("mediumblob", "Vec<u8>", MatchType.EXACT),
            TypeMappingEntry("longblob", "Vec<u8>", MatchType.EXACT),
            TypeMappingEntry("binary(\\(\\d+\\))?", "Vec<u8>", MatchType.REGEX),
            TypeMappingEntry("varbinary(\\(\\d+\\))?", "Vec<u8>", MatchType.REGEX),

            // JSON
            TypeMappingEntry("json", "Json", MatchType.EXACT),
        )

        private fun sqliteDefaults(): List<TypeMappingEntry> = listOf(
            // String types
            TypeMappingEntry("varchar(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("char(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("text", "String", MatchType.EXACT),
            TypeMappingEntry("nvarchar(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("nchar(\\(\\d+\\))?", "String", MatchType.REGEX),
            TypeMappingEntry("clob", "String", MatchType.EXACT),

            // Integer (SQLite INTEGER is always 64-bit)
            TypeMappingEntry("integer", "i64", MatchType.EXACT),
            TypeMappingEntry("int", "i32", MatchType.EXACT),
            TypeMappingEntry("int2", "i16", MatchType.EXACT),
            TypeMappingEntry("int8", "i64", MatchType.EXACT),
            TypeMappingEntry("tinyint", "i8", MatchType.EXACT),
            TypeMappingEntry("smallint", "i16", MatchType.EXACT),
            TypeMappingEntry("mediumint", "i32", MatchType.EXACT),
            TypeMappingEntry("bigint", "i64", MatchType.EXACT),

            // Float (SQLite REAL is always 64-bit)
            TypeMappingEntry("real", "f64", MatchType.EXACT),
            TypeMappingEntry("float", "f64", MatchType.EXACT),
            TypeMappingEntry("double", "f64", MatchType.EXACT),
            TypeMappingEntry("double precision", "f64", MatchType.EXACT),

            // Decimal
            TypeMappingEntry("numeric(\\(\\d+(,\\d+)?\\))?", "Decimal", MatchType.REGEX),
            TypeMappingEntry("decimal(\\(\\d+(,\\d+)?\\))?", "Decimal", MatchType.REGEX),

            // Boolean
            TypeMappingEntry("bool", "bool", MatchType.EXACT),
            TypeMappingEntry("boolean", "bool", MatchType.EXACT),

            // Date / Time
            TypeMappingEntry("date", "Date", MatchType.EXACT),
            TypeMappingEntry("time", "Time", MatchType.EXACT),
            TypeMappingEntry("datetime", "DateTime", MatchType.EXACT),
            TypeMappingEntry("timestamp", "DateTime", MatchType.EXACT),

            // Binary
            TypeMappingEntry("blob", "Vec<u8>", MatchType.EXACT),
        )

        /**
         * Resolve a SQL type using custom mapping entries.
         * Returns null if no custom mapping matches (caller should fall back to dialect).
         */
        fun resolveType(sqlType: String, entries: List<TypeMappingEntry>): String? {
            for (entry in entries) {
                if (entry.matches(sqlType)) {
                    return entry.rustType
                }
            }
            return null
        }
    }
}
