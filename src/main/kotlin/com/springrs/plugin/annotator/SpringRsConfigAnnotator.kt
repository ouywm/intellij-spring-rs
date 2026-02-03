package com.springrs.plugin.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.model.ConfigFieldModel
import com.springrs.plugin.parser.RustConfigStructParser
import com.springrs.plugin.parser.StructFieldParser
import com.springrs.plugin.quickfix.SpringRsRemoveQuotesFix
import com.springrs.plugin.quickfix.SpringRsReplaceTomlLiteralFix
import com.springrs.plugin.quickfix.SpringRsReplaceWithEmptyArrayFix
import com.springrs.plugin.quickfix.SpringRsWrapInArrayFix
import com.springrs.plugin.quickfix.SpringRsWrapInInlineTableFix
import com.springrs.plugin.quickfix.SpringRsWrapWithQuotesFix
import com.springrs.plugin.utils.CargoUtils
import com.springrs.plugin.utils.SpringRsIndexingUtil
import com.springrs.plugin.utils.RustTypeUtils
import org.rust.lang.core.psi.RsStructItem
import org.toml.lang.psi.*

/**
 * spring-rs config file annotator.
 *
 * Provides real-time validation for TOML config files:
 * - validate section headers
 * - support arbitrarily nested sections
 * - filter out fields behind disabled Cargo features
 *
 * Example:
 * ```toml
 * [web]  # ✓ valid
 * [web.middlewares]  # ✓ valid
 * [web.middlewares.static_assets]  # ✓ valid
 * [web.middlewares.static_assets.fallback]  # ✗ error: fallback is a String, not a struct
 * [unknown]  # ✗ error: unknown section
 * ```
 */
class SpringRsConfigAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Only apply to spring-rs config files.
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(element)) {
            return
        }

        // During indexing (Dumb Mode), skip validation to avoid IndexNotReadyException and false
        // positives (everything turning red).
        if (DumbService.isDumb(element.project)) {
            SpringRsIndexingUtil.scheduleDaemonRestartWhenSmart(element.project)

            // Show only once: anchor to the first section header in the file.
            if (element is TomlFile) {
                val firstHeader = PsiTreeUtil.findChildOfType(element, TomlTableHeader::class.java)
                val anchor = firstHeader?.key ?: firstHeader ?: element
                holder.newAnnotation(
                    HighlightSeverity.INFORMATION,
                    SpringRsBundle.message("springrs.indexing.paused")
                )
                    .range(anchor)
                    .highlightType(ProblemHighlightType.WEAK_WARNING)
                    .create()
            }
            return
        }

        when (element) {
            // Validate section headers.
            is TomlTableHeader -> validateSectionHeader(element, holder)
            // Validate key names.
            is TomlKey -> validateConfigKey(element, holder)
            // Validate value kinds (covers empty arrays/inline tables without a TomlLiteral).
            is TomlKeyValue -> validateConfigValueType(element, holder)
            // Validate values.
            is TomlLiteral -> validateConfigValue(element, holder)
        }
    }

    private fun getFilteredTypeIndex(
        parser: RustConfigStructParser,
        anchor: PsiElement
    ): RustConfigStructParser.TypeIndex {
        val typeIndex = parser.getTypeIndex()
        val vf = anchor.containingFile?.virtualFile ?: return typeIndex
        val cargoScope = CargoUtils.getCargoScopeForFile(anchor.project, vf) ?: return typeIndex
        val filteredPrefixes = filterPrefixToStruct(anchor.project, typeIndex.prefixToStruct, cargoScope)
        return typeIndex.copy(prefixToStruct = filteredPrefixes)
    }

    private fun filterPrefixToStruct(
        project: com.intellij.openapi.project.Project,
        prefixToStruct: Map<String, List<RsStructItem>>,
        cargoScope: CargoUtils.CargoScope
    ): Map<String, List<RsStructItem>> {
        val currentPackageName = cargoScope.currentPackageName
        val allowedPackages = cargoScope.allowedPackageNames

        val pkgNameCache = mutableMapOf<com.intellij.openapi.vfs.VirtualFile, String?>()
        fun getPkgName(struct: RsStructItem): String? {
            val vf = struct.containingFile?.virtualFile ?: return null
            return pkgNameCache.getOrPut(vf) { CargoUtils.findPackageNameForFile(project, vf) }
        }

        return prefixToStruct
            .mapValues { (_, structs) ->
                structs
                    // Limit to current package + its dependency graph. This avoids collisions in Cargo workspaces
                    // where multiple workspace members may define the same config_prefix (e.g. examples).
                    .filter { struct ->
                        val pkgName = getPkgName(struct)
                        pkgName != null && pkgName in allowedPackages
                    }
                    .sortedWith(
                        compareBy<RsStructItem> { struct ->
                            val pkgName = getPkgName(struct)
                            when {
                                pkgName != null && pkgName == currentPackageName -> 0
                                pkgName != null -> 1
                                else -> 2
                            }
                        }.thenBy { getPkgName(it) ?: "" }
                            .thenBy { it.containingFile?.virtualFile?.path ?: "" }
                    )
            }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Validate that a KeyValue's value kind matches the field type.
     *
     * This mainly covers cases like:
     * - numeric field assigned an array: a = []
     * - string field assigned an inline table: c.g = {}
     *
     * These cases may not contain a TomlLiteral, so [validateConfigValue] won't run.
     */
    private fun validateConfigValueType(keyValue: TomlKeyValue, holder: AnnotationHolder) {
        val value = keyValue.value ?: return
        if (value !is TomlArray && value !is TomlInlineTable) {
            return
        }

        val keyName = keyValue.key.text ?: return
        val project = keyValue.project
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, keyValue)

        val field = when (val inlineTable = findParentInlineTable(keyValue)) {
            null -> {
                val table = keyValue.parent as? TomlTable ?: return
                val sectionName = table.header.key?.text ?: return

                if (keyName.contains(".")) {
                    getDottedKeyField(keyName, sectionName, parser, typeIndex)
                } else {
                    parser.getConfigFields(sectionName, typeIndex).find { it.name == keyName }
                }
            }

            else -> {
                val pathParts = buildInlineTablePath(inlineTable)
                if (pathParts.isEmpty()) return

                val targetStruct = resolveStructByPath(parser, typeIndex, pathParts) ?: return
                val fields = StructFieldParser.parseStructFields(targetStruct, includeDocumentation = false) { fieldDecl ->
                    parser.resolveFieldType(fieldDecl, typeIndex.structs)
                }

                fields.find { it.name == keyName }
            }
        } ?: return

        when (value) {
            is TomlArray -> {
                if (!RustTypeUtils.isVecType(field.type)) {
                    val message = SpringRsBundle.message("springrs.annotator.cannot.assign.array", keyName, field.type)
                    val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                        .range(value)
                    // struct / map: offer to replace with inline table
                    if (field.isStructType || RustTypeUtils.isMapType(field.type)) {
                        builder.newFix(SpringRsWrapInInlineTableFix(value)).registerFix()
                    }
                    builder.create()
                }
            }

            is TomlInlineTable -> {
                if (!RustTypeUtils.isMapType(field.type) && !field.isStructType) {
                    val message = SpringRsBundle.message("springrs.annotator.cannot.assign.inline.table", keyName, field.type)
                    val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                        .range(value)
                    // vec: offer to replace with array
                    if (RustTypeUtils.isVecType(field.type)) {
                        builder.newFix(SpringRsReplaceWithEmptyArrayFix(value)).registerFix()
                    }
                    builder.create()
                }
            }
        }
    }

    /**
     * Validate section headers.
     *
     * Supports arbitrarily nested sections.
     */
    private fun validateSectionHeader(header: TomlTableHeader, holder: AnnotationHolder) {
        val sectionName = header.key?.text ?: return
        val project = header.project

        // Check if this is a nested section.
        if (sectionName.contains(".")) {
            validateNestedSection(sectionName, header, holder, project)
        } else {
            validateTopLevelSection(sectionName, header, holder, project)
        }
    }

    /**
     * Validate top-level sections.
     */
    private fun validateTopLevelSection(
        sectionName: String,
        header: TomlTableHeader,
        holder: AnnotationHolder,
        project: com.intellij.openapi.project.Project
    ) {
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, header)

        // Check whether it's in the available config_prefix list.
        if (!typeIndex.prefixToStruct.containsKey(sectionName)) {
            val availableSections = typeIndex.prefixToStruct.keys.sorted()

            val message = if (availableSections.isNotEmpty()) {
                SpringRsBundle.message("springrs.annotator.unknown.section", sectionName) +
                ". " + SpringRsBundle.message("springrs.annotator.available.sections", availableSections.joinToString(", "))
            } else {
                SpringRsBundle.message("springrs.annotator.unknown.section", sectionName)
            }

            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(header.key ?: header)
                .create()
        }
    }

    /**
     * Validate nested sections (supports arbitrary depth).
     *
     * Example:
     * - [web.middlewares] ✓
     * - [web.middlewares.static_assets] ✓
     * - [web.middlewares.static_assets.fallback] ✗ (fallback is a String, not a struct)
     */
    private fun validateNestedSection(
        sectionName: String,
        header: TomlTableHeader,
        holder: AnnotationHolder,
        project: com.intellij.openapi.project.Project
    ) {
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, header)
        val parts = sectionName.split(".")

        // Validate the root section.
        val rootStructList = typeIndex.prefixToStruct[parts[0]]
        if (rootStructList.isNullOrEmpty()) {
            val availableSections = typeIndex.prefixToStruct.keys.sorted()
            val message = if (availableSections.isNotEmpty()) {
                SpringRsBundle.message("springrs.annotator.unknown.root.section", parts[0]) +
                ". " + SpringRsBundle.message("springrs.annotator.available.sections", availableSections.joinToString(", "))
            } else {
                SpringRsBundle.message("springrs.annotator.unknown.root.section", parts[0])
            }
            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(header.key ?: header)
                .create()
            return
        }

        // Validate nested fields step by step.
        var currentStruct: RsStructItem = rootStructList.first()
        for (i in 1 until parts.size) {
            val fieldName = parts[i]

            // Get all fields of the current struct (filtered by enabled features).
            val fields = StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
                parser.resolveFieldType(field, typeIndex.structs) ?: return@parseStructFields null
            }

            // Find the field.
            val field = fields.find { it.name == fieldName }

            if (field == null) {
                // Field does not exist.
                val availableFields = fields.filter { it.isStructType }

                val message = if (availableFields.isNotEmpty()) {
                    SpringRsBundle.message("springrs.annotator.unknown.field", fieldName) +
                    ". " + SpringRsBundle.message("springrs.annotator.available.fields", availableFields.joinToString(", ") { it.name })
                } else {
                    SpringRsBundle.message("springrs.annotator.unknown.field", fieldName)
                }

                holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(header.key ?: header)
                    .create()
                return
            }

            // Check whether the field type is a struct.
            if (!field.isStructType) {
                val message = SpringRsBundle.message("springrs.annotator.field.not.struct", fieldName, field.type)
                holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(header.key ?: header)
                    .create()
                return
            }

            // Resolve the field type and continue validating the next level.
            val fieldType = parser.resolveFieldType(field.psiElement, typeIndex.structs)
            if (fieldType == null) {
                val message = SpringRsBundle.message("springrs.annotator.cannot.resolve.field.type", fieldName)
                holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(header.key ?: header)
                    .create()
                return
            }

            currentStruct = fieldType
        }
    }

    /**
     * Validate config keys.
     */
    private fun validateConfigKey(key: TomlKey, holder: AnnotationHolder) {
        // 1. Check if this is a section header key (already handled in validateSectionHeader).
        if (key.parent is TomlTableHeader) {
            return
        }

        // 2. Check if this is a key in a key-value pair.
        val keyValue = key.parent as? TomlKeyValue ?: return

        // 3. Resolve key name.
        val keyName = key.text ?: return

        // 4. Check if it's inside an inline table.
        val inlineTable = findParentInlineTable(keyValue)
        if (inlineTable != null) {
            // Validate inline table keys.
            validateInlineTableKey(key, keyName, inlineTable, holder)
            return
        }

        // 5. Resolve the owning table.
        val table = keyValue.parent as? TomlTable ?: return

        // 6. Resolve section name.
        val sectionName = table.header.key?.text ?: return

        // 7. Resolve config items from Rust code.
        val project = key.project
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, key)

        // 8. Check if this is a dotted key (e.g., c.f).
        if (keyName.contains(".")) {
            // Dotted key: validate nested path.
            validateDottedKey(keyName, sectionName, parser, typeIndex, key, holder)
            return
        }

        // 9. Regular key: validate top-level fields.
        val configFields = parser.getConfigFields(sectionName, typeIndex)

        // 10. If no config items are found, skip validation.
        if (configFields.isEmpty()) {
            return
        }

        // 11. Validate key name.
        val validKeys = configFields.map { it.name }

        if (!validKeys.contains(keyName)) {
            val message = if (validKeys.isNotEmpty()) {
                SpringRsBundle.message("springrs.annotator.unknown.config.key", keyName) +
                ". " + SpringRsBundle.message("springrs.annotator.available.keys", validKeys.joinToString(", "))
            } else {
                SpringRsBundle.message("springrs.annotator.unknown.config.key", keyName)
            }

            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(key)
                .create()
        }
    }

    /**
     * Validate dotted keys (e.g., c.f).
     *
     * Example: c.f = 11 validates `f` as a nested field of `c`.
     */
    private fun validateDottedKey(
        keyName: String,
        sectionName: String,
        parser: RustConfigStructParser,
        typeIndex: RustConfigStructParser.TypeIndex,
        key: TomlKey,
        holder: AnnotationHolder
    ) {
        val parts = keyName.split(".")
        if (parts.isEmpty()) return

        // Get all fields of the section.
        val configFields = parser.getConfigFields(sectionName, typeIndex)

        // Find the first segment (e.g., c).
        val rootField = configFields.find { it.name == parts[0] }
        if (rootField == null) {
            val validKeys = configFields.map { it.name }
            val message = if (validKeys.isNotEmpty()) {
                SpringRsBundle.message("springrs.annotator.unknown.config.key", parts[0]) +
                ". " + SpringRsBundle.message("springrs.annotator.available.keys", validKeys.joinToString(", "))
            } else {
                SpringRsBundle.message("springrs.annotator.unknown.config.key", parts[0])
            }
            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(key)
                .create()
            return
        }

        // Resolve the root field type.
        val fieldType = parser.resolveFieldType(rootField.psiElement, typeIndex.structs)
        if (fieldType == null) {
            // Root field is not a struct type; dotted access is not allowed.
            val message = SpringRsBundle.message("springrs.annotator.field.not.struct", parts[0], rootField.type)
            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(key)
                .create()
            return
        }

        // Validate nested fields step by step.
        var currentStruct: RsStructItem = fieldType
        for (i in 1 until parts.size) {
            val fieldName = parts[i]

            val fields = StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
                parser.resolveFieldType(field, typeIndex.structs) ?: return@parseStructFields null
            }

            val field = fields.find { it.name == fieldName }
            if (field == null) {
                val validKeys = fields.map { it.name }
                val message = if (validKeys.isNotEmpty()) {
                    SpringRsBundle.message("springrs.annotator.unknown.config.key", fieldName) +
                    ". " + SpringRsBundle.message("springrs.annotator.available.keys", validKeys.joinToString(", "))
                } else {
                    SpringRsBundle.message("springrs.annotator.unknown.config.key", fieldName)
                }
                holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(key)
                    .create()
                return
            }

            // If this is not the last segment, keep resolving.
            if (i < parts.size - 1) {
                val nextFieldType = parser.resolveFieldType(field.psiElement, typeIndex.structs)
                if (nextFieldType == null) {
                    val message = SpringRsBundle.message("springrs.annotator.field.not.struct", fieldName, field.type)
                    holder.newAnnotation(HighlightSeverity.ERROR, message)
                        .range(key)
                        .create()
                    return
                }
                currentStruct = nextFieldType
            }
        }
    }

    /**
     * Validate keys inside inline tables.
     */
    private fun validateInlineTableKey(
        key: TomlKey,
        keyName: String,
        inlineTable: TomlInlineTable,
        holder: AnnotationHolder
    ) {
        val project = key.project
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, key)

        // Build the full path from section to inline table.
        val pathParts = buildInlineTablePath(inlineTable)
        if (pathParts.isEmpty()) {
            return
        }

        // Check whether the parent field is a Map type.
        // If so, keys are dynamic and should not be validated.
        if (pathParts.size >= 2) {
            val parentPath = pathParts.dropLast(1)
            val parentStruct = resolveStructByPath(parser, typeIndex, parentPath)
            if (parentStruct != null) {
                val parentFields = StructFieldParser.parseStructFields(parentStruct, includeDocumentation = false) { field ->
                    parser.resolveFieldType(field, typeIndex.structs)
                }
                val currentFieldName = pathParts.last()
                val currentField = parentFields.find { it.name == currentFieldName }
                if (currentField != null && RustTypeUtils.isMapType(currentField.type)) {
                    // Parent field is a Map type; key is dynamic, skip validation.
                    return
                }
            }
        }

        // Resolve the struct for the path.
        val targetStruct = resolveStructByPath(parser, typeIndex, pathParts)
        if (targetStruct == null) {
            return
        }

        // Get all fields of the struct.
        val fields = StructFieldParser.parseStructFields(targetStruct, includeDocumentation = false) { field ->
            parser.resolveFieldType(field, typeIndex.structs)
        }

        // Validate key name.
        val validKeys = fields.map { it.name }
        if (!validKeys.contains(keyName)) {
            val message = if (validKeys.isNotEmpty()) {
                SpringRsBundle.message("springrs.annotator.unknown.config.key", keyName) +
                ". " + SpringRsBundle.message("springrs.annotator.available.keys", validKeys.joinToString(", "))
            } else {
                SpringRsBundle.message("springrs.annotator.unknown.config.key", keyName)
            }

            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(key)
                .create()
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

    /**
     * Build the full path from section to inline table.
     */
    private fun buildInlineTablePath(inlineTable: TomlInlineTable): List<String> {
        val pathParts = mutableListOf<String>()

        // Walk up and collect key names of all parent KeyValues.
        var current: PsiElement? = inlineTable.parent
        while (current != null) {
            when (current) {
                is TomlKeyValue -> {
                    // Prepend the key name to the path.
                    val keyName = current.key.text
                    if (keyName != null) {
                        pathParts.add(0, keyName)
                    }
                }
                is TomlTable -> {
                    // Reached the section; prepend the section name.
                    val sectionName = current.header?.key?.text
                    if (sectionName != null) {
                        // Section name may contain dots (e.g. "web.openapi.info"); split it.
                        val sectionParts = sectionName.split(".")
                        pathParts.addAll(0, sectionParts)
                    }
                    break
                }
            }
            current = current.parent
        }

        return pathParts
    }

    /**
     * Resolve a struct by path.
     */
    private fun resolveStructByPath(
        parser: RustConfigStructParser,
        typeIndex: RustConfigStructParser.TypeIndex,
        pathParts: List<String>
    ): RsStructItem? {
        if (pathParts.isEmpty()) return null

        // The first part is the top-level section (e.g. "web").
        var currentStruct: RsStructItem = parser.findStructByPrefix(pathParts[0], typeIndex) ?: return null

        // Walk the remaining path and resolve each level.
        for (i in 1 until pathParts.size) {
            val fieldName = pathParts[i]

            // Get all fields of the current struct.
            val fields = StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
                parser.resolveFieldType(field, typeIndex.structs) ?: return@parseStructFields null
            }

            // Find the corresponding field.
            val field = fields.find { it.name == fieldName }
            if (field == null) {
                return null
            }

            // Resolve the field type.
            val fieldStruct = parser.resolveFieldType(field.psiElement, typeIndex.structs)
            if (fieldStruct == null) {
                return null
            }

            currentStruct = fieldStruct
        }

        return currentStruct
    }

    /**
     * Validate config values.
     */
    private fun validateConfigValue(literal: TomlLiteral, holder: AnnotationHolder) {
        // Check if inside an array.
        val array = literal.parent as? TomlArray
        if (array != null) {
            // Validate array element value.
            validateArrayElementValue(literal, array, holder)
            return
        }

        // Check if inside an inline table.
        val inlineTable = findParentInlineTable(literal)
        if (inlineTable != null) {
            // Validate inline table value.
            validateInlineTableValue(literal, inlineTable, holder)
            return
        }

        // Regular key-value validation.
        // 1. Resolve owning KeyValue.
        val keyValue = literal.parent as? TomlKeyValue ?: return

        // 2. Resolve key name.
        val keyName = keyValue.key.text ?: return

        // 3. Resolve current value (strip quotes).
        val currentValue = literal.text?.trim('"', '\'') ?: return

        // 4. Resolve owning table.
        val table = keyValue.parent as? TomlTable ?: return

        // 5. Resolve section name.
        val sectionName = table.header.key?.text ?: return

        // 6. Resolve config type from Rust code.
        val project = literal.project
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, literal)

        // Check if this is a dotted key (e.g. c.g).
        val field = if (keyName.contains(".")) {
            // Dotted key: resolve nested path to get the field.
            getDottedKeyField(keyName, sectionName, parser, typeIndex)
        } else {
            // Regular key: find top-level field directly.
            val configFields = parser.getConfigFields(sectionName, typeIndex)
            configFields.find { it.name == keyName }
        }

        // If the field can't be resolved, skip validation.
        if (field == null) return

        // 7. Check whether the actual value kind matches.
        // First, check if an invalid complex type is assigned (array/inline table).
        val value = keyValue.value

        when {
            // If a simple type is expected but an array is assigned.
            value is TomlArray && !RustTypeUtils.isVecType(field.type) -> {
                val message = SpringRsBundle.message("springrs.annotator.cannot.assign.array", keyName, field.type)
                holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(value)
                    .create()
                return
            }

            // If a simple type is expected but an inline table is assigned.
            value is TomlInlineTable && !RustTypeUtils.isMapType(field.type) && !field.isStructType -> {
                val message = SpringRsBundle.message("springrs.annotator.cannot.assign.inline.table", keyName, field.type)
                holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(value)
                    .create()
                return
            }

            // If a Map type is expected but a string literal is assigned.
            RustTypeUtils.isMapType(field.type) && value is TomlLiteral -> {
                val message = SpringRsBundle.message("springrs.annotator.map.type.cannot.be.string", keyName, field.type)
                val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(literal)
                builder.newFix(SpringRsWrapInInlineTableFix(literal)).registerFix()
                builder.create()
                return
            }

            // If a Vec type is expected but a string literal is assigned.
            RustTypeUtils.isVecType(field.type) && value is TomlLiteral -> {
                val message = SpringRsBundle.message("springrs.annotator.vec.type.cannot.be.string", keyName, field.type)
                val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(literal)
                builder.newFix(SpringRsWrapInArrayFix(literal)).registerFix()
                builder.create()
                return
            }
        }

        // 8. Validate value content based on type.
        validateValueByType(field.type, currentValue, parser, literal, holder)
    }

    /**
     * Validate values inside inline tables.
     */
    private fun validateInlineTableValue(literal: TomlLiteral, inlineTable: TomlInlineTable, holder: AnnotationHolder) {
        // 1. Resolve owning KeyValue.
        val keyValue = literal.parent as? TomlKeyValue ?: return

        // 2. Resolve key name.
        val keyName = keyValue.key.text ?: return

        // 3. Resolve current value (strip quotes).
        val currentValue = literal.text?.trim('"', '\'') ?: return

        // 4. Build the full path from section to inline table.
        val project = literal.project
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, literal)

        val pathParts = buildInlineTablePath(inlineTable)
        if (pathParts.isEmpty()) {
            return
        }

        // 5. Resolve the struct for the path.
        val targetStruct = resolveStructByPath(parser, typeIndex, pathParts)
        if (targetStruct == null) {
            return
        }

        // 6. Get all fields of the struct.
        val fields = StructFieldParser.parseStructFields(targetStruct, includeDocumentation = false) { field ->
            parser.resolveFieldType(field, typeIndex.structs)
        }

        // 7. Find the corresponding field.
        val field = fields.find { it.name == keyName } ?: return

        // 8. Check whether it's a Map type.
        if (RustTypeUtils.isMapType(field.type)) {
            val message = SpringRsBundle.message("springrs.annotator.map.type.cannot.be.string", keyName, field.type)
            val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(literal)
            builder.newFix(SpringRsWrapInInlineTableFix(literal)).registerFix()
            builder.create()
            return
        }

        // 9. Check whether it's a Vec type.
        if (RustTypeUtils.isVecType(field.type)) {
            val message = SpringRsBundle.message("springrs.annotator.vec.type.cannot.be.string", keyName, field.type)
            val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(literal)
            builder.newFix(SpringRsWrapInArrayFix(literal)).registerFix()
            builder.create()
            return
        }

        // 10. Validate value based on type.
        validateValueByType(field.type, currentValue, parser, literal, holder)
    }

    /**
     * Validate array element values.
     */
    private fun validateArrayElementValue(literal: TomlLiteral, array: TomlArray, holder: AnnotationHolder) {
        // 1. Resolve owning KeyValue of the array.
        val keyValue = array.parent as? TomlKeyValue ?: return

        // 2. Resolve key name.
        val keyName = keyValue.key.text ?: return

        // 3. Resolve current value (strip quotes).
        val currentValue = literal.text?.trim('"', '\'') ?: return

        // 4. Resolve owning table.
        val table = keyValue.parent as? TomlTable ?: return

        // 5. Resolve section name.
        val sectionName = table.header.key?.text ?: return

        // 6. Resolve config type from Rust code.
        val project = literal.project
        val parser = RustConfigStructParser(project)
        val typeIndex = getFilteredTypeIndex(parser, literal)
        val configFields = parser.getConfigFields(sectionName, typeIndex)
        val field = configFields.find { it.name == keyName } ?: return

        // 7. Check whether the field type is Vec<T>.
        if (!RustTypeUtils.isVecType(field.type)) {
            return
        }

        // 8. Extract Vec's inner type.
        val innerType = RustTypeUtils.extractTypeName(field.type)

        // 9. Validate array element value based on the inner type.
        validateValueByType(innerType, currentValue, parser, literal, holder)
    }

    /**
     * Validate value by type.
     */
    private fun validateValueByType(
        type: String,
        currentValue: String,
        parser: RustConfigStructParser,
        literal: TomlLiteral,
        holder: AnnotationHolder
    ) {
        val baseType = extractTypeName(type)

        when {
            // bool: must be a TOML boolean literal.
            baseType == "bool" -> {
                // Check whether the TOML literal is a boolean.
                val isBoolean = literal.text == "true" || literal.text == "false"
                if (!isBoolean) {
                    val message = SpringRsBundle.message("springrs.annotator.invalid.bool.value", literal.text ?: currentValue)
                    holder.newAnnotation(HighlightSeverity.ERROR, message)
                        .range(literal)
                        .create()
                }
            }
            // Numeric types: must be a TOML number literal (unquoted).
            RustTypeUtils.isNumericType(baseType) -> {
                // Check whether it's a quoted string literal.
                val isStringLiteral = literal.text?.startsWith("\"") == true || literal.text?.startsWith("'") == true

                if (isStringLiteral) {
                    // If it's a string literal, report an error.
                    val message = SpringRsBundle.message("springrs.annotator.numeric.type.cannot.be.string", currentValue, baseType)
                    val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                        .range(literal)
                    builder.newFix(SpringRsRemoveQuotesFix(literal)).registerFix()
                    builder.create()
                    return
                }

                // Validate numeric format.
                val isValid = when {
                    RustTypeUtils.isIntegerType(baseType) -> currentValue.toLongOrNull() != null
                    RustTypeUtils.isFloatType(baseType) -> currentValue.toDoubleOrNull() != null
                    else -> true
                }
                if (!isValid) {
                    val message = SpringRsBundle.message("springrs.annotator.invalid.numeric.value", currentValue, baseType)
                    holder.newAnnotation(HighlightSeverity.ERROR, message)
                        .range(literal)
                        .create()
                }
            }
            // String types: must be a TOML string literal (quoted).
            RustTypeUtils.isStringLikeType(type) -> {
                // Check whether it's a quoted string literal.
                val isStringLiteral = literal.text?.startsWith("\"") == true || literal.text?.startsWith("'") == true

                if (!isStringLiteral) {
                    // If it's not a string literal, report an error (use literal.text, not currentValue).
                    val message = SpringRsBundle.message("springrs.annotator.string.type.must.be.quoted", literal.text ?: "")
                    val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                        .range(literal)
                    builder.newFix(SpringRsWrapWithQuotesFix(literal)).registerFix()
                    builder.create()
                }
            }
            // Try to resolve an enum type.
            else -> {
                val typeName = baseType
                val enumItem = parser.findEnumByTypeName(typeName)
                if (enumItem != null) {
                    // Enum values must be string literals.
                    val isStringLiteral = literal.text?.startsWith("\"") == true || literal.text?.startsWith("'") == true

                    if (!isStringLiteral) {
                        val message = SpringRsBundle.message("springrs.annotator.enum.type.must.be.string", literal.text ?: currentValue)
                        val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                            .range(literal)
                        builder.newFix(SpringRsWrapWithQuotesFix(literal)).registerFix()
                        builder.create()
                        return
                    }

                    // Parse enum variants.
                    val variants = parser.parseEnumVariants(enumItem)
                    if (!variants.contains(currentValue)) {
                        val message = SpringRsBundle.message(
                            "springrs.annotator.invalid.enum.value",
                            currentValue,
                            variants.joinToString(", ")
                        )
                        val builder = holder.newAnnotation(HighlightSeverity.ERROR, message)
                            .range(literal)

                        // Offer quick fixes to replace with candidate values.
                        variants.forEach { variant ->
                            builder.newFix(
                                SpringRsReplaceTomlLiteralFix(
                                    literal,
                                    replacementText = "\"$variant\"",
                                    presentableValue = variant
                                )
                            ).registerFix()
                        }

                        builder.create()
                    }
                }
            }
        }
    }


    /**
     * Extract the underlying type name (strip wrapper types).
     *
     * Examples: Option<String> -> String, Vec<Info> -> Info
     */
    private fun extractTypeName(typeString: String): String {
        var result = typeString

        // Strip wrappers such as Option<> and Vec<>.
        val regex = Regex("""(?:Option|Vec|Box|Arc|Rc)<(.+)>""")
        val match = regex.find(result)
        if (match != null) {
            result = match.groupValues[1].trim()
        }

        // Handle path-qualified types (e.g. aide::openapi::Info).
        if (result.contains("::")) {
            result = result.substringAfterLast("::")
        }

        return result
    }
    /**
     * Resolve the field referenced by a dotted key.
     *
     * Example: keyName = "c.g", sectionName = "my-plugin"
     * Returns: the `g` field in the `ConfigInner` struct
     */
    private fun getDottedKeyField(
        keyName: String,
        sectionName: String,
        parser: RustConfigStructParser,
        typeIndex: RustConfigStructParser.TypeIndex
    ): ConfigFieldModel? {
        val parts = keyName.split(".")
        if (parts.isEmpty()) return null

        // Get all fields of the section.
        val configFields = parser.getConfigFields(sectionName, typeIndex)

        // Find the first segment (e.g. c).
        val rootField = configFields.find { it.name == parts[0] } ?: return null

        // Resolve the root field type.
        var currentStruct = parser.resolveFieldType(rootField.psiElement, typeIndex.structs) ?: return null

        // Resolve nested fields step by step.
        for (i in 1 until parts.size) {
            val fieldName = parts[i]

            val fields = StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
                parser.resolveFieldType(field, typeIndex.structs) ?: return@parseStructFields null
            }

            // If this is the last segment, return this field.
            if (i == parts.size - 1) {
                return fields.find { it.name == fieldName }
            }

            // Otherwise, keep resolving the next level.
            val field = fields.find { it.name == fieldName } ?: return null
            currentStruct = parser.resolveFieldType(field.psiElement, typeIndex.structs) ?: return null
        }

        return null
    }
}
