package com.springrs.plugin.parser

import com.springrs.plugin.model.ConfigFieldModel
import com.springrs.plugin.config.parser.FieldAttribute
import com.springrs.plugin.config.parser.toAttributesMap
import com.springrs.plugin.utils.CfgFeatureUtils
import com.springrs.plugin.utils.RustAttributeUtils
import com.springrs.plugin.utils.RustTypeUtils
import com.springrs.plugin.utils.SerdeUtils
import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsStructItem

/**
 * Struct field parser.
 *
 * Parses Rust struct field information, including type, documentation and attributes.
 */
object StructFieldParser {

    /**
     * Parse all fields of a struct.
     *
     * Uses IntelliJ caches, automatically invalidated on PSI changes.
     *
     * @param struct Rust struct
     * @param resolveFieldType field type resolver (used to resolve referenced struct types)
     * @return list of field models
     */
    fun parseStructFields(
        struct: RsStructItem,
        resolveFieldType: (RsNamedFieldDecl) -> RsStructItem?
    ): List<ConfigFieldModel> {
        return doParseStructFields(struct, resolveFieldType, includeDocumentation = true)
    }

    fun parseStructFields(
        struct: RsStructItem,
        includeDocumentation: Boolean,
        resolveFieldType: (RsNamedFieldDecl) -> RsStructItem?
    ): List<ConfigFieldModel> {
        return doParseStructFields(struct, resolveFieldType, includeDocumentation)
    }

    /**
     * Internal implementation of struct field parsing.
     *
     * @param struct Rust struct
     * @param resolveFieldType field type resolver
     * @return list of field models
     */
    private fun doParseStructFields(
        struct: RsStructItem,
        resolveFieldType: (RsNamedFieldDecl) -> RsStructItem?,
        includeDocumentation: Boolean
    ): List<ConfigFieldModel> {
        val fields = mutableListOf<ConfigFieldModel>()

        // Cargo crate (used for cfg evaluation).
        val crate = struct.containingCrate

        // Virtual file (used for cfg checks).
        val virtualFile = struct.containingFile?.virtualFile

        // Named fields.
        struct.blockFields?.namedFieldDeclList?.forEach { field ->
            val fieldName = field.identifier.text ?: return@forEach

            // Extract cfg attributes.
            val cfgAttr = RustAttributeUtils.extractConditionalCompilation(field)

            // Skip fields behind disabled cfg conditions.
            if (!CfgFeatureUtils.checkCfgCondition(field, crate, cfgAttr, struct.project, virtualFile)) {
                return@forEach
            }

            val type = field.typeReference?.text ?: "unknown"

            // Documentation extraction can force Rust PSI parsing; allow callers to skip it in hot paths.
            val doc = if (includeDocumentation) RustAttributeUtils.extractDocComments(field) else null
            val attributes = SerdeUtils.extractFieldAttributes(field)

            // If serde(rename) is present, use the renamed field name.
            val renameAttr = attributes.filterIsInstance<FieldAttribute.Rename>().firstOrNull()
            val finalName = renameAttr?.newName ?: fieldName

            val resolvedStruct = resolveFieldType(field)
            val isStructType = resolvedStruct != null

            // Extract wrapper type and inner type (via PSI API).
            val (wrapperType, innerType) = RustTypeUtils.extractWrapperAndInner(field.typeReference)

            // Detect enum types.
            val enumType = field.typeReference?.let { RustTypeUtils.resolveEnumType(it) }
            val isEnumType = enumType != null
            // Store qualified name for precise lookup (avoids same-name enum conflicts across modules)
            val enumTypeName = enumType?.let { RustConfigStructParser.getQualifiedName(it) }

            // Extract default value info.
            val defaultFunction = attributes.filterIsInstance<FieldAttribute.DefaultWith>().firstOrNull()?.functionName
            val hasDefaultAttr = attributes.any { it is FieldAttribute.Default }

            val defaultValue = when {
                defaultFunction != null -> RustAttributeUtils.parseDefaultValueFromFunction(field, defaultFunction)
                hasDefaultAttr -> RustTypeUtils.getDefaultValueExample(innerType)
                else -> null
            }

            val visibility = field.vis?.text

            val conditionalCompilation = RustAttributeUtils.extractConditionalCompilation(field)
            val attributesMap = attributes.toAttributesMap()

            fields.add(
                ConfigFieldModel(
                    name = finalName,
                    type = type,
                    wrapperType = wrapperType,
                    innerType = innerType,
                    documentation = doc,
                    isStructType = isStructType,
                    structModel = null,
                    isEnumType = isEnumType,
                    enumTypeName = enumTypeName,
                    defaultFunction = defaultFunction,
                    defaultValue = defaultValue,
                    visibility = visibility,
                    conditionalCompilation = conditionalCompilation,
                    attributes = attributesMap,
                    psiElement = field
                )
            )
        }

        return fields
    }
}
