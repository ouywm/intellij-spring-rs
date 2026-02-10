package com.springrs.plugin.codegen

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.springrs.plugin.codegen.dialect.resolveTimestampNowExpr
import com.springrs.plugin.codegen.template.TemplateCallback
import com.springrs.plugin.codegen.template.TemplateTool
import com.springrs.plugin.utils.SpringRsConstants.TYPE_DATE
import com.springrs.plugin.utils.SpringRsConstants.TYPE_DATE_TIME
import com.springrs.plugin.utils.SpringRsConstants.TYPE_DATE_TIME_WITH_TZ
import com.springrs.plugin.utils.SpringRsConstants.TYPE_TIME
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import java.io.File
import java.io.StringWriter

/**
 * Velocity-based template rendering engine for Sea-ORM code generation.
 *
 * Template loading priority:
 * 1. Custom project templates: `{project}/.spring-rs/templates/{name}.rs.vm`
 * 2. Built-in templates: bundled in JAR `/codegen-templates/{name}.rs.vm`
 */
object VelocityTemplateEngine {

    private val LOG = logger<VelocityTemplateEngine>()

    private val engine: VelocityEngine by lazy {
        VelocityEngine().apply {
            setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")
            setProperty(RuntimeConstants.RUNTIME_LOG_NAME, "com.springrs.codegen.velocity")
            init()
        }
    }

    // ── Public API ──

    /**
     * Render a Velocity template string with the given context.
     *
     * Automatically injects:
     * - `$tool` — [TemplateTool] (string manipulation, collections, time)
     * - `$callback` — [TemplateCallback] (file name/path control)
     */
    fun render(templateContent: String, context: Map<String, Any?>): String =
        renderWithCallback(templateContent, context).first

    /**
     * Render a template and return both the content and the callback state.
     * Used by the Action to respect `$callback.setFileName()` etc.
     */
    fun renderWithCallback(templateContent: String, context: Map<String, Any?>): Pair<String, TemplateCallback> {
        val callback = TemplateCallback()
        val vc = VelocityContext()
        vc.put("tool", TemplateTool)
        vc.put("callback", callback)
        context.forEach { (key, value) -> if (value != null) vc.put(key, value) }

        val writer = StringWriter()
        engine.evaluate(vc, writer, "spring-rs-codegen", templateContent)
        return writer.toString() to callback
    }

    /**
     * High-level API: load template + build context + render in one call.
     *
     * Eliminates the repetitive load→build→render boilerplate in each generator.
     *
     * @param project         Current project (for settings & custom template resolution)
     * @param templateName    Template base name (e.g., "entity", "dto", "vo")
     * @param table           Table metadata
     * @param relations       FK relations (only needed by Entity layer)
     * @param contextCustomizer  Lambda to inject layer-specific context variables (derives, flags, etc.)
     */
    fun generateFromTemplate(
        project: Project,
        templateName: String,
        table: TableInfo,
        relations: List<RelationInfo> = emptyList(),
        contextCustomizer: (MutableMap<String, Any?>) -> Unit = {}
    ): String {
        val settings = CodeGenSettingsState.getInstance(project)
        val template = loadTemplate(project, templateName, settings.useCustomTemplate, settings.customTemplatePath)
        val context = buildTableContext(table, settings, relations)
        contextCustomizer(context)
        return render(template, context)
    }

    /**
     * Load a template by name, checking custom directory first, then built-in resources.
     *
     * @param templateName  Base name without extension (e.g., "entity", "dto")
     */
    fun loadTemplate(
        project: Project,
        templateName: String,
        useCustom: Boolean,
        customPath: String
    ): String {
        // 1. Custom project template
        if (useCustom) {
            val basePath = project.basePath
            if (basePath != null) {
                val customFile = File(basePath, "$customPath/$templateName.rs.vm")
                if (customFile.exists()) {
                    LOG.info("Loading custom template: ${customFile.absolutePath}")
                    return customFile.readText(Charsets.UTF_8)
                }
            }
        }

        // 2. Built-in template from JAR resources
        val resourcePath = "/codegen-templates/$templateName.rs.vm"
        return VelocityTemplateEngine::class.java.getResourceAsStream(resourcePath)
            ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
            ?: throw IllegalStateException("Template not found: $templateName.rs.vm")
    }

