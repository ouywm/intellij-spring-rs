package com.springrs.plugin.utils

import com.springrs.plugin.config.parser.FieldAttribute
import org.rust.lang.core.psi.RsEnumItem
import org.rust.lang.core.psi.RsEnumVariant
import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.RsOuterAttributeOwner
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue

/**
 * Serde utilities.
 *
 * Helper methods for Serde-related tasks such as enum variant parsing and rename rules.
 */
object SerdeUtils {

    // ==================== Generic Serde Attribute Extraction ====================

    /**
     * Extracts a serde sub-attribute string value.
     *
     * Generic helper for extracting `value` from `#[serde(subAttributeName = "value")]`.
     *
     * @param element PSI element with attributes
     * @param subAttributeName sub-attribute name (e.g. "rename", "default")
     * @return attribute value, or null if not present
     */
    fun extractSerdeSubAttribute(element: RsOuterAttributeOwner, subAttributeName: String): String? {
        return element.outerAttrList
            .map { it.metaItem }
            .filter { it.name == SpringRsConstants.ATTR_SERDE }
            .firstNotNullOfOrNull { serdeAttr ->
                serdeAttr.metaItemArgs?.metaItemList
                    ?.find { it.name == subAttributeName }
                    ?.litExpr?.stringValue
            }
    }

    /**
     * Returns true if a serde sub-attribute exists.
     *
     * Generic helper for checking the existence of `#[serde(subAttributeName)]`.
     *
     * @param element PSI element with attributes
     * @param subAttributeName sub-attribute name (e.g. "flatten", "skip")
     */
    fun hasSerdeSubAttribute(element: RsOuterAttributeOwner, subAttributeName: String): Boolean {
        return element.outerAttrList
            .map { it.metaItem }
            .filter { it.name == SpringRsConstants.ATTR_SERDE }
            .any { serdeAttr ->
                serdeAttr.metaItemArgs?.metaItemList
                    ?.any { it.name == subAttributeName } == true
            }
    }

    // ==================== Struct Attributes ====================

    /**
     * Returns true if the struct has `#[serde(transparent)]`.
     *
     * Transparent types wrap a base type and should not be treated as nested structs.
     *
     * Example:
     * ```rust
     * #[derive(Debug, Clone, JsonSchema, Deserialize)]
     * #[serde(transparent)]
     * pub(crate) struct ChronoTimePattern(String);
     * ```
     *
     * @param struct struct definition
     */
    fun hasTransparentAttribute(struct: RsStructItem): Boolean {
        return hasSerdeSubAttribute(struct, SpringRsConstants.SERDE_TRANSPARENT)
    }

    // ==================== Enum Attributes ====================

    /**
     * Parses all enum variant names.
     *
     * Extracts variant names from enum definition, considering `#[serde(rename = "...")]`
     * and enum-level `#[serde(rename_all = "...")]`.
     *
     * @param enumItem enum definition
     * @return variant names (after serde rename rules)
     */
    fun parseEnumVariants(enumItem: RsEnumItem): List<String> {
        val renameAll = extractRenameAll(enumItem)
        return enumItem.enumBody?.enumVariantList
            ?.mapNotNull { variant ->
                variant.identifier.text?.let { getVariantDisplayName(variant, renameAll) }
            }
            ?: emptyList()
    }

    /**
     * Applies serde `rename_all` rule.
     *
     * Converts names according to the `rename_all` attribute value.
     *
     * @param name original name
     * @param renameAll rename_all rule (e.g. "snake_case", "camelCase")
     * @return converted name
     */
    fun applyRenameAllRule(name: String, renameAll: String?): String {
        return when (renameAll) {
            "PascalCase" -> name // already PascalCase
            "camelCase" -> name.replaceFirstChar { it.lowercase() }
            "snake_case" -> name.replace(Regex("([A-Z])"), "_$1").lowercase().trimStart('_')
            "SCREAMING_SNAKE_CASE" -> name.replace(Regex("([A-Z])"), "_$1").uppercase().trimStart('_')
            "kebab-case" -> name.replace(Regex("([A-Z])"), "-$1").lowercase().trimStart('-')
            "lowercase" -> name.lowercase()
            "UPPERCASE" -> name.uppercase()
            null -> name // no rename_all: keep original (Serde default)
            else -> name // unknown rename_all: keep original
        }
    }

    /**
     * Extracts enum-level `rename_all` attribute value.
     *
     * @param enumItem enum definition
     * @return rename_all value, or null if absent
     */
    fun extractRenameAll(enumItem: RsEnumItem): String? {
        return extractSerdeSubAttribute(enumItem, SpringRsConstants.SERDE_RENAME_ALL)
    }

    /**
     * Returns the display name for an enum variant (considering serde rename and rename_all).
     *
     * @param variant enum variant
     * @param renameAll enum-level rename_all rule
     * @return display name
     */
    fun getVariantDisplayName(variant: RsEnumVariant, renameAll: String?): String {
        val variantName = variant.identifier.text ?: return "unknown"

        // serde(rename = "...") has the highest priority.
        val renamedName = extractSerdeSubAttribute(variant, SpringRsConstants.SERDE_RENAME)
        if (renamedName != null) return renamedName

        // Apply rename_all.
        return applyRenameAllRule(variantName, renameAll)
    }

    /**
     * Finds the enum variant matching a TOML string value.
     *
     * @param enumItem enum definition
     * @param tomlValue TOML string value
     * @return matching enum variant, or null if none
     */
    fun resolveEnumVariant(enumItem: RsEnumItem, tomlValue: String): RsEnumVariant? {
        val renameAll = extractRenameAll(enumItem)
        val variants = enumItem.enumBody?.enumVariantList ?: return null

        for (variant in variants) {
            val displayName = getVariantDisplayName(variant, renameAll)
            if (displayName == tomlValue) return variant
        }
        return null
    }

    /**
     * Extracts Serde attributes from a field.
     *
     * Parses `#[serde(...)]` attributes on the field.
     *
     * @param field field declaration
     * @return list of Serde attributes
     */
    fun extractFieldAttributes(field: RsNamedFieldDecl): List<FieldAttribute> {
        val attributes = mutableListOf<FieldAttribute>()

        field.outerAttrList.forEach { attr ->
            val metaItem = attr.metaItem

            // Only handle serde attributes.
            if (metaItem.name == SpringRsConstants.ATTR_SERDE) {
                val args = metaItem.metaItemArgs?.metaItemList ?: return@forEach

                args.forEach { arg ->
                    when (arg.name) {
                        SpringRsConstants.SERDE_FLATTEN -> attributes.add(FieldAttribute.Flatten)
                        SpringRsConstants.SERDE_DEFAULT -> {
                            // With value: `#[serde(default = "function_name")]`
                            val value = arg.litExpr?.stringValue
                            if (value != null) {
                                attributes.add(FieldAttribute.DefaultWith(value))
                            } else {
                                attributes.add(FieldAttribute.Default)
                            }
                        }

                        SpringRsConstants.SERDE_RENAME -> {
                            val value = arg.litExpr?.stringValue
                            if (value != null) {
                                attributes.add(FieldAttribute.Rename(value))
                            }
                        }

                        SpringRsConstants.SERDE_SKIP -> attributes.add(FieldAttribute.Skip)
                        else -> {
                            // Unrecognized attribute.
                            val value = arg.litExpr?.stringValue
                            attributes.add(FieldAttribute.Other(arg.name ?: "unknown", value))
                        }
                    }
                }
            }
        }

        return attributes
    }
}
