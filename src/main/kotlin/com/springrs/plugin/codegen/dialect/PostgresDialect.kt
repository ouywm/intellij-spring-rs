package com.springrs.plugin.codegen.dialect

/**
 * PostgreSQL dialect.
 *
 * Key characteristics:
 * - `timestamp` = WITHOUT timezone → `DateTime`
 * - `timestamptz` = WITH timezone → `DateTimeWithTimeZone`
 * - Supports array types: `int4[]`, `_int4`
 * - Has `serial`/`bigserial` for auto-increment
 * - Has `jsonb`, `citext`, `inet`, geometry types, `oid`
 */
object PostgresDialect : DatabaseDialect {

    override val type = DatabaseType.POSTGRESQL
    override fun supportsArrayTypes() = true

    /** PG-specific types merged with [DatabaseDialect.COMMON_TYPES]. */
    private val TYPE_MAP: Map<String, String> = DatabaseDialect.COMMON_TYPES + mapOf(
        // PG internal integer names
        "int2" to "i16", "smallserial" to "i16",
        "int4" to "i32", "serial" to "i32", "serial4" to "i32",
        "int8" to "i64", "bigserial" to "i64", "serial8" to "i64",

        // PG float aliases
        "float4" to "f32",
        "float8" to "f64", "float" to "f64",

        // PG money
        "money" to "Decimal",

        // PG string extras
        "citext" to "String", "name" to "String", "bpchar" to "String",

        // PG timestamp (WITHOUT TZ by default)
        "timestamp" to "DateTime",
        "timestamp without time zone" to "DateTime",
        "timestamptz" to "DateTimeWithTimeZone",
        "timestamp with time zone" to "DateTimeWithTimeZone",
        "time without time zone" to "Time",
        "timetz" to "Time", "time with time zone" to "Time",

        // PG specific types
        "uuid" to "Uuid",
        "jsonb" to "Json",
        "bytea" to "Vec<u8>",
        "inet" to "String", "macaddr" to "String", "macaddr8" to "String",
        "bit" to "String", "varbit" to "String", "bit varying" to "String",
        "xml" to "String", "interval" to "String",
        "point" to "String", "line" to "String", "lseg" to "String",
        "box" to "String", "path" to "String", "polygon" to "String",
        "circle" to "String", "tsvector" to "String", "tsquery" to "String",
        "oid" to "u32",
    )

    override fun toRustType(sqlType: String): String {
        val normalized = sqlType.trim().lowercase()

        // PG array: "int4[]"
        if (normalized.endsWith("[]")) {
            return "Vec<${resolveFromMap(normalized.removeSuffix("[]"), TYPE_MAP)}>"
        }
        // PG internal array: "_int4"
        if (normalized.startsWith("_")) {
            return "Vec<${resolveFromMap(normalized.removePrefix("_"), TYPE_MAP)}>"
        }

        return resolveFromMap(normalized, TYPE_MAP)
    }
}