    // ── Context builders ──

    /**
     * Build the common Velocity context shared by all layers.
     */
    fun buildTableContext(
        table: TableInfo,
        settings: CodeGenSettingsState,
        relations: List<RelationInfo> = emptyList()
    ): MutableMap<String, Any?> {
        val columns = table.columns.map { it.toContextMap() }
        // Filter out #[sea_orm(ignore)] columns from DTO/Query layers.
        // Ignored columns exist in Model struct but NOT in ActiveModel or Column enum,
        // so they cannot be used in Set(), Column::Xxx, or IntoCondition.
        val insertColumns = table.insertColumns.filter { !isSeaOrmIgnored(it) }.map { it.toContextMap() }
        val updateColumns = table.updateColumns.filter { !isSeaOrmIgnored(it) }.map { it.toUpdateContextMap() }

        // ── Timestamp detection ──
        val hasCreatedAt = table.columns.any { it.name in CREATED_AT_COLUMNS }
        val hasUpdatedAt = table.columns.any { it.name in UPDATED_AT_COLUMNS }
        val createdAtField = table.columns.firstOrNull { it.name in CREATED_AT_COLUMNS }
        val updatedAtField = table.columns.firstOrNull { it.name in UPDATED_AT_COLUMNS }
        // rustType was already correctly set by the dialect during readTable, so this is DB-agnostic
        val timestampNowExpr = resolveTimestampNowExpr((createdAtField ?: updatedAtField)?.rustType)

        // Schema: non-public schemas need #[sea_orm(schema_name = "xxx")]
        val hasSchema = table.schemaName.isNotEmpty() && table.schemaName != "public"

        return mutableMapOf(
            // ── Global info ──
            "author" to System.getProperty("user.name", ""),
            "date" to TemplateTool.currDate(),
            "year" to TemplateTool.currYear(),

            // ── Schema ──
            "schemaName" to table.schemaName,
            "hasSchema" to hasSchema,

            // ── Table names ──
            "tableName" to table.name,
            "tableComment" to (table.comment ?: ""),
            "entityName" to table.entityName,
            "moduleName" to table.moduleName,
            "serviceName" to table.serviceName,
            "dtoCreateName" to table.dtoCreateName,
            "dtoUpdateName" to table.dtoUpdateName,
            "voName" to table.voName,
            "queryName" to table.queryName,

            // ── Columns ──
            "columns" to columns,
            "insertColumns" to insertColumns,
            "updateColumns" to updateColumns,
            "queryColumns" to table.queryColumns.filter { !isSeaOrmIgnored(it) }.map { it.toContextMap() },

            // ── Primary key ──
            "primaryKeyType" to table.primaryKeyType,
            "primaryKeyName" to table.primaryKeyName,
            "isCompositePrimaryKey" to (table.primaryKeys.size > 1),
            "compositePkType" to if (table.primaryKeys.size > 1) {
                val pkCols = table.columns.filter { it.isPrimaryKey }
                "(${pkCols.joinToString(", ") { it.rustType }})"
            } else table.primaryKeyType,
            "compositePkFields" to if (table.primaryKeys.size > 1) {
                table.columns.filter { it.isPrimaryKey }.map { mapOf("fieldName" to it.fieldName, "rustType" to it.rustType) }
            } else emptyList<Any>(),

            // ── Timestamps ──
            "hasTimestamps" to (hasCreatedAt || hasUpdatedAt),
            "hasCreatedAt" to hasCreatedAt,
            "hasUpdatedAt" to hasUpdatedAt,
            "createdAtFieldName" to (createdAtField?.fieldName ?: ""),
            "updatedAtFieldName" to (updatedAtField?.fieldName ?: ""),
            "timestampNowExpr" to timestampNowExpr,

            // ── Routing ──
            "routePrefix" to settings.routePrefix,

            // ── Module paths (for cross-layer imports) ──
            // Non-default schemas add a subdirectory: crate::entity → crate::entity::app
            "entityModulePath" to CodegenPlan.dirToCratePath(settings.entityOutputDir, table.schemaSubDir),
            "dtoModulePath" to CodegenPlan.dirToCratePath(settings.dtoOutputDir, table.schemaSubDir),
            "voModulePath" to CodegenPlan.dirToCratePath(settings.voOutputDir, table.schemaSubDir),
            "serviceModulePath" to CodegenPlan.dirToCratePath(settings.serviceOutputDir, table.schemaSubDir),

            // ── Relations (from ForeignKeyDetector) ──
            // Relations: strip table prefix from target paths (e.g., t_role → role)
            "relations" to relations.map { it.toContextMap(table.tableNamePrefix) },
            "hasRelations" to relations.isNotEmpty(),
            "belongsToRelations" to relations.filter { it.relationType == RelationType.BELONGS_TO }.map { it.toContextMap(table.tableNamePrefix) },
            "hasManyRelations" to relations.filter { it.relationType == RelationType.HAS_MANY }.map { it.toContextMap(table.tableNamePrefix) },
            "hasOneRelations" to relations.filter { it.relationType == RelationType.HAS_ONE }.map { it.toContextMap(table.tableNamePrefix) }
        )
    }

