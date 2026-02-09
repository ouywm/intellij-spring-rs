package com.springrs.plugin.codegen.layer

import com.intellij.openapi.project.Project
import com.springrs.plugin.codegen.*
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_BUILDER
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_CLONE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DEBUG
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DESERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_EQ
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_JSON_SCHEMA
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_PARTIAL_EQ
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_SERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_VALIDATE
import com.springrs.plugin.utils.SpringRsConstants.SEA_ORM_DERIVE_ENTITY_MODEL
import com.springrs.plugin.utils.SpringRsConstants.SEA_ORM_DERIVE_RELATION
import com.springrs.plugin.utils.SpringRsConstants.SEA_ORM_ENUM_ITER
import com.springrs.plugin.utils.SpringRsConstants.TYPE_DECIMAL

/**
 * Sealed class describing each code generation layer.
 *
 * Replaces the 5 separate Generator objects and the 8-parameter `LayerSpec` data class.
 */
sealed class CodegenLayer {

    /** Unique identifier for this layer (e.g., "entity", "dto"). */
    abstract val id: String

    /** Velocity template base name (without `.rs.vm`). */
    abstract val templateName: String

    /** Generated file name for the given table. */
    abstract fun fileName(table: TableInfo): String

    /** Module name for mod.rs declaration. */
    abstract fun modName(table: TableInfo): String

    /** Generate code content for the given table. */
    abstract fun generate(table: TableInfo, project: Project): String

    /** Extra module names to include in mod.rs (e.g., "prelude" for Entity layer). */
    open fun extraModules(): List<String> = emptyList()

    /**
     * Auxiliary files (e.g., prelude.rs) generated alongside the main files.
     *
     * @param modules  Module names that were actually generated in this directory.
     * @param tables   Tables whose modules are in [modules].
     * @param dir      The output directory (relative path).
     * @return List of (relativePath, content) pairs.
     */
    open fun auxiliaryFiles(modules: List<String>, tables: List<TableInfo>, dir: String): List<GeneratedFile> =
        emptyList()

}

// ══════════════════════════════════════════════════════════════
// ── Entity Layer
// ══════════════════════════════════════════════════════════════

/**
 * Entity layer — generates Sea-ORM entity structs.
 *
 * Most complex layer: supports incremental merge, has relations, generates prelude.rs,
 * and includes a string-building fallback when Velocity fails.
 */
