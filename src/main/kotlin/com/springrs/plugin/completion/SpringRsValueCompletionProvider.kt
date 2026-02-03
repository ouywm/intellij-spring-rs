package com.springrs.plugin.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.model.ConfigFieldModel
import com.springrs.plugin.parser.RustConfigStructParser
import com.springrs.plugin.utils.SerdeUtils
import com.springrs.plugin.utils.RustTypeUtils
import com.springrs.plugin.utils.SpringRsConstants
import org.rust.lang.core.psi.RsEnumItem
import org.toml.lang.psi.TomlArray
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

/**
 * spring-rs config value completion provider.
 *
 * Provides smart completion for values in TOML config files.
 *
 * Supported scenarios:
 * ```toml
 * [web]
 * enable = true        # ← complete bool values: true/false
 * log_level = "info"   # ← complete enum values: debug/info/warn/error
 *
 * [web.middlewares]
 * file = {enable = true}  # ← inline table value completion
 * ```
 */
class SpringRsValueCompletionProvider : CompletionProvider<CompletionParameters>() {

    /**
     * Path segment info: field name + whether it's inside an array element.
     */
    private data class PathSegment(val fieldName: String, val inArray: Boolean)

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val element = parameters.position
        val project = element.project


        // Only apply to spring-rs config files.
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(element)) {
            return
        }
        if (DumbService.isDumb(project)) {
            return
        }

        // 1. Find the owning KeyValue.
        val keyValue = findKeyValue(element)
        if (keyValue == null) {
            return
        }

        // 2. Resolve key name.
        val keyName = keyValue.key.text?.replace(SpringRsConstants.INTELLIJ_COMPLETION_DUMMY, "")?.trim() ?: run {
            return
        }

        // 3. Check whether we're inside an inline table.
        val inlineTable = findParentInlineTable(element)
        if (inlineTable != null) {

            // Use text position to determine whether we're in value position.
            val inlineTableText = inlineTable.text
            val elementOffset = element.textRange.startOffset - inlineTable.textRange.startOffset
            val textBeforeElement = inlineTableText.substring(0, elementOffset.coerceAtLeast(0))


            // Check whether there is an '=' after the last comma.
            val textAfterLastComma = if (textBeforeElement.contains(",")) {
                textBeforeElement.substringAfterLast(",")
            } else {
                textBeforeElement
            }


            // If there's no '=' after the last comma, we're in key position.
            if (!textAfterLastComma.contains("=")) {
                return
            }

            // If we are after '=', also check whether the value is already completed.
            val trimmedBefore = textBeforeElement.trimEnd()
            if (trimmedBefore.isNotEmpty()) {
                val lastChar = trimmedBefore.last()
                // If the last character indicates a completed value, don't trigger completion.
                if (lastChar == '"' || lastChar == '\'' || lastChar == '}' || lastChar == ']' ||
                    lastChar.isDigit() || trimmedBefore.endsWith("true") || trimmedBefore.endsWith("false")) {
                    return
                }
            }

            handleInlineTableValueCompletion(inlineTable, keyName, project, result)
            return
        }

        // 4. Check whether we're still in key position (regular KeyValue).
        // Using '=' position is more reliable than PSI ranges: when the value PSI hasn't formed yet,
        // the dummy identifier may be parsed into the key.
        val caretOffset = parameters.offset
        val eqIndexInKeyValue = keyValue.text.indexOf('=')
        val eqOffset = if (eqIndexInKeyValue >= 0) keyValue.textRange.startOffset + eqIndexInKeyValue else -1


        if (eqOffset != -1 && caretOffset <= eqOffset) {
            return
        }

        // 5. Regular config value completion.
        val sectionName = getCurrentSectionFromKeyValue(keyValue)
        if (sectionName == null) {
            return
        }

        handleNormalValueCompletion(sectionName, keyName, project, result)
    }

    private fun sanitizePrefixMatcher(result: CompletionResultSet): CompletionResultSet {
        val rawPrefix = result.prefixMatcher.prefix
        // When completion runs, IntelliJ injects a dummy identifier into PSI.
        // In TOML string literals, some prefix matchers may also include quotes.
        val cleanedPrefix = rawPrefix
            .replace(SpringRsConstants.INTELLIJ_COMPLETION_DUMMY, "")
            .trim()
            .trim('"', '\'')

        return if (cleanedPrefix == rawPrefix) result else result.withPrefixMatcher(cleanedPrefix)
    }

    /**
     * Handle value completion inside inline tables (supports arbitrary nesting).
     *
     * Examples:
     * - info = {title = |}  → top-level inline table, completes title's value
     * - info = {contact = {name = |}}  → nested inline table, completes name's value
     */
    private fun handleInlineTableValueCompletion(
        inlineTable: TomlInlineTable,
        keyName: String,
        project: com.intellij.openapi.project.Project,
        result: CompletionResultSet
    ) {

        // Parse the actual key name from inline-table text (PSI may still contain placeholder tokens).
        val inlineTableText = inlineTable.text

        val lastEqualIndex = inlineTableText.lastIndexOf('=')
        if (lastEqualIndex == -1) {
            return
        }

        val textBeforeEqual = inlineTableText.substring(0, lastEqualIndex).trim()

        // Extract actual key name.
        val actualKeyName = if (textBeforeEqual.contains(',')) {
            // Multiple fields: take the part after the last comma.
            textBeforeEqual.substringAfterLast(',').trim()
        } else {
            // First field: remove the leading '{'.
            textBeforeEqual.removePrefix("{").trim()
        }

        // Walk up the inline table chain and collect the key path until we reach TomlTable.
        // Supports inline tables inside array elements, e.g. items = [{name = ""}]
        val keyPath = mutableListOf<PathSegment>()
        var sectionName: String? = null
        var currentNode: com.intellij.psi.PsiElement = inlineTable

            while (true) {
                var parent = currentNode.parent

                // If parent is TomlArray, skip it and keep walking up to find TomlKeyValue.
                val inArray = parent is TomlArray
                if (inArray) {
                    parent = parent.parent
                }

            val kv = parent as? TomlKeyValue ?: break
            keyPath.add(0, PathSegment(kv.key.text ?: break, inArray))

            val grandParent = kv.parent
            if (grandParent is TomlTable) {
                sectionName = grandParent.header.key?.text
                break
                } else if (grandParent is TomlInlineTable) {
                    currentNode = grandParent
                } else if (grandParent is TomlArray) {
                    // KeyValue is inside an array (nested array case); keep walking up.
                    currentNode = grandParent
                } else {
                    break
                }
        }

        if (sectionName == null || keyPath.isEmpty()) {
            return
        }

        val parser = RustConfigStructParser(project)
        val typeIndex = parser.getTypeIndex()

        // Resolve the key path step by step to find the struct corresponding to the current inline table.
        // Supports Vec<T>: if the field is Vec and we're inside an array element, use the inner type T.
        val sectionStruct = parser.resolveStructForSection(sectionName) ?: return

        var currentStruct = sectionStruct
        for (segment in keyPath) {
            val field = parser.findFieldInStruct(currentStruct, segment.fieldName) ?: return

            val fieldType = field.type

            when {
                // Vec<T> inside array element: extract inner type T and resolve.
                segment.inArray && RustTypeUtils.isVecType(fieldType) -> {
                    val innerTypeName = RustTypeUtils.extractInnerTypeName(fieldType)
                    currentStruct = parser.findStructByTypeName(innerTypeName) ?: return
                }
                // Map/Set and other collection-like types: no completion.
                RustTypeUtils.isMapType(fieldType) -> return
                // Vec but not inside an array element (likely a syntax/user error): no completion.
                RustTypeUtils.isVecType(fieldType) -> return
                // Regular struct type.
                else -> {
                    currentStruct = parser.resolveFieldType(field.psiElement, typeIndex.structs) ?: return
                }
            }
        }

        // Resolve the target field in the current struct.
        val field = parser.findFieldInStruct(currentStruct, actualKeyName) ?: return

        // Provide completions based on the field type.
        provideValueCompletionByField(field, parser, sanitizePrefixMatcher(result))
    }

    /**
     * Handle value completion for regular (non-inline-table) config entries.
     */
    private fun handleNormalValueCompletion(
        sectionName: String,
        keyName: String,
        project: com.intellij.openapi.project.Project,
        result: CompletionResultSet
    ) {

        val sanitizedResult = sanitizePrefixMatcher(result)

        // Resolve the config field from Rust code.
        val parser = RustConfigStructParser(project)
        val configFields = parser.getConfigFields(sectionName)
        val field = configFields.find { it.name == keyName }

        if (field != null) {
            provideValueCompletionByField(field, parser, sanitizedResult)
        } else {
        }
    }

    /**
     * Provide value completion by field type.
     */
    private fun provideValueCompletionByField(
        field: ConfigFieldModel,
        parser: RustConfigStructParser,
        result: CompletionResultSet
    ) {

        val caseInsensitiveResult = result.caseInsensitive()
        val baseType = RustTypeUtils.extractTypeName(field.type)
        val defaultTailText = SpringRsBundle.message("springrs.completion.tail.default")

        when {
            // bool: provide true/false.
            baseType == "bool" -> {
                // If there is a default value, show it first in bold.
                val defaultVal = field.defaultValue
                if (defaultVal == "true" || defaultVal == "false") {
                    caseInsensitiveResult.addElement(
                        LookupElementBuilder.create("true")
                            .withBoldness(defaultVal == "true")
                            .withTailText(if (defaultVal == "true") defaultTailText else null, true)
                    )
                    caseInsensitiveResult.addElement(
                        LookupElementBuilder.create("false")
                            .withBoldness(defaultVal == "false")
                            .withTailText(if (defaultVal == "false") defaultTailText else null, true)
                    )
                } else {
                    caseInsensitiveResult.addElement(
                        LookupElementBuilder.create("true")
                            .withBoldness(true)
                    )
                    caseInsensitiveResult.addElement(
                        LookupElementBuilder.create("false")
                    )
                }
            }
            // String (and string-like types): if there is a default value, suggest it.
            RustTypeUtils.isStringLikeType(field.type) -> {
                val defaultVal = field.defaultValue
                if (defaultVal != null) {
                    // Strip outer quotes (defaultValue may include quotes like "\"/docs\"").
                    val cleanValue = defaultVal.trim('"')
                    if (cleanValue.isNotEmpty()) {
                        caseInsensitiveResult.addElement(
                            LookupElementBuilder.create(cleanValue)
                                .withBoldness(true)
                                .withTailText(defaultTailText, true)
                        )
                    }
                }
            }
            // Try to resolve an enum type.
            else -> {
                val enumItem = resolveEnumItem(field, parser, baseType)
                if (enumItem != null) {
                    val variants = SerdeUtils.parseEnumVariants(enumItem)
                    // Detect default variant.
                    val defaultVal = field.defaultValue?.trim('"')
                    variants.forEach { variant ->
                        val isDefault = variant == defaultVal
                        caseInsensitiveResult.addElement(
                            LookupElementBuilder.create(variant)
                                .withBoldness(isDefault)
                                .withTailText(if (isDefault) defaultTailText else null, true)
                        )
                    }
                } else {
                    // For other types (non-enum/non-bool/non-string), suggest default value if present.
                    val defaultVal = field.defaultValue
                    if (defaultVal != null) {
                        val cleanValue = defaultVal.trim('"')
                        if (cleanValue.isNotEmpty()) {
                            caseInsensitiveResult.addElement(
                                LookupElementBuilder.create(cleanValue)
                                    .withBoldness(true)
                                    .withTailText(defaultTailText, true)
                                    .withTypeText(field.type)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun resolveEnumItem(field: ConfigFieldModel, parser: RustConfigStructParser, baseType: String): RsEnumItem? {
        // 1) Prefer resolving from PSI reference (works even if we didn't scan that crate for enums).
        field.psiElement.typeReference?.let { typeRef ->
            RustTypeUtils.resolveEnumType(typeRef)?.let { return it }
        }

        // 2) Fallback to type-index lookup.
        val typeName = RustTypeUtils.extractStructName(baseType)
        return parser.findEnumByTypeName(typeName)
    }

    /**
     * Find the owning KeyValue.
     */
    private fun findKeyValue(element: PsiElement): TomlKeyValue? {
        var current: PsiElement? = element
        while (current != null && current !is TomlKeyValue) {
            current = current.parent
        }
        return current
    }

    /**
     * Find the parent inline table.
     */
    private fun findParentInlineTable(element: PsiElement): TomlInlineTable? {
        var current: PsiElement? = element.parent
        while (current != null) {
            if (current is TomlInlineTable) {
                return current
            }
            if (current is TomlTable) {
                return null
            }
            current = current.parent
        }
        return null
    }

    /**
     * Resolve the current section name for a KeyValue.
     */
    private fun getCurrentSectionFromKeyValue(keyValue: TomlKeyValue): String? {
        val table = keyValue.parent as? TomlTable ?: return null
        return table.header.key?.text
    }
}