    // ── Helpers ──

    /**
     * Convert [ColumnInfo] to a map for Velocity access.
     *
     * Keys match the design-doc variable names: `$column.isPrimaryKey`, etc.
     */
    private fun ColumnInfo.toContextMap(): Map<String, Any?> {
        val seaOrmColType = resolveSeaOrmColumnType(sqlType, rustType)
        val normalizedSqlBase = PARAM_STRIP_REGEX.replace(sqlType.trim().lowercase(), "").trim()
        val isCustomPgType = normalizedSqlBase in PG_CUSTOM_STRING_TYPES

        // Build precomputed sea_orm attribute string for the entity template
        val seaOrmAttr = buildSeaOrmAttr(
            isPrimaryKey = isPrimaryKey,
            isAutoIncrement = isAutoIncrement,
            columnType = seaOrmColType,
            isCustomPgType = isCustomPgType,
            rawSqlBaseType = normalizedSqlBase,
            isUnique = isUnique,
            isNullable = isNullable
        )

        return mapOf(
            "name" to name,
            "fieldName" to fieldName,
            "rustType" to rustType,
            "fullRustType" to fullRustType,
            "sqlType" to sqlType,
            "isPrimaryKey" to isPrimaryKey,
            "isNullable" to isNullable,
            "isAutoIncrement" to isAutoIncrement,
            "isUnique" to isUnique,
            "isDateTimeType" to (rustType in DATE_TIME_TYPES),
            "isUuidType" to (rustType == "Uuid"),
            "isVoConvertType" to (rustType in VO_STRING_CONVERT_TYPES),
            "isVoVecConvertType" to (rustType.startsWith("Vec<") &&
                rustType.removePrefix("Vec<").removeSuffix(">") in VO_STRING_CONVERT_TYPES),
            "isStringType" to (rustType == "String"),
            "isVirtual" to isVirtual,
            "isIgnored" to isCustomPgType,
            "comment" to (comment ?: ""),
            "defaultValue" to (defaultValue ?: ""),
            "voRustType" to voRustType(),
            // Precomputed sea_orm attribute (e.g., "primary_key, column_type = \"JsonBinary\", nullable")
            "seaOrmAttr" to (seaOrmAttr ?: ""),
            "hasSeaOrmAttr" to (seaOrmAttr != null),
            // Keep legacy variables for backward compatibility with custom templates
            "seaOrmColumnType" to (seaOrmColType ?: ""),
            "hasSeaOrmColumnType" to (seaOrmColType != null),
            // Ext properties: accessible as $column.ext.myKey in templates
            "ext" to ext
        )
    }

