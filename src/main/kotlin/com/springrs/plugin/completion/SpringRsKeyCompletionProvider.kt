package com.springrs.plugin.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.util.ProcessingContext
import com.springrs.plugin.parser.RustConfigStructParser
import org.rust.lang.core.psi.RsStructItem
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

/**
 * spring-rs config key completion provider.
 *
 * Provides smart completion for keys in TOML config files.
 *
 * Supported scenarios:
 * ```toml
 * [web]
 * port = 8080        # ← completes "port"
 * host = "0.0.0.0"   # ← completes "host"
 *
 * [web.openapi]
 * title = "API"      # ← completes "title"
 * ```
 */
class SpringRsKeyCompletionProvider : CompletionProvider<CompletionParameters>() {

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

        // Ensure we are in the "key" part of a KeyValue.
        val keySegment = element.parent as? TomlKeySegment ?: run {
            return
        }

        val key = keySegment.parent as? TomlKey ?: run {
            return
        }

        val keyValue = key.parent as? TomlKeyValue ?: run {
            return
        }

        // Resolve owning table.
        val table = keyValue.parent as? TomlTable ?: run {
            return
        }

        // Resolve section name.
        val sectionName = table.header.key?.text ?: run {
            return
        }

        // Resolve config items from Rust code.
        val parser = RustConfigStructParser(project)

        // Check if this is a dotted key (e.g. c.).
        val keyText = key.text ?: ""
        if (keyText.contains(".")) {
            // Dotted-key completion.
            addDottedKeyCompletions(keyText, sectionName, parser, table, result)
        } else {
            // Regular key completion.
            val configFields = parser.getConfigFields(sectionName)

            if (configFields.isEmpty()) {
                return
            }

            // Keys already present in the current section.
            val existingKeys = table.entries.mapNotNull { it.key.text }.toSet()

            // Nested sections that already exist (e.g. for [web], if [web.openapi] exists, don't suggest "openapi").
            val nestedSectionKeys = getNestedSectionKeys(element.containingFile as? TomlFile, sectionName)

            // Add completion items (exclude existing keys and keys already used as nested sections).
            configFields.forEach { field ->
                if (field.name in existingKeys) {
                    return@forEach
                }
                // If this field is already used as a nested section, don't suggest it.
                if (field.name in nestedSectionKeys) {
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

    }

    /**
     * Get key names that already exist as nested sections.
     *
     * Example: for [web], if [web.openapi] exists, returns {"openapi"}.
     *
     * @param tomlFile TOML file
     * @param currentSection current section name
     * @return key names already used as nested sections
     */
    private fun getNestedSectionKeys(tomlFile: TomlFile?, currentSection: String): Set<String> {
        if (tomlFile == null) return emptySet()

        val prefix = "$currentSection."
        return tomlFile.children
            .filterIsInstance<TomlTable>()
            .mapNotNull { it.header.key?.text }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).split(".").first() }  // Take the first segment.
            .toSet()
    }

    /**
     * Add dotted-key completions (e.g. c.f).
     *
     * Example: after typing "c.", completes fields of the struct referenced by `c`.
     */
    private fun addDottedKeyCompletions(
        keyText: String,
        sectionName: String,
        parser: RustConfigStructParser,
        table: TomlTable,
        result: CompletionResultSet
    ) {

        // Split the dotted key.
        val parts = keyText.split(".")
        if (parts.isEmpty()) {
            return
        }

        // Drop the trailing empty part (caret position).
        val completeParts = if (parts.last().isEmpty() || parts.last().contains("IntellijIdea")) {
            parts.dropLast(1)
        } else {
            parts
        }

        if (completeParts.isEmpty()) {
            return
        }


        val typeIndex = parser.getTypeIndex()

        // Get all fields of the section.
        val configFields = parser.getConfigFields(sectionName)

        // Resolve the root field (e.g. c).
        val rootField = configFields.find { it.name == completeParts[0] }
        if (rootField == null) {
            return
        }


        // Resolve the root field type.
        val fieldType = parser.resolveFieldType(rootField.psiElement, typeIndex.structs)
        if (fieldType == null) {
            return
        }

        // Resolve nested fields step by step.
        var currentStruct: RsStructItem = fieldType
        for (i in 1 until completeParts.size) {
            val fieldName = completeParts[i]

            val fields = com.springrs.plugin.parser.StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
                parser.resolveFieldType(field, typeIndex.structs) ?: return@parseStructFields null
            }

            val field = fields.find { it.name == fieldName }
            if (field == null) {
                return
            }

            val nextFieldType = parser.resolveFieldType(field.psiElement, typeIndex.structs)
            if (nextFieldType == null) {
                return
            }

            currentStruct = nextFieldType
        }

        // Get all fields of the current struct.
        val fields = com.springrs.plugin.parser.StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
            parser.resolveFieldType(field, typeIndex.structs) ?: return@parseStructFields null
        }


        // Collect existing dotted keys.
        val existingDottedKeys = getExistingDottedKeys(table, completeParts)

        // Add completion items (exclude existing keys).
        fields.forEach { field ->
            // Build full key name (e.g. c.f).
            val fullKeyName = "${completeParts.joinToString(".")}.${field.name}"

            // Skip if this full dotted key already exists.
            if (existingDottedKeys.contains(fullKeyName)) {
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
     * Get dotted keys already present in the current section.
     *
     * @param table current TOML table
     * @param prefix dotted-key prefix (e.g. ["c"])
     * @return existing full dotted keys (e.g. ["c.f", "c.g"])
     */
    private fun getExistingDottedKeys(table: TomlTable, prefix: List<String>): Set<String> {
        val existingKeys = mutableSetOf<String>()
        val prefixStr = prefix.joinToString(".")

        // Walk all entries in the current table.
        table.entries.forEach { entry ->
            val keyText = entry.key.text
            if (keyText != null && keyText.startsWith("$prefixStr.")) {
                existingKeys.add(keyText)
            }
        }

        return existingKeys
    }
}
