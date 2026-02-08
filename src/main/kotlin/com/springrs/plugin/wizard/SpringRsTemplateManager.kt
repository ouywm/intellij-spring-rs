package com.springrs.plugin.wizard

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.project.Project
import java.util.*

/**
 * spring-rs Template Manager.
 *
 * Manages loading and rendering of file templates for project generation.
 * Plugin metadata comes from [SpringRsPluginRegistry].
 */
object SpringRsTemplateManager {

    /** Generated file name constants. */
    object Files {
        const val SRC_DIR = "src"
        const val CONFIG_DIR = "config"
        const val PROTO_DIR = "proto"
        const val BIN_DIR = "bin"
        const val CARGO_TOML = "Cargo.toml"
        const val MAIN_RS = "main.rs"
        const val MOD_RS = "mod.rs"
        const val GITIGNORE = ".gitignore"
        const val BUILD_RS = "build.rs"
        const val SERVER_RS = "server.rs"
        const val HELLOWORLD_PROTO = "helloworld.proto"
    }

    /** Template path constants (maps to fileTemplates/internal/). */
    object Templates {
        const val PROJECT_CARGO = "project-Cargo.toml"
        const val PROJECT_MAIN = "project-main.rs"
        const val PROJECT_GITIGNORE = "project-gitignore"
        const val GRPC_BUILD = "grpc-build.rs"
        const val GRPC_SERVER = "grpc-server.rs"
        const val GRPC_PROTO = "grpc-helloworld.proto"
    }

    /** Config file: output file name → template path. */
    data class ConfigFile(val fileName: String, val templatePath: String)

    val CONFIG_FILES = listOf(
        ConfigFile("app.toml", "config-app.toml"),
        ConfigFile("app-dev.toml", "config-app-dev.toml"),
        ConfigFile("app-prod.toml", "config-app-prod.toml"),
        ConfigFile("app-test.toml", "config-app-test.toml")
    )

    /**
     * Render a template with properties.
     */
    fun renderTemplate(project: Project?, templateName: String, properties: Map<String, Any> = emptyMap()): String {
        val templateManager = if (project != null) {
            FileTemplateManager.getInstance(project)
        } else {
            FileTemplateManager.getDefaultInstance()
        }

        val template = templateManager.getInternalTemplate(templateName)
        val props = Properties().apply {
            properties.forEach { (key, value) ->
                setProperty(key, value.toString())
            }
        }
        return template.getText(props)
    }

    /**
     * Build template properties for project generation.
     *
     * @param extraDependencies user-added crates from crates.io search
     */
    fun buildTemplateProperties(
        projectName: String,
        plugins: List<String>,
        moduleNames: List<String>,
        extraDependencies: List<CrateSearchResult> = emptyList()
    ): Map<String, Any> {
        val defs = plugins.mapNotNull { SpringRsPluginRegistry.get(it) }

        val selectedIds = plugins.toSet()
        val dependencies = defs.joinToString("\n") { it.resolveDependency(selectedIds) }

        // Crate names already present in the template (hardcoded in project-Cargo.toml.ft)
        // and in plugin main dependencies — these must be excluded from EXTRA_DEPS.
        val reservedNames = mutableSetOf("spring", "tokio", "anyhow", "serde_json")
        defs.forEach { reservedNames.add(extractCrateName(it.dependency)) }

        // Collect all extra dep lines: plugin extras first, then user extras.
        // Deduplicate by crate name — first occurrence wins (plugin lines may include features).
        val allExtraLines = mutableListOf<String>()
        defs.forEach { def ->
            def.extraDeps.lines().filter { it.isNotBlank() }.forEach { allExtraLines.add(it) }
        }
        extraDependencies.forEach { allExtraLines.add(it.dependencyLine) }

        val seen = mutableSetOf<String>()
        seen.addAll(reservedNames)
        val dedupedExtras = allExtraLines.filter { line ->
            val name = extractCrateName(line)
            name.isNotEmpty() && seen.add(name) // returns false if already present → skip
        }
        val extraDeps = dedupedExtras.joinToString("\n")

        val buildDeps = defs.mapNotNull { it.buildDeps.ifEmpty { null } }.joinToString("\n")
        val imports = buildImports(defs, moduleNames)
        val pluginAdditions = defs.joinToString("\n") { "        .add_plugin(${it.pluginClassName})" }
        val autoConfig = buildAutoConfig(defs, moduleNames)
        val configSections = defs.mapNotNull { it.configSection.ifEmpty { null } }.joinToString("\n\n")

        return mapOf(
            "PROJECT_NAME" to projectName,
            "DEPENDENCIES" to dependencies,
            "EXTRA_DEPS" to extraDeps,
            "BUILD_DEPS" to buildDeps,
            "IMPORTS" to imports,
            "PLUGINS" to pluginAdditions,
            "AUTO_CONFIG" to autoConfig,
            "CONFIG_SECTIONS" to configSections
        )
    }

