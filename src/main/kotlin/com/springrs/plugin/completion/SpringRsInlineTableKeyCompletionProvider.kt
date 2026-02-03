package com.springrs.plugin.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import com.springrs.plugin.parser.RustConfigStructParser
import com.springrs.plugin.parser.StructFieldParser
import com.springrs.plugin.utils.RustTypeUtils
import org.toml.lang.psi.TomlArray
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

/**
 * spring-rs inline table key completion provider.
 *
 * Provides key completion for inline tables in TOML config files, with arbitrary nesting support.
 *
 * Supported scenarios:
 * ```toml
 * [logger]
 * file_appender = {enable = true, path = ""}  # ← top-level inline table
 *
 * [web.openapi]
 * info = {title = "", contact = {name = ""}}   # ← nested inline table
 *
 * [section]
 * items = [{name = "", value = 0}]             # ← inline table inside an array element
 * ```
 */
class SpringRsInlineTableKeyCompletionProvider : CompletionProvider<CompletionParameters>() {

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

        // Find the owning inline table.
        val inlineTable = findParentInlineTable(element)
        if (inlineTable == null) {
            return
        }

        // Check caret position: whether it's in a value position (after '=').
        val fileText = element.containingFile.text
        val cursorOffset = element.textRange.startOffset
        val lastEqualIndex = fileText.lastIndexOf('=', startIndex = (cursorOffset - 1).coerceAtLeast(0))

        if (lastEqualIndex != -1) {
            val textAfterEqual = fileText.substring(lastEqualIndex + 1, cursorOffset).trim()
            // If text after '=' is empty or a placeholder, we are in value position, so don't offer key completion.
            if (textAfterEqual.isEmpty() || textAfterEqual.contains("IntellijIdea") || textAfterEqual.contains("Rulezzz")) {
                return
            }
        }

        // Walk up the inline table chain and collect the key path until we reach TomlTable.
        // Supports inline tables inside array elements, e.g. items = [{name = ""}]
        val keyPath = mutableListOf<PathSegment>()
        var sectionName: String? = null
        var currentNode: PsiElement = inlineTable

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

        // Resolve the struct of the parent field.
        val parser = RustConfigStructParser(project)
        val typeIndex = parser.getTypeIndex()

        // Resolve the key path step by step to find the target struct.
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

        // Get all fields of the target struct.
        val targetFields = StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
            parser.resolveFieldType(field, typeIndex.structs)
        }

        // Collect existing keys.
        val existingKeys = inlineTable.entries.mapNotNull { it.key.text }.toSet()

        // Add completion items (skip existing keys and flatten'ed collection-like fields).
        targetFields.forEach { field ->
            if (field.name in existingKeys) {
                return@forEach
            }

            // Skip flatten'ed collection-like fields (e.g. extensions: IndexMap<String, Value>).
            if (field.isFlatten() && RustTypeUtils.shouldSkipFieldType(field.type)) {
                return@forEach
            }

            val lookupElement = LookupElementBuilder.create(field.name)
                .withIcon(AllIcons.Nodes.Property)
                .withTypeText(field.type)
                .withInsertHandler { insertContext, _ ->
                    CompletionInsertHandlerUtils.insertValueTemplateByFieldType(field, insertContext)
                }

            result.addElement(lookupElement)
        }

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
}
