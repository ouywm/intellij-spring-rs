package com.springrs.plugin.routes

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.springrs.plugin.utils.RustAttributeUtils
import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.RsTypeReference
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue

/**
 * Collects spring-rs component definitions from Rust source code.
 *
 * Delegates to [SpringRsUnifiedScanner] for the actual scanning, which collects
 * routes, jobs, stream listeners, and components in a single pass.
 *
 * This object retains the data classes and helper methods (e.g. [buildConfigEntriesFromStruct])
 * that are shared between the scanner and other consumers.
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
        val fieldOffset: Int = -1
    ) {
        /** Returns the effective display value (TOML value if present, else default). */
        fun displayValue(): String? = value ?: defaultValue
    }

    /**
     * Component info.
     */
    data class ComponentInfo(
        val type: ComponentType,
        val name: String,
        val file: VirtualFile,
        val offset: Int,
        val detail: String? = null,
        val configEntries: List<ConfigEntry>? = null
    )

    /**
     * Returns cached components. Delegates to [SpringRsUnifiedScanner] so that
     * all item types share a single project scan.
     */
    fun getComponentsCached(project: Project): List<ComponentInfo> {
        if (DumbService.isDumb(project)) return emptyList()
        return SpringRsUnifiedScanner.getScanResultCached(project).components
    }

    // ══════════════════════════════════════════════════════════════
    // ── Shared helpers (used by SpringRsUnifiedScanner)
    // ══════════════════════════════════════════════════════════════

    /**
     * Build config entries by combining struct fields (for defaults) with TOML values.
     * Public so that [SpringRsUnifiedScanner] can reuse the same logic.
     */
    fun buildConfigEntriesFromStruct(
        struct: RsStructItem,
        tomlValues: Map<String, String>
    ): List<ConfigEntry> {
        val entries = mutableListOf<ConfigEntry>()
        val processedKeys = mutableSetOf<String>()

        val blockFields = struct.blockFields
        if (blockFields != null) {
            for (field in blockFields.namedFieldDeclList) {
                val fieldName = field.name ?: continue
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

        // Add any extra TOML keys not present in the struct.
        for ((key, value) in tomlValues) {
            if (key !in processedKeys) {
                entries.add(ConfigEntry(key = key, value = value, defaultValue = null, isFromToml = true))
            }
        }

        return entries
    }

    /**
     * Resolve the default value for a struct field.
     */
    private fun resolveFieldDefault(field: RsNamedFieldDecl): String? {
        val serdeUtils = com.springrs.plugin.utils.SerdeUtils
        val defaultFn = serdeUtils.extractSerdeSubAttribute(field, "default")
        if (defaultFn != null) {
            val resolved = RustAttributeUtils.parseDefaultValueFromFunction(field, defaultFn)
            if (resolved != null) return resolved
        }

        val hasSerdeDefault = serdeUtils.hasSerdeSubAttribute(field, "default")
        val typeRef = field.typeReference
        val typeText = typeRef?.text ?: return null
        val innerType = com.springrs.plugin.utils.RustTypeUtils.extractInnerTypeName(typeText)
        val baseType = com.springrs.plugin.utils.RustTypeUtils.extractStructName(innerType)

        if (typeText.startsWith("Option<")) {
            return if (hasSerdeDefault) "None" else null
        }
        if (typeText.startsWith("Vec<") || typeText.contains("Vec<")) {
            return "[]"
        }

        val typeDefault = com.springrs.plugin.utils.RustTypeUtils.getDefaultValueExample(baseType)
        if (!typeDefault.startsWith("#")) return typeDefault

        if (hasSerdeDefault) {
            val enumDefault = resolveEnumDefault(typeRef)
            if (enumDefault != null) return "\"$enumDefault\""
            return "{...}"
        }

        return null
    }

    private fun resolveEnumDefault(typeRef: RsTypeReference): String? {
        val enumItem = com.springrs.plugin.utils.RustTypeUtils.resolveEnumType(typeRef) ?: return null

        for (variant in enumItem.enumBody?.enumVariantList ?: return null) {
            val isDefault = variant.outerAttrList.any { attr ->
                attr.metaItem.name == "default"
            }
            if (isDefault) {
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
}
