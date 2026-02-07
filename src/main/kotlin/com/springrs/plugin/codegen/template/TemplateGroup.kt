package com.springrs.plugin.codegen.template

/**
 * A named group of code generation templates.
 *
 * Similar to EasyCode's template groups (Default, MyBatis-Plus, etc.).
 * Each group contains templates for all 5 layers.
 *
 * Groups can be:
 * - **Built-in**: Shipped with the plugin, not editable
 * - **Custom**: Created by the user, stored in settings or project directory
 */
class TemplateGroup() {

    /** Group name (e.g., "Default", "Minimal", "Full CRUD") */
    var name: String = "Default"

    /** Template content for each layer. Key = template name (entity/dto/vo/service/route). */
    var templates: MutableMap<String, String> = mutableMapOf()

    /** Global variables accessible in all templates of this group. */
    var globalVariables: MutableMap<String, String> = mutableMapOf()

    constructor(name: String) : this() {
        this.name = name
    }

    companion object {
        /** All template names for the 5 layers. */
        val TEMPLATE_NAMES = listOf("entity", "dto", "vo", "service", "route")

        /** Create a default group with built-in templates loaded from resources. */
        fun createDefault(): TemplateGroup {
            val group = TemplateGroup("Default")
            for (name in TEMPLATE_NAMES) {
                val content = TemplateGroup::class.java
                    .getResourceAsStream("/codegen-templates/$name.rs.vm")
                    ?.use { it.bufferedReader().readText() }
                    ?: ""
                group.templates[name] = content
            }
            group.globalVariables["author"] = System.getProperty("user.name", "")
            return group
        }
    }
}
