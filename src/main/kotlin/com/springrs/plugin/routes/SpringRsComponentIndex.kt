package com.springrs.plugin.routes

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.utils.FilePathValidator
import com.springrs.plugin.utils.RustAttributeUtils
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

/**
 * Collects spring-rs component definitions from Rust source code.
 *
 * Scans for:
 * - **SERVICE**: structs with `#[derive(Service)]`
 * - **CONFIGURATION**: structs with `#[config_prefix = "xxx"]` and `#[derive(Configurable)]`
 * - **PLUGIN**: `.add_plugin(XxxPlugin)` calls in the main function
 *
 * Used by the tool window to display components, configurations, and plugins.
 */
object SpringRsComponentIndex {

    /**
     * Component type.
     */
    enum class ComponentType(val displayName: String) {
        SERVICE("Service"),
        CONFIGURATION("Configuration"),
        PLUGIN("Plugin")
    }

    /**
     * A single configuration key-value entry.
     */
    data class ConfigEntry(
        val key: String,
        val value: String?,
        val defaultValue: String?,
        val isFromToml: Boolean,
        val fieldOffset: Int = -1  // Offset of the field in the Rust source file
    ) {
        /** Returns the effective display value (TOML value if present, else default). */
        fun displayValue(): String? = value ?: defaultValue
    }

    /**
     * Component info.
     *
     * @param type component type (SERVICE, CONFIGURATION, PLUGIN)
     * @param name struct name / plugin name
     * @param file source file
     * @param offset text offset for navigation
     * @param detail additional detail (config_prefix for CONFIGURATION, full plugin name for PLUGIN)
     * @param configEntries for CONFIGURATION type: list of config key-value entries
     */
    data class ComponentInfo(
        val type: ComponentType,
        val name: String,
        val file: VirtualFile,
        val offset: Int,
        val detail: String? = null,
        val configEntries: List<ConfigEntry>? = null
    )

    private val COMPONENTS_KEY: Key<CachedValue<List<ComponentInfo>>> =
        Key.create("com.springrs.plugin.routes.SpringRsComponentIndex.COMPONENTS")

