package com.springrs.plugin.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.util.ProcessingContext
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.parser.RustConfigStructParser
import com.springrs.plugin.parser.StructFieldParser
import com.springrs.plugin.utils.SpringRsConstants
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlTable

/**
 * Section header completion provider.
 *
 * Provides completion for TOML section headers, including arbitrary nesting.
 *
 * Examples:
 * - [web] - top-level section
 * - [web.openapi] - 1-level nesting
 * - [web.openapi.info] - 2-level nesting
 * - [web.middlewares.static] - 2-level nesting
 *
 * Features:
 * 1) Scan all structs annotated with #[derive(Configurable)]
 * 2) Extract config_prefix values
 * 3) Provide section completion suggestions
 * 4) Support arbitrarily nested sections (only suggests struct-typed fields)
 * 5) Filter out fields behind disabled Cargo features
 */
class SpringRsSectionCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {

        val element = parameters.position

        // Only apply to spring-rs config files.
        val isSpringRsFile = SpringRsConfigFileUtil.isSpringRsConfigFile(element)

        if (!isSpringRsFile) {
            return
        }

        val project = element.project
        if (DumbService.isDumb(project)) {
            return
        }

        // Get the full TomlKey text.
        val keySegment = element.parent as? TomlKeySegment
        val key = keySegment?.parent as? TomlKey


        // Get the whole key text, including any already-typed parts.
        // Strip IntelliJ's completion placeholder.
        val fullKeyText = key?.text?.replace(SpringRsConstants.INTELLIJ_COMPLETION_DUMMY, "")?.trim()
            ?: element.text.replace(SpringRsConstants.INTELLIJ_COMPLETION_DUMMY, "").trim()

        // Collect all section names that already exist in the file.
        val tomlFile = element.containingFile as? TomlFile
        val existingSections = getExistingSections(tomlFile)

