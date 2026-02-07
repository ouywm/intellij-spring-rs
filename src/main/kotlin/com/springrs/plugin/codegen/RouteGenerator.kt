package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project

/**
 * Generates spring-rs Route layer code with CRUD handlers.
 *
 * Uses `#[get]`, `#[post]`, `#[put]`, `#[delete]` macros and `Component<Service>` injection.
 */
object RouteGenerator {

    fun generate(table: TableInfo, project: Project): String {
        val settings = CodeGenSettingsState.getInstance(project)
        val template = VelocityTemplateEngine.loadTemplate(
            project, "route", settings.useCustomTemplate, settings.customTemplatePath
        )
        val context = VelocityTemplateEngine.buildTableContext(table, settings)
        return VelocityTemplateEngine.render(template, context)
    }

    /** Generated file name: `{module_name}_route.rs` */
    fun fileName(table: TableInfo): String = "${table.moduleName}_route.rs"

    /** Module name for mod.rs: `{module_name}_route` */
    fun modName(table: TableInfo): String = "${table.moduleName}_route"
}