    /**
     * Extended context for update columns: includes `isEntityNullable` flag
     * so the service template can decide between `Set(val)` and `Set(Some(val))`.
     */
    private fun ColumnInfo.toUpdateContextMap(): Map<String, Any?> {
        val base = toContextMap().toMutableMap()
        base["isEntityNullable"] = isNullable
        return base
    }

    /**
     * Convert [RelationInfo] to Velocity-accessible map.
     *
     * @param tablePrefix table name prefix to strip (e.g., "t_") so that
     *        relation paths use module names (e.g., `super::role::Entity` not `super::t_role::Entity`).
     */
    private fun RelationInfo.toContextMap(tablePrefix: String = ""): Map<String, Any> {
        val strippedTarget = stripPrefix(targetTable, tablePrefix)
        val strippedModuleName = strippedTarget.toSnakeCase()
        return mapOf(
            "relationType" to relationType.name,
            "isBelongsTo" to (relationType == RelationType.BELONGS_TO),
            "isHasMany" to (relationType == RelationType.HAS_MANY),
            "isHasOne" to (relationType == RelationType.HAS_ONE),
            "targetTable" to strippedTarget,
            "targetModuleName" to strippedModuleName,
            "fromColumn" to fromColumn,
            "toColumn" to toColumn,
            "variantName" to strippedTarget.toPascalCase().let {
                if (relationType == RelationType.HAS_MANY) "${it}s" else it
            },
            "targetEntityPath" to "super::${strippedModuleName}::Entity",
            "fromColumnPath" to "Column::${fromColumn.toPascalCase()}",
            "toColumnPath" to "super::${strippedModuleName}::Column::${toColumn.toPascalCase()}"
        )
    }

    /** Strip table name prefix (case-insensitive). */
    private fun stripPrefix(name: String, prefix: String): String {
        if (prefix.isEmpty()) return name
        return if (name.startsWith(prefix, ignoreCase = true)) name.substring(prefix.length) else name
    }

    /** DateTime types that are converted to String in VO layer. */
    private val DATE_TIME_TYPES = setOf(TYPE_DATE_TIME, TYPE_DATE_TIME_WITH_TZ, TYPE_DATE, TYPE_TIME)

    /** Types that are converted to String in VO layer (for JsonSchema compatibility). */
    private val VO_STRING_CONVERT_TYPES = DATE_TIME_TYPES + setOf("Uuid", "Decimal", "Json")