        // Check whether this is a nested section (contains '.').
        when {
            fullKeyText.contains(".") -> {
                // Nested section completion (arbitrary depth).
                addNestedSectionCompletions(fullKeyText, project, tomlFile, existingSections, result)
            }
            else -> {
                // Top-level section completion (including empty input).
                addTopLevelSectionCompletions(project, existingSections, result)
            }
        }

    }

    /**
     * Collect all existing section names in the config file.
     */
    private fun getExistingSections(tomlFile: TomlFile?): Set<String> {
        if (tomlFile == null) return emptySet()

        return tomlFile.children
            .filterIsInstance<org.toml.lang.psi.TomlTable>()
            .mapNotNull { it.header.key?.text }
            .toSet()
    }

    /**
     * Add top-level section completions.
     *
     * Extracts all config prefixes from Rust code.
     */
    private fun addTopLevelSectionCompletions(
        project: Project,
        existingSections: Set<String>,
        result: CompletionResultSet
    ) {

        val parser = RustConfigStructParser(project)
        // Use cached getTypeIndex().
        val typeIndex = parser.getTypeIndex()

        // Collect all config_prefix values.
        val prefixes = typeIndex.prefixToStruct.keys

        // Use case-insensitive matching.
        val caseInsensitiveResult = result.caseInsensitive()

        // Create completion items (exclude existing sections).
        var addedCount = 0
        prefixes.forEach { prefix ->
            // Skip existing sections.
            if (prefix in existingSections) {
                return@forEach
            }

            val structName = typeIndex.prefixToStruct[prefix]?.firstOrNull()?.name
            val lookupElement = LookupElementBuilder.create(prefix)
                .withIcon(AllIcons.Nodes.ConfigFolder)
                .withBoldness(true)
                .withTypeText(structName ?: SpringRsBundle.message("springrs.completion.section.typeText.default"))

            caseInsensitiveResult.addElement(lookupElement)
            addedCount++
        }

    }

    /**
     * Add nested section completions (supports arbitrary depth).
     *
     * Examples:
     * - typing [web. suggests struct fields under web (e.g. openapi, middlewares)
     * - typing [web.openapi. suggests struct fields under openapi (e.g. info)
     * - typing [web.middlewares. suggests struct fields under middlewares (e.g. static, cors)
     *
     * Note: only struct-typed fields are suggested. Primitive fields are configured inside a section.
     */
    private fun addNestedSectionCompletions(
        currentText: String,
        project: Project,
        tomlFile: TomlFile?,
        existingSections: Set<String>,
        result: CompletionResultSet
    ) {

        val parser = RustConfigStructParser(project)
        val typeIndex = parser.getTypeIndex()

        // Parse parent section path.
        // Example: currentText = "web.openapi." -> parentPath = "web.openapi"
        // Example: currentText = "web." -> parentPath = "web"
        val parts = currentText.split(".")
        val parentPath = parts.dropLast(1).joinToString(".")


        if (parentPath.isEmpty()) {
            return
        }

        // Resolve the parent section struct (supports arbitrary nesting).
        val parentStruct = parser.resolveNestedStruct(parentPath)
            ?: typeIndex.prefixToStruct[parentPath]?.firstOrNull()  // Fallback to top-level prefix lookup.

        if (parentStruct == null) {
            return
        }


        // Get all fields of the parent struct (filtered by enabled features).
        val fields = StructFieldParser.parseStructFields(parentStruct, includeDocumentation = false) { field ->
            parser.resolveFieldType(field, typeIndex.structs)
        }

        // Get keys already present in the parent section (e.g. openapi = {...} under [web]).
        val parentSectionKeys = getParentSectionKeys(tomlFile, parentPath)

        // Use case-insensitive matching.
        val caseInsensitiveResult = result.caseInsensitive()

        // Only suggest struct-typed fields that can be further nested.
        var addedCount = 0
        fields.forEach { field ->

            // Flatten'ed fields are inlined into the current section and should not be suggested as nested sections.
            if (field.isStructType && !field.isFlatten()) {
                // Build full section name (e.g. web.openapi).
                val fullSectionName = "$parentPath.${field.name}"

                // Skip existing sections.
                if (fullSectionName in existingSections) {
                    return@forEach
                }

                // Skip keys already present in the parent section (e.g. openapi = {...} under [web]).
                if (field.name in parentSectionKeys) {
                    return@forEach
                }

                val lookupElement = LookupElementBuilder.create(field.name)
                    .withIcon(AllIcons.Nodes.ConfigFolder)
                    .withBoldness(true)
                    .withTypeText(field.type)
                    .withTailText(SpringRsBundle.message("springrs.completion.tail.nested.section"), true)
                    .withInsertHandler { insertContext, _ ->
                        val document = insertContext.document
                        val editor = insertContext.editor
                        val offset = insertContext.tailOffset

                        // If the next token is ']', move caret past it.
                        if (offset < document.textLength &&
                            document.getText(TextRange(offset, offset + 1)) == "]") {
                            editor.caretModel.moveToOffset(offset + 1)
                        }

                        // Insert a new line.
                        document.insertString(editor.caretModel.offset, "\n")
                        editor.caretModel.moveToOffset(editor.caretModel.offset + 1)
                    }

                caseInsensitiveResult.addElement(lookupElement)
                addedCount++
            } else {
            }
        }

    }

    /**
     * Get keys already present in the parent section.
     *
     * Example: for parentPath = "web", if [web] contains openapi = {...}, returns {"openapi"}.
     *
     * @param tomlFile TOML file
     * @param parentPath parent section path
     * @return set of keys already present in the parent section
     */
    private fun getParentSectionKeys(tomlFile: TomlFile?, parentPath: String): Set<String> {
        if (tomlFile == null) return emptySet()

        // Find the parent section table.
        val parentTable = tomlFile.children
            .filterIsInstance<TomlTable>()
            .find { it.header.key?.text == parentPath }
            ?: return emptySet()

        // Collect keys in the parent section.
        return parentTable.entries.mapNotNull { it.key.text }.toSet()
    }
}
