package com.springrs.plugin.wizard

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * spring-rs Project Generator.
 *
 * Generates project files based on selected plugins and configuration.
 * All content is loaded from template files in resources/fileTemplates/internal/
 */
object SpringRsProjectGenerator {

    fun generate(
        baseDir: VirtualFile,
        projectName: String,
        data: SpringRsConfigurationData,
        project: Project? = null
    ) {
        runWriteAction {
            val srcDir = baseDir.createChildDirectory(this, SpringRsTemplateManager.Files.SRC_DIR)
            val configDir = baseDir.createChildDirectory(this, SpringRsTemplateManager.Files.CONFIG_DIR)

            // Check if grpc is selected (has special structure)
            val hasGrpc = data.selectedPlugins.contains(SpringRsPluginRegistry.GRPC)

            // For grpc example, don't include grpc in normal module generation
            val moduleNames = if (data.generateExample) {
                data.selectedPlugins
                    .filter { it in SpringRsPluginRegistry.ids() }
                    .filter { !hasGrpc || it != SpringRsPluginRegistry.GRPC }  // Exclude grpc from modules
                    .mapNotNull { SpringRsPluginRegistry.get(it)?.moduleName }
            } else {
                emptyList()
            }

            val props = SpringRsTemplateManager.buildTemplateProperties(
                projectName,
                data.selectedPlugins,
                moduleNames
            )

            baseDir.writeFile(this, SpringRsTemplateManager.Files.CARGO_TOML,
                SpringRsTemplateManager.renderCargoToml(project, props))

            srcDir.writeFile(this, SpringRsTemplateManager.Files.MAIN_RS,
                SpringRsTemplateManager.renderMainRs(project, props))

            if (data.generateExample) {
                // Generate normal plugin examples (excluding grpc)
                for (pluginId in data.selectedPlugins) {
                    if (hasGrpc && pluginId == SpringRsPluginRegistry.GRPC) continue
                    val def = SpringRsPluginRegistry.get(pluginId) ?: continue
                    val dir = srcDir.createChildDirectory(this, def.dirName)
                    dir.writeFile(this, SpringRsTemplateManager.Files.MOD_RS,
                        SpringRsTemplateManager.renderExample(project, pluginId))
                }

                // Generate grpc special structure
                if (hasGrpc) {
                    generateGrpcStructure(baseDir, srcDir, project)
                }
            }

            for (configFile in SpringRsTemplateManager.CONFIG_FILES) {
                configDir.writeFile(this, configFile.fileName,
                    SpringRsTemplateManager.renderConfig(project, configFile, props))
            }

            baseDir.writeFile(this, SpringRsTemplateManager.Files.GITIGNORE,
                SpringRsTemplateManager.renderGitignore(project))
        }
    }

    /**
     * Generate grpc-specific project structure:
     * - build.rs (in root)
     * - proto/helloworld.proto
     * - src/bin/server.rs
     */
    private fun generateGrpcStructure(baseDir: VirtualFile, srcDir: VirtualFile, project: Project?) {
        // Create build.rs in root
        baseDir.writeFile(this, SpringRsTemplateManager.Files.BUILD_RS,
            SpringRsTemplateManager.renderGrpcBuild(project))

        // Create proto directory and helloworld.proto
        val protoDir = baseDir.createChildDirectory(this, SpringRsTemplateManager.Files.PROTO_DIR)
        protoDir.writeFile(this, SpringRsTemplateManager.Files.HELLOWORLD_PROTO,
            SpringRsTemplateManager.renderGrpcProto(project))

        // Create src/bin directory and server.rs
        val binDir = srcDir.createChildDirectory(this, SpringRsTemplateManager.Files.BIN_DIR)
        binDir.writeFile(this, SpringRsTemplateManager.Files.SERVER_RS,
            SpringRsTemplateManager.renderGrpcServer(project))
    }

    private fun VirtualFile.writeFile(requestor: Any, name: String, content: String) {
        createChildData(requestor, name).setBinaryContent(content.toByteArray())
    }
}