class EntityLayer(
    private val extraDerives: Set<String>,
    private val relationsMap: Map<String, List<RelationInfo>> = emptyMap(),
    private val derivesOverrides: Map<String, Set<String>> = emptyMap()
) : CodegenLayer() {

    override val id = "entity"
    override val templateName = "entity"
    override fun fileName(table: TableInfo) = "${table.moduleName}.rs"
    override fun modName(table: TableInfo) = table.moduleName

    override fun generate(table: TableInfo, project: Project): String {
        val relations = relationsMap[table.name.lowercase()].orEmpty()
        val derives = derivesOverrides[table.name] ?: extraDerives
        return try {
            VelocityTemplateEngine.generateFromTemplate(project, templateName, table, relations) { ctx ->
                ctx.putDerives("baseDerives", ENTITY_BASE_DERIVES, derives)
                ctx["addSerde"] = DERIVE_SERIALIZE in derives || DERIVE_DESERIALIZE in derives
                ctx["addBon"] = DERIVE_BUILDER in derives
                ctx["addJsonSchema"] = DERIVE_JSON_SCHEMA in derives
                ctx["needsDecimal"] = table.columns.any { it.rustType == TYPE_DECIMAL }
            }
        } catch (_: Exception) {
            generateEntityFallback(table, derives)
        }
    }

    override fun extraModules() = listOf("prelude")

    override fun auxiliaryFiles(modules: List<String>, tables: List<TableInfo>, dir: String): List<GeneratedFile> {
        if (modules.isEmpty()) return emptyList()
        val content = ModuleFileGenerator.generatePreludeContent(tables.filter { it.moduleName in modules })
        return listOf(GeneratedFile("$dir/prelude.rs", content))
    }

    companion object {
        /** Base derives for Entity struct (always present). */
        val ENTITY_BASE_DERIVES = listOf(
            DERIVE_CLONE, DERIVE_DEBUG, DERIVE_PARTIAL_EQ, DERIVE_EQ, SEA_ORM_DERIVE_ENTITY_MODEL
        )

        /**
         * String-building fallback — no project / template engine dependency.
         */
        fun generateEntityFallback(table: TableInfo, extraDerives: Set<String> = emptySet()): String {
            val sb = StringBuilder()

            sb.appendLine("use sea_orm::entity::prelude::*;")

            val needsSerde = DERIVE_SERIALIZE in extraDerives || DERIVE_DESERIALIZE in extraDerives
            if (needsSerde) {
                val serdeImports = buildList {
                    if (DERIVE_DESERIALIZE in extraDerives) add(DERIVE_DESERIALIZE)
                    if (DERIVE_SERIALIZE in extraDerives) add(DERIVE_SERIALIZE)
                }
                sb.appendLine("use serde::{${serdeImports.joinToString(", ")}};")
            }

            if (table.columns.any { it.rustType == TYPE_DECIMAL }) {
                sb.appendLine("use rust_decimal::Decimal;")
            }

            sb.appendLine()

            if (!table.comment.isNullOrBlank()) {
                sb.appendLine("/// ${table.comment}")
            }

            val extras = extraDerives.filter { it !in ENTITY_BASE_DERIVES }
            sb.appendLine("#[derive(${(ENTITY_BASE_DERIVES + extras).joinToString(", ")})]")
            sb.appendLine("#[sea_orm(table_name = \"${table.name}\")]")
            sb.appendLine("pub struct Model {")

            for (col in table.columns) {
                if (!col.comment.isNullOrBlank()) {
                    sb.appendLine("    /// ${col.comment}")
                }
                if (col.isPrimaryKey) {
                    val attr = if (col.isAutoIncrement) "primary_key" else "primary_key, auto_increment = false"
                    sb.appendLine("    #[sea_orm($attr)]")
                }
                sb.appendLine("    pub ${col.fieldName}: ${col.fullRustType},")
            }

            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("#[derive(Copy, $DERIVE_CLONE, $DERIVE_DEBUG, $SEA_ORM_ENUM_ITER, $SEA_ORM_DERIVE_RELATION)]")
            sb.appendLine("pub enum Relation {}")
            sb.appendLine()
            sb.appendLine("impl ActiveModelBehavior for ActiveModel {}")

            return sb.toString()
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ── DTO Layer
// ══════════════════════════════════════════════════════════════

class DtoLayer(
    private val extraDerives: Set<String>,
    private val derivesOverrides: Map<String, Set<String>> = emptyMap()
) : CodegenLayer() {

    override val id = "dto"
    override val templateName = "dto"

    override fun fileName(table: TableInfo) = "${table.moduleName}_dto.rs"
    override fun modName(table: TableInfo) = "${table.moduleName}_dto"

    override fun generate(table: TableInfo, project: Project): String {
        val derives = derivesOverrides[table.name] ?: extraDerives
        return VelocityTemplateEngine.generateFromTemplate(project, templateName, table) { ctx ->
            ctx.putDerives("dtoBaseDerives", DTO_BASE_DERIVES, derives)
            ctx["addBon"] = DERIVE_BUILDER in derives
            ctx["addValidate"] = DERIVE_VALIDATE in derives
        }
    }

    companion object {
        private val DTO_BASE_DERIVES = listOf(DERIVE_DEBUG, DERIVE_DESERIALIZE)
    }
}

// ══════════════════════════════════════════════════════════════
// ── VO Layer
// ══════════════════════════════════════════════════════════════

class VoLayer(
    private val extraDerives: Set<String>,
    private val derivesOverrides: Map<String, Set<String>> = emptyMap()
) : CodegenLayer() {

    override val id = "vo"
    override val templateName = "vo"

    override fun fileName(table: TableInfo) = "${table.moduleName}_vo.rs"
    override fun modName(table: TableInfo) = "${table.moduleName}_vo"

    override fun generate(table: TableInfo, project: Project): String {
        val derives = derivesOverrides[table.name] ?: extraDerives
        return VelocityTemplateEngine.generateFromTemplate(project, templateName, table) { ctx ->
            ctx.putDerives("voBaseDerives", VO_BASE_DERIVES, derives)
            ctx["addBon"] = DERIVE_BUILDER in derives
            ctx["addJsonSchema"] = DERIVE_JSON_SCHEMA in derives
        }
    }

    companion object {
        private val VO_BASE_DERIVES = listOf(DERIVE_DEBUG, DERIVE_SERIALIZE)
    }
}

// ══════════════════════════════════════════════════════════════
// ── Service Layer
// ══════════════════════════════════════════════════════════════

class ServiceLayer : CodegenLayer() {

    override val id = "service"
    override val templateName = "service"

    override fun fileName(table: TableInfo) = "${table.moduleName}_service.rs"
    override fun modName(table: TableInfo) = "${table.moduleName}_service"

    override fun generate(table: TableInfo, project: Project): String =
        VelocityTemplateEngine.generateFromTemplate(project, templateName, table)
}

// ══════════════════════════════════════════════════════════════
// ── Route Layer
// ══════════════════════════════════════════════════════════════

class RouteLayer(
    private val dtoExtraDerives: Set<String> = emptySet()
) : CodegenLayer() {

    override val id = "route"
    override val templateName = "route"

    override fun fileName(table: TableInfo) = "${table.moduleName}_route.rs"
    override fun modName(table: TableInfo) = "${table.moduleName}_route"

    override fun generate(table: TableInfo, project: Project): String =
        VelocityTemplateEngine.generateFromTemplate(project, templateName, table) { ctx ->
            ctx["addValidate"] = DERIVE_VALIDATE in dtoExtraDerives
        }
}
