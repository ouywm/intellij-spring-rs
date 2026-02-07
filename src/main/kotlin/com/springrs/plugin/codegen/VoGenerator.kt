package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_BUILDER
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DEBUG
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_SERIALIZE

/**
 * Generates VO (View Object) code for API responses.
 *
 * DateTime types are converted to `String` for JSON serialization.
 */
object VoGenerator {

    private val VO_BASE_DERIVES = listOf(DERIVE_DEBUG, DERIVE_SERIALIZE)

    fun generate(table: TableInfo, extraDerives: Set<String>, project: Project): String {
        val settings = CodeGenSettingsState.getInstance(project)
        val template = VelocityTemplateEngine.loadTemplate(
            project, "vo", settings.useCustomTemplate, settings.customTemplatePath
        )
        val context = VelocityTemplateEngine.buildTableContext(table, settings)

        val extras = extraDerives.filter { it !in VO_BASE_DERIVES }
        context["voBaseDerives"] = VO_BASE_DERIVES.joinToString(", ")
        context["extraDerives"] = extras.joinToString(", ")
        context["addBon"] = DERIVE_BUILDER in extraDerives

        return VelocityTemplateEngine.render(template, context)
    }

    /** Generated file name: `{module_name}_vo.rs` */
    fun fileName(table: TableInfo): String = "${table.moduleName}_vo.rs"

    /** Module name for mod.rs: `{module_name}_vo` */
    fun modName(table: TableInfo): String = "${table.moduleName}_vo"
}