    fun getComponentsCached(project: Project): List<ComponentInfo> {
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            COMPONENTS_KEY,
            {
                val components = buildComponents(project)
                CachedValueProvider.Result.create(
                    components,
                    SpringRsRouteModificationTracker.getInstance(project),
                    com.intellij.psi.util.PsiModificationTracker.getInstance(project)
                )
            },
            false
        )
    }

    private fun buildComponents(project: Project): List<ComponentInfo> {
        val components = mutableListOf<ComponentInfo>()
        val scope = GlobalSearchScope.projectScope(project)

        // Collect TOML config values for later use.
        val tomlConfigValues = collectTomlConfigValues(project)

        for (vFile in FileTypeIndex.getFiles(RsFileType, scope)) {
            ProgressManager.checkCanceled()

            if (FilePathValidator.isMacroExpanded(vFile.path)) continue

            val rsFile = PsiManager.getInstance(project).findFile(vFile) as? RsFile ?: continue

            // Scan structs for Service and Configuration.
            for (struct in PsiTreeUtil.findChildrenOfType(rsFile, RsStructItem::class.java)) {
                ProgressManager.checkCanceled()
                collectServiceComponent(vFile, struct, components)
                collectConfigurationComponent(vFile, struct, tomlConfigValues, components)
            }

            // Scan for .add_plugin(...) calls.
            for (call in PsiTreeUtil.findChildrenOfType(rsFile, RsMethodCall::class.java)) {
                ProgressManager.checkCanceled()
                collectPluginComponent(vFile, call, components)
            }
        }

        return components
            .distinctBy { "${it.type}:${it.name}:${it.file.path}:${it.offset}" }
            .sortedWith(compareBy<ComponentInfo> { it.type.ordinal }.thenBy { it.name })
    }

    // ==================== Service ====================

    private fun collectServiceComponent(vFile: VirtualFile, struct: RsStructItem, out: MutableList<ComponentInfo>) {
        if (!hasServiceDerive(struct)) return
        val name = struct.name ?: return
        val offset = struct.identifier?.textOffset ?: struct.textOffset

        out.add(
            ComponentInfo(
                type = ComponentType.SERVICE,
                name = name,
                file = vFile,
                offset = offset
            )
        )
    }

    /**
     * Check whether a struct has `#[derive(Service)]`.
     */
    private fun hasServiceDerive(struct: RsStructItem): Boolean {
        return struct.outerAttrList
            .map { it.metaItem }
            .filter { it.name == "derive" }
            .any { deriveAttr ->
                deriveAttr.metaItemArgs?.metaItemList?.any { it.name == "Service" } == true
            }
    }

    // ==================== Configuration ====================

    private fun collectConfigurationComponent(
        vFile: VirtualFile,
        struct: RsStructItem,
        tomlConfigValues: Map<String, Map<String, String>>,
        out: MutableList<ComponentInfo>
    ) {
        val configPrefix = RustAttributeUtils.extractConfigPrefix(struct) ?: return
        val name = struct.name ?: return
        val offset = struct.identifier?.textOffset ?: struct.textOffset

        // Get user-configured values from TOML.
        val tomlValues = tomlConfigValues[configPrefix] ?: emptyMap()

        // Build config entries from struct fields + TOML values.
        val entries = buildConfigEntries(struct, tomlValues)

        out.add(
            ComponentInfo(
                type = ComponentType.CONFIGURATION,
                name = name,
                file = vFile,
                offset = offset,
                detail = configPrefix,
                configEntries = entries
            )
        )
    }

    /**
     * Build config entries by combining struct fields (for defaults) with TOML values.
     */
    private fun buildConfigEntries(
        struct: RsStructItem,
        tomlValues: Map<String, String>
    ): List<ConfigEntry> {
        val entries = mutableListOf<ConfigEntry>()
        val processedKeys = mutableSetOf<String>()

        // Collect fields from the struct.
        val blockFields = struct.blockFields
        if (blockFields != null) {
            for (field in blockFields.namedFieldDeclList) {
                val fieldName = field.name ?: continue
                // Apply serde rename if present.
                val key = com.springrs.plugin.utils.SerdeUtils.extractSerdeSubAttribute(field, "rename") ?: fieldName

                val tomlValue = tomlValues[key]
                val defaultValue = resolveFieldDefault(field)
                val isFromToml = tomlValue != null
                val fieldOffset = field.identifier?.textOffset ?: field.textOffset

                entries.add(ConfigEntry(
                    key = key,
                    value = tomlValue,
                    defaultValue = defaultValue,
                    isFromToml = isFromToml,
                    fieldOffset = fieldOffset
                ))
                processedKeys.add(key)
            }
        }

        // Add any extra TOML keys not present in the struct (unlikely but possible).
        for ((key, value) in tomlValues) {
            if (key !in processedKeys) {
                entries.add(ConfigEntry(key = key, value = value, defaultValue = null, isFromToml = true))
            }
        }

        return entries
    }

    /**
     * Resolve the default value for a struct field.
     *
     * Priority:

     * 1. serde(default = "function_name") → resolve function return value
     * 2. serde(default) flag → resolve from type:
     *    a. Enum type with #[default] variant → use serde rename of the default variant
     *    b. Primitive type → use RustTypeUtils default
     *    c. Vec → []
     *    d. Struct type → {...}
     * 3. Option<T> → None (implicit)
     * 4. Primitive types without serde(default) → type-based default
     */
    private fun resolveFieldDefault(field: org.rust.lang.core.psi.RsNamedFieldDecl): String? {
        val serdeUtils = com.springrs.plugin.utils.SerdeUtils

        // 1. serde(default = "function_name")
        val defaultFn = serdeUtils.extractSerdeSubAttribute(field, "default")
        if (defaultFn != null) {
            val resolved = RustAttributeUtils.parseDefaultValueFromFunction(field, defaultFn)
            if (resolved != null) return resolved
        }

        // 2. Check if serde(default) is present (flag-style, no function name).
        val hasSerdeDefault = serdeUtils.hasSerdeSubAttribute(field, "default")

        val typeRef = field.typeReference
        val typeText = typeRef?.text ?: return null
        val innerType = com.springrs.plugin.utils.RustTypeUtils.extractInnerTypeName(typeText)
        val baseType = com.springrs.plugin.utils.RustTypeUtils.extractStructName(innerType)

        // 3. Option<T> without serde(default) → implicitly None.
        if (typeText.startsWith("Option<")) {
            return if (hasSerdeDefault) "None" else null
        }

        // 4. Vec<T> with serde(default) → empty array.
        if (typeText.startsWith("Vec<") || typeText.contains("Vec<")) {
            return "[]"
        }

        // 5. Primitive types → RustTypeUtils default.
        val typeDefault = com.springrs.plugin.utils.RustTypeUtils.getDefaultValueExample(baseType)
        if (!typeDefault.startsWith("#")) return typeDefault

        // 6. Not a primitive. Try resolving the type via PSI.
        if (typeRef != null && hasSerdeDefault) {
            // Try to resolve as enum type → find the #[default] variant.
            val enumDefault = resolveEnumDefault(typeRef)
            if (enumDefault != null) return "\"$enumDefault\""

            // It's a struct/complex type with serde(default) → show {...}
            return "{...}"
        }

        return null
    }

    /**
     * Resolve the default value for an enum type by finding the variant
     * annotated with `#[default]`.
     *
     * Returns the serde rename value (or variant name if no rename).
     */
    private fun resolveEnumDefault(typeRef: org.rust.lang.core.psi.RsTypeReference): String? {
        val enumItem = com.springrs.plugin.utils.RustTypeUtils.resolveEnumType(typeRef) ?: return null

        for (variant in enumItem.enumBody?.enumVariantList ?: return null) {
            val isDefault = variant.outerAttrList.any { attr ->
                val name = attr.metaItem.name
                name == "default"
            }
            if (isDefault) {
                // Check for serde(rename = "xxx") on this variant.
                val rename = variant.outerAttrList
                    .map { it.metaItem }
                    .filter { it.name == "serde" }
                    .flatMap { it.metaItemArgs?.metaItemList ?: emptyList() }
                    .firstOrNull { it.name == "rename" }
                    ?.litExpr?.stringValue

                return rename ?: variant.name
            }
        }

        return null
    }

    // ==================== Plugin ====================

    private fun collectPluginComponent(vFile: VirtualFile, call: RsMethodCall, out: MutableList<ComponentInfo>) {
        if (call.referenceName != "add_plugin") return

        val args = call.valueArgumentList.exprList
        if (args.isEmpty()) return

        val pluginExpr = args[0]
        val pluginName = extractPluginName(pluginExpr) ?: return
        val offset = call.textOffset

        out.add(
            ComponentInfo(
                type = ComponentType.PLUGIN,
                name = pluginName,
                file = vFile,
                offset = offset,
                detail = pluginExpr.text
            )
        )
    }

    /**
     * Extracts the plugin name from the argument expression.
     *
     * Handles:
     * - `SaTokenPlugin` (path expression)
     * - `WebPlugin` (path expression)
     * - `SqlxPlugin::new()` (call expression)
     */
    private fun extractPluginName(expr: RsExpr): String? {
        return when (expr) {
            is RsPathExpr -> expr.path.referenceName
            is RsCallExpr -> {
                // Handle `XxxPlugin::new(...)` or `XxxPlugin(...)`.
                val callee = expr.expr
                if (callee is RsPathExpr) {
                    // For `Plugin::new()`, return "Plugin".
                    val path = callee.path
                    val qualifier = path.path
                    qualifier?.referenceName ?: path.referenceName
                } else null
            }
            else -> expr.text.takeIf { it.length < 80 }
        }
    }

    // ==================== TOML Config Value Reading ====================

    /**
     * Reads configuration values from TOML config files.
     *
     * Returns a map: config_prefix -> (key -> value_text).
     */
    private fun collectTomlConfigValues(project: Project): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()

        val scope = GlobalSearchScope.projectScope(project)
        val tomlFileType = org.toml.lang.psi.TomlFileType

        for (vFile in FileTypeIndex.getFiles(tomlFileType, scope)) {
            if (!SpringRsConfigFileUtil.isConfigFileName(vFile.name)) continue

            val tomlFile = PsiManager.getInstance(project).findFile(vFile) as? TomlFile ?: continue

            // Iterate top-level tables (sections).
            for (element in tomlFile.children) {
                if (element is TomlTable) {
                    val header = element.header
                    val sectionName = header.key?.segments?.joinToString(".") { it.name ?: "" } ?: continue

                    val sectionValues = result.getOrPut(sectionName) { mutableMapOf() }

                    // Collect key-value pairs in this section.
                    for (entry in element.entries) {
                        val key = entry.key.text
                        val value = entry.value?.text ?: ""
                        sectionValues[key] = value
                    }
                }
            }
        }

        return result
    }
}
