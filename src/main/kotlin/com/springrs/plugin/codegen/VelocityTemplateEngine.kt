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
     *
     * @return Pair(rendered content, callback state after rendering)
     */
    fun render(templateContent: String, context: Map<String, Any?>): String {
        val callback = TemplateCallback()
        val vc = VelocityContext()

        // Inject built-in objects
        vc.put("tool", TemplateTool)
        vc.put("callback", callback)

        // Inject user context
        context.forEach { (key, value) -> if (value != null) vc.put(key, value) }

        val writer = StringWriter()
        engine.evaluate(vc, writer, "spring-rs-codegen", templateContent)
        return writer.toString()
    }

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
        val insertColumns = table.insertColumns.map { it.toContextMap() }
        val updateColumns = table.updateColumns.map { it.toUpdateContextMap() }

        // ── Timestamp detection ──
        val hasCreatedAt = table.columns.any { it.name in CREATED_AT_COLUMNS }
        val hasUpdatedAt = table.columns.any { it.name in UPDATED_AT_COLUMNS }
        val createdAtField = table.columns.firstOrNull { it.name in CREATED_AT_COLUMNS }
        val updatedAtField = table.columns.firstOrNull { it.name in UPDATED_AT_COLUMNS }
        // rustType was already correctly set by the dialect during readTable, so this is DB-agnostic
        val timestampNowExpr = resolveTimestampNowExpr((createdAtField ?: updatedAtField)?.rustType)

        return mutableMapOf(
            // ── Global info ──
            "author" to System.getProperty("user.name", ""),
            "date" to TemplateTool.currDate(),
            "year" to TemplateTool.currYear(),

            // ── Table names ──
            "tableName" to table.name,
            "tableComment" to (table.comment ?: ""),
            "entityName" to table.entityName,
            "moduleName" to table.moduleName,
            "serviceName" to table.serviceName,
            "dtoCreateName" to table.dtoCreateName,
            "dtoUpdateName" to table.dtoUpdateName,
            "voName" to table.voName,

            // ── Columns ──
            "columns" to columns,
            "insertColumns" to insertColumns,
            "updateColumns" to updateColumns,

            // ── Primary key ──
            "primaryKeyType" to table.primaryKeyType,
            "primaryKeyName" to table.primaryKeyName,

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
            "entityModulePath" to dirToCratePath(settings.entityOutputDir),
            "dtoModulePath" to dirToCratePath(settings.dtoOutputDir),
            "voModulePath" to dirToCratePath(settings.voOutputDir),
            "serviceModulePath" to dirToCratePath(settings.serviceOutputDir),

            // ── Relations (from ForeignKeyDetector) ──
            "relations" to relations.map { it.toContextMap() },
            "hasRelations" to relations.isNotEmpty(),
            "belongsToRelations" to relations.filter { it.relationType == RelationType.BELONGS_TO }.map { it.toContextMap() },
            "hasManyRelations" to relations.filter { it.relationType == RelationType.HAS_MANY }.map { it.toContextMap() },
            "hasOneRelations" to relations.filter { it.relationType == RelationType.HAS_ONE }.map { it.toContextMap() }
        )
    }

    // ── Helpers ──

    /**
     * Convert output directory to Rust crate path.
     * `"src/entity"` → `"crate::entity"`
     */
    private fun dirToCratePath(dir: String): String {
        val stripped = dir.removePrefix("src/").removePrefix("src\\")
        return "crate::${stripped.replace("/", "::").replace("\\", "::")}"
    }

    /**
     * Convert [ColumnInfo] to a map for Velocity access.
     *
     * Keys match the design-doc variable names: `$column.isPrimaryKey`, etc.
     */
    private fun ColumnInfo.toContextMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "fieldName" to fieldName,
        "rustType" to rustType,
        "fullRustType" to fullRustType,
        "sqlType" to sqlType,
        "isPrimaryKey" to isPrimaryKey,
        "isNullable" to isNullable,
        "isAutoIncrement" to isAutoIncrement,
        "isDateTimeType" to (rustType in DATE_TIME_TYPES),
        "comment" to (comment ?: ""),
        "defaultValue" to (defaultValue ?: ""),
        "voRustType" to voRustType()
    )

    /**
     * Extended context for update columns: includes `isEntityNullable` flag
     * so the service template can decide between `Set(val)` and `Set(Some(val))`.
     */
    private fun ColumnInfo.toUpdateContextMap(): Map<String, Any?> {
        val base = toContextMap().toMutableMap()
        base["isEntityNullable"] = isNullable
        return base
    }

    /** Convert [RelationInfo] to Velocity-accessible map. */
    private fun RelationInfo.toContextMap(): Map<String, Any> = mapOf(
        "relationType" to relationType.name,
        "isBelongsTo" to (relationType == RelationType.BELONGS_TO),
        "isHasMany" to (relationType == RelationType.HAS_MANY),
        "isHasOne" to (relationType == RelationType.HAS_ONE),
        "targetTable" to targetTable,
        "targetModuleName" to targetTable.toSnakeCase(),
        "fromColumn" to fromColumn,
        "toColumn" to toColumn,
        "variantName" to variantName,
        "targetEntityPath" to targetEntityPath,
        "fromColumnPath" to fromColumnPath,
        "toColumnPath" to toColumnPath
    )

    /** DateTime types that are converted to String in VO layer. */
    private val DATE_TIME_TYPES = setOf(TYPE_DATE_TIME, TYPE_DATE_TIME_WITH_TZ, TYPE_DATE, TYPE_TIME)

    /** Column names that represent "created at" timestamps. */
    private val CREATED_AT_COLUMNS = setOf("created_at", "create_time")

    /** Column names that represent "updated at" timestamps. */
    private val UPDATED_AT_COLUMNS = setOf("updated_at", "update_time")


    /**
     * VO Rust type: datetime types are converted to String.
     */
    private fun ColumnInfo.voRustType(): String {
        val baseType = if (rustType in DATE_TIME_TYPES) "String" else rustType
        return if (isNullable) "Option<$baseType>" else baseType
    }
}
