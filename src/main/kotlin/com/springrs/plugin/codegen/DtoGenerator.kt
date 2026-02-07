package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_BUILDER
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DEBUG
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DESERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_VALIDATE

/**
 * Generates DTO (Data Transfer Object) code: CreateXxxDto + UpdateXxxDto.
 *
 * - CreateDto uses [TableInfo.insertColumns] (excludes auto PK, created_at, etc.)
 * - UpdateDto uses [TableInfo.updateColumns] (excludes PK, created_at); all fields are `Option<T>`
 */
object DtoGenerator {

    private val DTO_BASE_DERIVES = listOf(DERIVE_DEBUG, DERIVE_DESERIALIZE)

    fun generate(table: TableInfo, extraDerives: Set<String>, project: Project): String {
        val settings = CodeGenSettingsState.getInstance(project)
        val template = VelocityTemplateEngine.loadTemplate(
            project, "dto", settings.useCustomTemplate, settings.customTemplatePath
        )
        val context = VelocityTemplateEngine.buildTableContext(table, settings)

        val extras = extraDerives.filter { it !in DTO_BASE_DERIVES }
        context["dtoBaseDerives"] = DTO_BASE_DERIVES.joinToString(", ")
        context["extraDerives"] = extras.joinToString(", ")
        context["addBon"] = DERIVE_BUILDER in extraDerives
        context["addValidate"] = DERIVE_VALIDATE in extraDerives

        return VelocityTemplateEngine.render(template, context)
    }

    /** Generated file name: `{module_name}_dto.rs` */
    fun fileName(table: TableInfo): String = "${table.moduleName}_dto.rs"

    /** Module name for mod.rs: `{module_name}_dto` */
    fun modName(table: TableInfo): String = "${table.moduleName}_dto"
}