    /**
     * Resolve the `#[sea_orm(column_type = "...")]` annotation value.
     *
     * Returns **null** for most types — sea-orm can infer the correct type from Rust type alone.
     * Only returns a value when Rust type → SQL type mapping is **ambiguous**.
     *
     * Aligned with `sea-orm-codegen 2.0` output:
     * - `jsonb` → `"JsonBinary"`
     * - `text` → `"Text"` (sea-orm defaults `String` to `varchar`)
     * - `decimal(p,s)` / `numeric(p,s)` → `"Decimal(Some((p, s)))"`
     * - `citext` / `inet` / `macaddr` etc. → `"custom(\"xxx\")"` (with escaped inner quotes)
     *
     * Reference: sea-orm DeriveEntityModel macro + sea-orm-codegen get_col_type_attrs()
     */
    private fun resolveSeaOrmColumnType(sqlType: String, rustType: String): String? {
        val normalized = sqlType.trim().lowercase()
        val baseType = PARAM_STRIP_REGEX.replace(normalized, "").trim()

        return when {
            // PG jsonb → must annotate as JsonBinary (json is auto-inferred, jsonb is not)
            baseType == "jsonb" -> "JsonBinary"

            // TEXT → annotate as "Text" (sea-orm defaults String to varchar, but DB column is text)
            baseType == "text" -> "Text"

            // Float (real/float4) → annotate as "Float" (sea-orm needs explicit Float annotation)
            baseType in FLOAT_TYPES -> "Float"

            // Double (double precision/float8) → annotate as "Double"
            baseType in DOUBLE_TYPES -> "Double"

            // bytea → annotate as VarBinary (sea-orm maps Vec<u8> but needs explicit column_type)
            baseType == "bytea" -> "VarBinary(StringLen::None)"

            // Decimal/Numeric with precision → Decimal(Some((p, s)))
            baseType in DECIMAL_TYPES -> {
                val match = DECIMAL_PRECISION_REGEX.find(normalized)
                if (match != null) {
                    "Decimal(Some((${match.groupValues[1]}, ${match.groupValues[2]})))"
                } else null // no precision → let sea-orm infer
            }

            // PG citext → maps to String but DB needs custom column type
            baseType == "citext" -> "custom(\\\"citext\\\")"

            // PG network/geo/misc types → map to String but DB column is a distinct type.
            // Sea-ORM would send varchar which is incompatible. Need custom() annotation.
            // Escaped inner quotes: custom(\"inet\") → valid Rust string literal containing custom("inet")
            baseType in PG_CUSTOM_STRING_TYPES -> "custom(\\\"$baseType\\\")"

            // PG array types: handled by sea-orm `with-postgres-array` feature + Vec<T>.
            // No column_type annotation needed.
            normalized.endsWith("[]") || normalized.startsWith("_") -> null

            // All other types: sea-orm infers from Rust type, no annotation needed
            else -> null
        }
    }

