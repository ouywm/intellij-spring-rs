package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project

/**
 * Generates spring-rs Service layer code with CRUD operations.
 *
 * Uses `#[derive(Clone, Service)]` and `#[inject(component)]` for DbConn injection.
 */
object ServiceGenerator {

    fun generate(table: TableInfo, project: Project): String {
        val settings = CodeGenSettingsState.getInstance(project)
        val template = VelocityTemplateEngine.loadTemplate(
            project, "service", settings.useCustomTemplate, settings.customTemplatePath
        )
        val context = VelocityTemplateEngine.buildTableContext(table, settings)
        return VelocityTemplateEngine.render(template, context)
    }

    /** Generated file name: `{module_name}_service.rs` */
    fun fileName(table: TableInfo): String = "${table.moduleName}_service.rs"

    /** Module name for mod.rs: `{module_name}_service` */
    fun modName(table: TableInfo): String = "${table.moduleName}_service"
}
