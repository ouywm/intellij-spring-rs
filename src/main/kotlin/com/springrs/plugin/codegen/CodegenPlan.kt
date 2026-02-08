package com.springrs.plugin.codegen

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.springrs.plugin.codegen.layer.CodegenLayer

/**
 * Lightweight layer configuration — replaces the old 8-parameter `LayerSpec`.
 *
 * @param layer        The [CodegenLayer] describing what to generate.
 * @param enabled      Whether this layer is enabled.
 * @param outputDir    Relative output directory (e.g., "src/entity").
 * @param isTableEnabled  Per-table filter (returns true if this table should be generated for this layer).
 */
data class LayerConfig(
    val layer: CodegenLayer,
    val enabled: Boolean,
    val outputDir: String,
    val isTableEnabled: (String) -> Boolean = { true },
    val outputDirForTable: (String) -> String = { outputDir }
)

/**
 * Unified file planning — both preview and actual generation use this same path.
 *
 * Produces a list of [GeneratedFile] entries (relative paths + content) that
 * describe exactly what files should be created/updated.
 */
object CodegenPlan {

    private val LOG = logger<CodegenPlan>()

    /**
     * Plan all files to generate for the given layers and tables.
     *
     * This is the single entry point shared by [GenerateSeaOrmDialog.buildPreviewFiles]
     * and [GenerateSeaOrmAction.actionPerformed].
     */
    fun plan(
        layers: List<LayerConfig>,
        tables: List<TableInfo>,
        project: Project
    ): List<GeneratedFile> = buildList {
        for (config in layers) {
            if (!config.enabled) continue
            val layer = config.layer

            // Group tables by their per-table output directory
            val byOutputDir = tables.groupBy { config.outputDirForTable(it.name) }

            for ((baseDir, dirTables) in byOutputDir) {
                val bySchema = dirTables.groupBy { it.schemaSubDir }
                val baseDirModules = mutableListOf<String>()
                var hasDefaultSchemaModules = false

                for ((schemaSubDir, schemaTables) in bySchema) {
                    val dir = resolveSchemaDir(baseDir, schemaSubDir)
                    val modules = mutableListOf<String>()

                    for (table in schemaTables) {
                        if (!config.isTableEnabled(table.name)) continue
                        try {
                            add(GeneratedFile("$dir/${layer.fileName(table)}", layer.generate(table, project)))
                            modules.add(layer.modName(table))
                        } catch (ex: Exception) {
                            LOG.warn("Code generation failed for ${table.name} (${layer.id})", ex)
                        }
                    }

                    if (modules.isNotEmpty()) {
                        val allModules = modules + layer.extraModules()
                        add(GeneratedFile("$dir/mod.rs", ModuleFileGenerator.generateModContent(allModules)))
                        addAll(layer.auxiliaryFiles(modules, schemaTables.filter { it.moduleName in modules }, dir))
                    }

                    if (schemaSubDir.isEmpty()) {
                        baseDirModules.addAll(modules)
                        if (modules.isNotEmpty()) hasDefaultSchemaModules = true
                    } else if (modules.isNotEmpty()) {
                        baseDirModules.add(schemaSubDir)
                    }
                }

                // Multi-schema: base mod.rs includes schema subdirectories
                if (bySchema.keys.any { it.isNotEmpty() } && baseDirModules.isNotEmpty()) {
                    val allBaseModules = if (layer.extraModules().isNotEmpty() && hasDefaultSchemaModules) {
                        (baseDirModules + layer.extraModules()).distinct()
                    } else {
                        baseDirModules.distinct()
                    }
                    add(GeneratedFile("$baseDir/mod.rs", ModuleFileGenerator.generateModContent(allBaseModules)))
                }
            }
        }
    }

    // ── Schema path utilities ──

    /** Resolve the effective directory for a given schema subdirectory. */
    fun resolveSchemaDir(baseDir: String, schemaSubDir: String): String =
        if (schemaSubDir.isEmpty()) baseDir else "$baseDir/$schemaSubDir"

    /**
     * Convert output directory to Rust crate path.
     *
     * `"src/entity"` → `"crate::entity"`
     * With schema: `"src/entity"` + `"app"` → `"crate::entity::app"`
     *
     * Migrated from [VelocityTemplateEngine].
     */
    fun dirToCratePath(dir: String, schemaSubDir: String = ""): String {
        val stripped = dir.removePrefix("src/").removePrefix("src\\")
        val base = "crate::${stripped.replace("/", "::").replace("\\", "::")}"
        return if (schemaSubDir.isNotEmpty()) "$base::$schemaSubDir" else base
    }
}