    /**
     * Build the complete `#[sea_orm(...)]` attribute content for a column.
     *
     * Returns null if no attribute is needed (plain columns without special annotations).
     *
     * Combines all sea-orm attribute parts in the standard order:
     * `ignore, primary_key, auto_increment = false, column_type = "...", select_as = "...", unique, nullable`
     *
     * Aligned with `sea-orm-cli 2.0` output format.
     */
    private fun buildSeaOrmAttr(
        isPrimaryKey: Boolean,
        isAutoIncrement: Boolean,
        columnType: String?,
        isCustomPgType: Boolean,
        rawSqlBaseType: String = "",
        isUnique: Boolean,
        isNullable: Boolean
    ): String? {
        val parts = mutableListOf<String>()

        // Custom PG types use `ignore` — excluded from standard sea-orm CRUD operations.
        // This matches sea-orm-codegen 2.0 behavior. The column is still present in the struct
        // but sea-orm won't include it in auto-generated SELECT/INSERT/UPDATE statements.
        if (isCustomPgType && rawSqlBaseType.isNotEmpty()) {
            parts.add("ignore")
        }

        if (isPrimaryKey) {
            parts.add("primary_key")
            if (!isAutoIncrement) parts.add("auto_increment = false")
        }

        if (columnType != null) {
            parts.add("column_type = \"$columnType\"")
        }

        // Custom PG types: select_as = "text" → CAST(column AS text) in raw SELECT queries.
        // No save_as — matches sea-orm-codegen 2.0 (these types are read-only via ORM).
        if (isCustomPgType && rawSqlBaseType.isNotEmpty()) {
            parts.add("select_as = \"text\"")
        }

        if (isUnique && !isPrimaryKey) {
            parts.add("unique")
        }

        // Nullable annotation is required when column_type is explicitly set,
        // so sea-orm correctly generates Option<T> mapping
        if (isNullable && columnType != null) {
            parts.add("nullable")
        }

        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /** Regex to strip type parameters like `(10,2)` from type names. */
    private val PARAM_STRIP_REGEX = Regex("\\(.*\\)")

    /**
     * Check if a column will be marked with `#[sea_orm(ignore)]`.
     *
     * Ignored columns exist in the Entity `Model` struct but are NOT present in
     * `ActiveModel` or `Column` enum. Therefore they must be excluded from
     * DTO (insert/update) and Query (filter) layers.
     *
     * Internal visibility so [DtoLayer] can also filter for `needsPrelude`/`needsDecimal`.
     */
    internal fun isSeaOrmIgnored(col: ColumnInfo): Boolean {
        if (col.isVirtual) return true
        val normalizedSqlBase = PARAM_STRIP_REGEX.replace(col.sqlType.trim().lowercase(), "").trim()
        return normalizedSqlBase in PG_CUSTOM_STRING_TYPES
    }

    /** Regex to extract precision and scale from `numeric(10, 2)`. */
    private val DECIMAL_PRECISION_REGEX = Regex("\\((\\d+)\\s*,\\s*(\\d+)\\)")

    /** SQL type names for decimal/numeric types. */
    private val DECIMAL_TYPES = setOf("numeric", "decimal")

    /** SQL type names for single-precision float (→ column_type = "Float"). */
    private val FLOAT_TYPES = setOf("real", "float4")

    /** SQL type names for double-precision float (→ column_type = "Double"). */
    private val DOUBLE_TYPES = setOf("double precision", "float8")

    /**
     * PG types that map to Rust `String` but are NOT varchar/text compatible.
     *
     * These types generate `#[sea_orm(ignore, column_type = "custom(\"xxx\")", select_as = "text")]`
     * — aligned with `sea-orm-codegen 2.0` output.
     *
     * Note: `interval` is excluded — sea-orm CLI treats it as plain String without annotation.
     * Note: `bit`/`varbit` are excluded — mapped to Vec<u8> (natively supported by sea-orm).
     */
    private val PG_CUSTOM_STRING_TYPES = setOf(
        // Network
        "inet", "cidr", "macaddr", "macaddr8",
        // XML
        "xml",
        // Geometric
        "point", "line", "lseg", "box", "path", "polygon", "circle",
        // Text search
        "tsvector", "tsquery",
        // System
        "oid", "pg_lsn", "name",
        // Range types (PG 9.2+)
        "int4range", "int8range", "numrange", "tsrange", "tstzrange", "daterange",
        // Multirange types (PG 14+)
        "int4multirange", "int8multirange", "nummultirange",
        "tsmultirange", "tstzmultirange", "datemultirange"
    )

    /** Column names that represent "created at" timestamps. */
    private val CREATED_AT_COLUMNS = setOf("created_at", "create_time")

    /** Column names that represent "updated at" timestamps. */
    private val UPDATED_AT_COLUMNS = setOf("updated_at", "update_time")


    /**
     * VO Rust type: DateTime/Uuid/Decimal/Json types → String for JSON serialization & JsonSchema compatibility.
     *
     * Handles both scalar types (`Uuid` → `String`) and Vec types (`Vec<Uuid>` → `Vec<String>`).
     * This avoids schemars version conflicts (0.8 vs 1.2) for Uuid/Json in JsonSchema derive.
     */
    private fun ColumnInfo.voRustType(): String {
        val baseType = when {
            rustType in VO_STRING_CONVERT_TYPES -> "String"
            rustType.startsWith("Vec<") -> {
                val inner = rustType.removePrefix("Vec<").removeSuffix(">")
                if (inner in VO_STRING_CONVERT_TYPES) "Vec<String>" else rustType
            }
            else -> rustType
        }
        return if (isNullable) "Option<$baseType>" else baseType
    }
}

/**
 * Extension to set base-derives + extra-derives context in one call.
 *
 * Shared by all generators that support configurable derive macros.
 * Sets `$baseKey` (e.g. "dtoBaseDerives") and `$extraDerives` in the context.
 */
fun MutableMap<String, Any?>.putDerives(
    baseKey: String,
    baseDerives: List<String>,
    extraDerives: Set<String>
) {
    this[baseKey] = baseDerives.joinToString(", ")
    this["extraDerives"] = extraDerives.filter { it !in baseDerives }.joinToString(", ")
}