    /**
     * Build #[auto_config(...)] attribute based on selected plugins.
     * Only WebConfigurator and JobConfigurator are supported by the macro.
     * This is determined by selected plugins, NOT by whether examples are generated.
     */
    private fun buildAutoConfig(
        defs: List<SpringRsPlugin>,
        @Suppress("UNUSED_PARAMETER") moduleNames: List<String>
    ): String {
        val configurators = defs
            .mapNotNull { it.configuratorName.ifEmpty { null } }
            .distinct()

        if (configurators.isEmpty()) return ""

        return "#[auto_config(${configurators.joinToString(", ")})]\n"
    }

    private fun buildImports(
        defs: List<SpringRsPlugin>,
        moduleNames: List<String>
    ): String {
        val lines = mutableListOf<String>()

        if (moduleNames.isNotEmpty()) {
            moduleNames.forEach { lines.add("mod $it;") }
            lines.add("")
        }

        // Always add spring::App
        lines.add("use spring::App;")

        // Add plugin imports
        defs.forEach { lines.add("use ${it.crateName}::${it.pluginClassName};") }

        // Add configurator imports for all plugins that have them (based on plugins, not examples)
        val configuratorImports = defs
            .filter { it.configuratorName.isNotEmpty() }
            .map { "use ${it.crateName}::${it.configuratorName};" }
            .distinct()

        if (configuratorImports.isNotEmpty()) {
            configuratorImports.forEach { lines.add(it) }
            lines.add("use spring::auto_config;")
        }

        return lines.joinToString("\n")
    }

    fun renderCargoToml(project: Project?, props: Map<String, Any>): String =
        renderTemplate(project, Templates.PROJECT_CARGO, props)

    fun renderMainRs(project: Project?, props: Map<String, Any>): String =
        renderTemplate(project, Templates.PROJECT_MAIN, props)

    fun renderGitignore(project: Project?): String =
        renderTemplate(project, Templates.PROJECT_GITIGNORE)

    fun renderExample(project: Project?, pluginId: String): String {
        val def = SpringRsPluginRegistry.get(pluginId) ?: return "// No example template for $pluginId"
        return renderTemplate(project, def.templatePath)
    }

    fun renderConfig(project: Project?, configFile: ConfigFile, props: Map<String, Any>): String =
        renderTemplate(project, configFile.templatePath, props)

    fun renderGrpcBuild(project: Project?): String =
        renderTemplate(project, Templates.GRPC_BUILD)

    fun renderGrpcServer(project: Project?): String =
        renderTemplate(project, Templates.GRPC_SERVER)

    fun renderGrpcProto(project: Project?): String =
        renderTemplate(project, Templates.GRPC_PROTO)

    /** Extract crate name from a Cargo.toml dependency line: `serde = "1"` → `serde` */
    private fun extractCrateName(line: String): String =
        line.trim().split("=", limit = 2).first().trim()
}