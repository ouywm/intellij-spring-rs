package com.springrs.plugin.parser

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.springrs.plugin.utils.CfgFeatureUtils
import com.springrs.plugin.model.ConfigFieldModel
import com.springrs.plugin.utils.FilePathValidator
import com.springrs.plugin.utils.RustAttributeUtils
import com.springrs.plugin.utils.RustTypeUtils
import com.springrs.plugin.utils.SerdeUtils
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.qualifiedName
import org.rust.lang.core.types.rawType
import org.rust.lang.core.types.ty.TyAdt

/**
 * Rust config struct parser.
 *
 * Parses Rust structs annotated with #[derive(Configurable)] and extracts config metadata used for
 * TOML completion/validation.
 *
 * Architecture:
 * - cache layer: CachedValuesManager caches and tracks PSI changes
 * - scan layer: collects type definitions from Rust source files
 * - parse layer: parses struct fields, enum variants, doc comments, etc.
 * - query layer: provides higher-level queries (by prefix, type name, ...)
 *
 * Performance:
 * - uses project-level caches to avoid rescanning
 * - caches are invalidated automatically on PSI changes
 */
class RustConfigStructParser(private val project: Project) {

    companion object {
        // Dependency cache key: invalidated when dependency Rust structure changes.
        private val DEPENDENCY_INDEX_KEY: Key<CachedValue<TypeIndex>> =
            Key.create("com.springrs.plugin.parser.RustConfigStructParser.DEPENDENCY_INDEX")

        // Project cache key: invalidated when project PSI changes.
        private val PROJECT_INDEX_KEY: Key<CachedValue<TypeIndex>> =
            Key.create("com.springrs.plugin.parser.RustConfigStructParser.PROJECT_INDEX")

        /**
         * Get fully qualified name for a struct.
         */
        fun getQualifiedName(struct: RsStructItem): String {
            return struct.qualifiedName ?: struct.name ?: "unknown"
        }

        /**
         * Get fully qualified name for an enum.
         */
        fun getQualifiedName(enum: RsEnumItem): String {
            return enum.qualifiedName ?: enum.name ?: "unknown"
        }

        /**
         * Get dependency type index (cached).
         */
        fun getDependencyIndexCached(project: Project): TypeIndex {
            return CachedValuesManager.getManager(project).getCachedValue(
                project,
                DEPENDENCY_INDEX_KEY,
                {
                    val index = buildIndex(project, ScanMode.DEPENDENCY)
                    CachedValueProvider.Result.create(
                        index,
                        project.rustPsiManager.rustStructureModificationTrackerInDependencies
                    )
                },
                false
            )
        }

        /**
         * Get project type index (cached).
         *
         * Uses PsiModificationTracker to track all PSI changes, including:
         * - struct field add/remove/change
         * - attribute value changes (e.g. config_prefix = "user" -> "user1")
         * - any other PSI changes
         */
        fun getProjectIndexCached(project: Project): TypeIndex {
            return CachedValuesManager.getManager(project).getCachedValue(
                project,
                PROJECT_INDEX_KEY,
                {
                    val index = buildIndex(project, ScanMode.PROJECT)
                    CachedValueProvider.Result.create(
                        index,
                        // Track all PSI changes.
                        PsiModificationTracker.getInstance(project)
                    )
                },
                false
            )
        }

        // ==================== Scan Mode ====================

        /**
         * Scan mode enum.
         */
        private enum class ScanMode {
            /** Scan only spring-related files in dependencies. */
            DEPENDENCY,
            /** Scan only project files. */
            PROJECT
        }

        /**
         * Unified index builder.
         */
        private fun buildIndex(project: Project, mode: ScanMode): TypeIndex {
            val allStructs = mutableMapOf<String, RsStructItem>()
            val allEnums = mutableMapOf<String, RsEnumItem>()

            val searchScope = when (mode) {
                ScanMode.DEPENDENCY -> GlobalSearchScope.everythingScope(project)
                ScanMode.PROJECT -> GlobalSearchScope.projectScope(project)
            }

            FileTypeIndex.getFiles(RsFileType, searchScope).forEach { virtualFile ->
                if (shouldIncludeFile(project, virtualFile, mode)) {
                    collectTypesFromFile(project, virtualFile, allStructs, allEnums)
                }
            }

            // Recursively collect nested types.
            collectNestedTypes(allStructs.values.toList(), allStructs, allEnums)

            // Build prefix -> struct reverse index.
            val prefixToStruct = buildPrefixToStructIndex(allStructs)

            return TypeIndex(allStructs, allEnums, prefixToStruct)
        }

        /**
         * Determine whether a file should be included in scanning.
         */
        private fun shouldIncludeFile(project: Project, virtualFile: VirtualFile, mode: ScanMode): Boolean {
            val path = virtualFile.path

            // Macro-expanded files are always excluded.
            if (FilePathValidator.isMacroExpanded(path)) {
                return false
            }

            return when (mode) {
                ScanMode.DEPENDENCY -> {
                    // Dependencies: scan only spring-related files and exclude project files.
                    FilePathValidator.isSpringRelated(path) && !FilePathValidator.isProjectFile(project, path)
                }
                ScanMode.PROJECT -> {
                    // Project: scan all project files (projectScope already restricts the search range).
                    true
                }
            }
        }

        /**
         * Collect structs and enums from a file.
         */
        private fun collectTypesFromFile(
            project: Project,
            virtualFile: VirtualFile,
            allStructs: MutableMap<String, RsStructItem>,
            allEnums: MutableMap<String, RsEnumItem>
        ) {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? RsFile ?: return

            PsiTreeUtil.findChildrenOfType(psiFile, RsStructItem::class.java).forEach { struct ->
                allStructs[getQualifiedName(struct)] = struct
            }

            PsiTreeUtil.findChildrenOfType(psiFile, RsEnumItem::class.java).forEach { enum ->
                allEnums[getQualifiedName(enum)] = enum
            }
        }

        /**
         * Merge two type indexes.
         */
        private fun mergeTypeIndex(dependency: TypeIndex, projectIdx: TypeIndex): TypeIndex {
            val allStructs = mutableMapOf<String, RsStructItem>()
            val allEnums = mutableMapOf<String, RsEnumItem>()
            val allPrefixes = mutableMapOf<String, MutableList<RsStructItem>>()

            // Struct/enum maps: project should override dependency by qualified name.
            allStructs.putAll(dependency.structs)
            allEnums.putAll(dependency.enums)
            allStructs.putAll(projectIdx.structs)
            allEnums.putAll(projectIdx.enums)

            // Prefix mapping: prefer project candidates first (user code wins by default),
            // then fall back to dependency candidates.
            projectIdx.prefixToStruct.forEach { (prefix, structs) ->
                allPrefixes.getOrPut(prefix) { mutableListOf() }.addAll(structs)
            }
            dependency.prefixToStruct.forEach { (prefix, structs) ->
                allPrefixes.getOrPut(prefix) { mutableListOf() }.addAll(structs)
            }

            return TypeIndex(allStructs, allEnums, allPrefixes)
        }

        /**
         * Build prefix -> struct reverse index.
         */
        private fun buildPrefixToStructIndex(
            allStructs: Map<String, RsStructItem>
        ): Map<String, List<RsStructItem>> {
            val prefixToStruct = mutableMapOf<String, MutableList<RsStructItem>>()

            allStructs.values.forEach { struct ->
                struct.name ?: return@forEach

                val prefix = RustAttributeUtils.extractConfigPrefix(struct)
                if (prefix == null) {
                    return@forEach
                }

                val isConfig = RustAttributeUtils.isConfigStruct(struct)
                if (!isConfig) {
                    return@forEach
                }

                val shouldInclude = CfgFeatureUtils.shouldIncludeStruct(struct)
                if (!shouldInclude) {
                    return@forEach
                }

                prefixToStruct.getOrPut(prefix) { mutableListOf() }.add(struct)
            }

            return prefixToStruct
        }

        /**
         * Recursively collect nested structs and enums.
         */
        private fun collectNestedTypes(
            structs: List<RsStructItem>,
            allStructs: MutableMap<String, RsStructItem>,
            allEnums: MutableMap<String, RsEnumItem>
        ) {
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque(structs)

            while (queue.isNotEmpty()) {
                val struct = queue.removeFirst()
                val structName = struct.name ?: continue

                if (structName in visited) continue
                visited.add(structName)

                val fields = StructFieldParser.parseStructFields(struct, includeDocumentation = false) { field ->
                    resolveFieldTypeInternal(field, allStructs)
                }

                fields.forEach { field ->
                    // Fast path: skip collection-like types based on type text.
                    val fieldTypeText = field.psiElement.typeReference?.text
                    if (fieldTypeText != null && RustTypeUtils.shouldSkipFieldType(fieldTypeText)) {
                        return@forEach
                    }

                    // Collect nested struct types.
                    val fieldType = resolveFieldTypeInternal(field.psiElement, allStructs)
                    if (fieldType != null) {
                        val fieldTypeName = fieldType.name
                        if (fieldTypeName != null && fieldTypeName !in visited) {
                            // Avoid collecting internal impl types of collection wrappers.
                            if (RustTypeUtils.shouldSkipNestedTypeCollection(fieldType)) {
                                return@forEach
                            }

                            val qualifiedName = getQualifiedName(fieldType)
                            allStructs[qualifiedName] = fieldType
                            queue.add(fieldType)
                        }
                    }

                    // Collect enum types.
                    field.psiElement.typeReference?.let { typeRef ->
                        RustTypeUtils.resolveEnumType(typeRef)?.let { enumType ->
                            val qualifiedName = getQualifiedName(enumType)
                            if (qualifiedName !in allEnums) {
                                allEnums[qualifiedName] = enumType
                            }
                        }
                    }
                }
            }
        }

        /**
         * Resolve a field type reference.
         *
         * Uses multiple strategies:
         * 1. Try rawType API first (most reliable for direct dependencies)
         * 2. If that fails, try reference.resolve()
         * 3. If still unresolved, search by type name in collected allStructs (supports transitive deps)
         */
        private fun resolveFieldTypeInternal(field: RsNamedFieldDecl, allStructs: Map<String, RsStructItem>): RsStructItem? {
            val typeRef = field.typeReference ?: return null
            val pathType = typeRef as? RsPathType ?: return null

            // Use rawType API to get type information.
            val rawType = typeRef.rawType

            val typeText = typeRef.text

            // Strategy 1: extract struct from rawType.
            var struct = extractStructFromType(rawType)

            // Strategy 2: if rawType fails, try reference.resolve().
            if (struct == null) {
                struct = resolveFieldTypeByReference(pathType)
            }

            // Strategy 3: if still unresolved, search by type name in collected allStructs (supports transitive deps).
            if (struct == null && allStructs is MutableMap) {
                struct = findStructByTypeText(typeText, allStructs)
            }

            if (struct == null) return null
            if (!isValidStruct(struct)) return null
            if (SerdeUtils.hasTransparentAttribute(struct)) return null

            val qualifiedName = getQualifiedName(struct)
            val result = allStructs[qualifiedName] ?: struct

            return result
        }

        /**
         * Resolve field type via reference.resolve() (fallback).
         */
        private fun resolveFieldTypeByReference(pathType: RsPathType): RsStructItem? {
            val typeArgumentList = pathType.path.typeArgumentList

            // Handle generic wrappers (e.g. Option<T>, Vec<T>).
            if (typeArgumentList != null && typeArgumentList.typeReferenceList.isNotEmpty()) {
                val innerTypeRef = typeArgumentList.typeReferenceList.first()
                val innerPathType = innerTypeRef as? RsPathType ?: return null

                val resolved = innerPathType.path.reference?.resolve()
                return resolved as? RsStructItem
            }

            // Non-generic type.
            val resolved = pathType.path.reference?.resolve()
            return resolved as? RsStructItem
        }

        /**
         * Search collected structs by type name (supports transitive dependencies).
         *
         * Examples:
         * - "aide::openapi::Info" -> find a struct with name "Info" and qualifiedName containing "aide::openapi"
         * - "Option<aide::openapi::Info>" -> extract inner type and then search
         *
         * If not found in allStructs, attempt to dynamically load from the filesystem.
         */
        private fun findStructByTypeText(typeText: String, allStructs: MutableMap<String, RsStructItem>): RsStructItem? {
            // Extract the actual type name (strip wrappers like Option<>, Vec<>).
            val actualType = RustTypeUtils.extractInnerTypeName(typeText)

            // If the type is path-qualified (e.g. aide::openapi::Info).
            if (actualType.contains("::")) {
                // Extract simple name (e.g. Info).
                val simpleName = actualType.substringAfterLast("::")
                // Extract path prefix (e.g. aide::openapi).
                val pathPrefix = actualType.substringBeforeLast("::")

                // First try to find a match in allStructs.
                var found = allStructs.entries.find { (qualifiedName, struct) ->
                    struct.name == simpleName && qualifiedName.contains(pathPrefix)
                }?.value

                // If not found, try to load it dynamically.
                if (found == null) {
                    found = tryLoadStructFromPackage(actualType, simpleName, pathPrefix, allStructs)
                }

                return found
            }

            // Simple type name: search by name.
            return allStructs.values.find { it.name == actualType }
        }

        /**
         * Try to dynamically load a struct from its package.
         *
         * @param actualType full type path (e.g. aide::openapi::Info)
         * @param simpleName simple name (e.g. Info)
         * @param pathPrefix path prefix (e.g. aide::openapi)
         * @param allStructs collected structs (will be updated)
         * @return found struct, or null if not found
         */
        private fun tryLoadStructFromPackage(
            actualType: String,
            simpleName: String,
            pathPrefix: String,
            allStructs: MutableMap<String, RsStructItem>
        ): RsStructItem? {
            // Extract package name (e.g. aide::openapi::Info -> aide).
            val packageName = pathPrefix.substringBefore("::")

            // We can't access the parser's project directly here because this lives in a companion object;
            // instead, grab the Project from any already-collected struct.
            val project = allStructs.values.firstOrNull()?.project ?: return null

            // Search all Rust files whose path contains the package name.
            val searchScope = GlobalSearchScope.everythingScope(project)
            FileTypeIndex.getFiles(RsFileType, searchScope).forEach { virtualFile ->
                val path = virtualFile.path

                // Skip macro-expanded files and standard library.
                if (FilePathValidator.isMacroExpanded(path) || FilePathValidator.isStandardLibrary(path)) {
                    return@forEach
                }

                // Check whether the file path contains the package name.
                if (path.contains("/$packageName-") || path.contains("/$packageName/")) {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? RsFile ?: return@forEach

                    // Collect structs in this file.
                    PsiTreeUtil.findChildrenOfType(psiFile, RsStructItem::class.java).forEach { struct ->
                        val qualifiedName = getQualifiedName(struct)
                        if (qualifiedName !in allStructs) {
                            allStructs[qualifiedName] = struct

                        }
                    }

                    // Collect enums as well.
                    PsiTreeUtil.findChildrenOfType(psiFile, RsEnumItem::class.java).forEach { enum ->
                        // Not handled here because we don't have an allEnums parameter.
                    }
                }
            }

            // Search again after collecting candidates.
            return allStructs.entries.find { (qualifiedName, struct) ->
                struct.name == simpleName && qualifiedName.contains(pathPrefix)
            }?.value
        }

        /**
         * Extract a struct item from a Ty.
         *
         * Supports:
         * - TyAdt: direct struct types
         * - generic wrappers (e.g. Option<T>, Vec<T>): extract inner type
         */
        private fun extractStructFromType(ty: org.rust.lang.core.types.ty.Ty): RsStructItem? {
            // If TyAdt, return the underlying item.
            if (ty is TyAdt) {
                val item = ty.item
                if (item is RsStructItem) {
                    return item
                }
            }

            // Handle generic wrappers (e.g. Option<T>, Vec<T>).
            if (ty is TyAdt && ty.typeArguments.isNotEmpty()) {
                val innerType = ty.typeArguments.first()
                if (innerType is TyAdt) {
                    val item = innerType.item
                    if (item is RsStructItem) {
                        return item
                    }
                }
            }

            return null
        }

        /**
         * Check whether a struct is valid (not standard library, not macro-expanded).
         *
         * Allows all non-stdlib and non-macro-expanded structs, including:
         * - spring-related dependencies (e.g. spring-web)
         * - other third-party dependencies (e.g. aide, serde)
         * - project structs
         */
        private fun isValidStruct(struct: RsStructItem): Boolean {
            val filePath = struct.containingFile.virtualFile?.path ?: return false
            // Only exclude macro-expanded files and standard library files.
            return !FilePathValidator.isMacroExpanded(filePath) && !FilePathValidator.isStandardLibrary(filePath)
        }
    }

    /**
     * Global struct/enum index data structure.
     */
    data class TypeIndex(
        val structs: Map<String, RsStructItem>,
        val enums: Map<String, RsEnumItem>,
        val prefixToStruct: Map<String, List<RsStructItem>>
    )

    // ==================== Public Query APIs ====================

    /**
     * Find all structs annotated with Configurable derive in the project.
     */
    fun findConfigurableStructs(): List<RsStructItem> {
        return getTypeIndex().structs.values.filter { RustAttributeUtils.isConfigStruct(it) }
    }

    /**
     * Get type index (cached) by merging dependency and project caches.
     */
    fun getTypeIndex(): TypeIndex {
        val dependencyIndex = getDependencyIndexCached(project)
        val projectIndex = getProjectIndexCached(project)
        return mergeTypeIndex(dependencyIndex, projectIndex)
    }

    /**
     * Get dependency type index.
     */
    fun getDependencyIndex(): TypeIndex = getDependencyIndexCached(project)

    /**
     * Get project type index.
     */
    fun getProjectIndex(): TypeIndex = getProjectIndexCached(project)

    /**
     * Resolve field type reference.
     */
    fun resolveFieldType(field: RsNamedFieldDecl, allStructs: Map<String, RsStructItem>): RsStructItem? {
        return resolveFieldTypeInternal(field, allStructs)
    }

    /**
     * Parse all variants of an enum.
     */
    fun parseEnumVariants(enumItem: RsEnumItem): List<String> {
        return SerdeUtils.parseEnumVariants(enumItem)
    }

    /**
     * Find enum by type name.
     */
    fun findEnumByTypeName(typeName: String): RsEnumItem? {
        val index = getTypeIndex()

        // Try direct lookup first.
        index.enums[typeName]?.let { return it }

        // Fallback: match by simple name.
        return index.enums.entries.find { (qualifiedName, _) ->
            qualifiedName.endsWith("::$typeName") || qualifiedName == typeName
        }?.value
    }

    /**
     * Get all enums.
     */
    fun getEnumCache(): Map<String, RsEnumItem> = getTypeIndex().enums

    /**
     * Find config struct by config_prefix.
     */
    fun findStructByPrefix(sectionName: String): RsStructItem? {
        return findStructByPrefix(sectionName, getTypeIndex())
    }

    fun findStructByPrefix(sectionName: String, typeIndex: TypeIndex): RsStructItem? {
        // First try the reverse index; return the first match.
        typeIndex.prefixToStruct[sectionName]?.firstOrNull()?.let { return it }

        // Special-case: logger -> LoggerConfig
        val structName = when (sectionName) {
            "logger" -> "LoggerConfig"
            else -> null
        }

        return structName?.let { typeIndex.structs[it] }
    }

    /**
     * Resolve nested structs.
     */
    fun resolveNestedStruct(parentPath: String): RsStructItem? {
        val parts = parentPath.split(".")
        val index = getTypeIndex()

        var currentStruct = index.prefixToStruct[parts[0]]?.firstOrNull() ?: return null

        for (i in 1 until parts.size) {
            val fieldName = parts[i]
            val fields = StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
                resolveFieldType(field, index.structs)
            }
            val field = fields.find { it.name == fieldName } ?: return null
            currentStruct = resolveFieldType(field.psiElement, index.structs) ?: return null
        }

        return currentStruct
    }

    /**
     * Get config fields for a section.
     *
     * @param sectionName section name (e.g. "web", "web.server")
     * @return list of config fields
     */
    fun getConfigFields(sectionName: String): List<ConfigFieldModel> {
        return getConfigFields(sectionName, getTypeIndex())
    }

    fun getConfigFields(sectionName: String, typeIndex: TypeIndex): List<ConfigFieldModel> {
        val parts = sectionName.split(".")

        val rootStruct = typeIndex.prefixToStruct[parts[0]]?.firstOrNull() ?: return emptyList()

        if (parts.size == 1) {
            // Top-level section: expand flatten fields.
            return expandFlattenFields(rootStruct, typeIndex)
        }

        var currentStruct = rootStruct
        for (i in 1 until parts.size) {
            val fieldName = parts[i]
            // Note: don't use expandFlattenFields here because we need to resolve the concrete field.
            val fields = StructFieldParser.parseStructFields(currentStruct, includeDocumentation = false) { field ->
                resolveFieldType(field, typeIndex.structs)
            }
            val field = fields.find { it.name == fieldName } ?: return emptyList()
            currentStruct = resolveFieldType(field.psiElement, typeIndex.structs) ?: return emptyList()
        }

        // Expand flatten fields for the final level as well.
        return expandFlattenFields(currentStruct, typeIndex)
    }

    /**
     * Expand flatten fields.
     *
     * If a field has #[serde(flatten)], return its nested fields instead of the field itself.
     */
    private fun expandFlattenFields(struct: RsStructItem, index: TypeIndex): List<ConfigFieldModel> {
        val fields = StructFieldParser.parseStructFields(struct, includeDocumentation = false) { field ->
            resolveFieldType(field, index.structs)
        }

        val result = mutableListOf<ConfigFieldModel>()

        fields.forEach { field ->
            if (field.isFlatten()) {
                // Check if the flatten field is a collection-like type.
                val fieldTypeText = field.psiElement.typeReference?.text
                if (fieldTypeText != null && RustTypeUtils.shouldSkipFieldType(fieldTypeText)) {
                    // For collections (e.g. IndexMap, HashMap), don't expand; keep the field itself.
                    result.add(field)
                } else {
                    // For structs, expand nested fields.
                    val flattenStruct = resolveFieldType(field.psiElement, index.structs)
                    if (flattenStruct != null) {
                        val flattenFields = StructFieldParser.parseStructFields(flattenStruct, includeDocumentation = false) { f ->
                            resolveFieldType(f, index.structs)
                        }
                        result.addAll(flattenFields)
                    }
                }
            } else {
                // Regular field.
                result.add(field)
            }
        }

        return result
    }



    /**
     * Find struct by type name.
     */
    fun findStructByTypeName(typeName: String): RsStructItem? {
        val structName = RustTypeUtils.extractStructName(typeName)
        val index = getTypeIndex()

        // Try direct lookup first.
        index.structs[structName]?.let { return it }

        // Fallback: match by simple name.
        return index.structs.entries.find { (qualifiedName, _) ->
            qualifiedName.endsWith("::$structName") || qualifiedName == structName
        }?.value
    }

    // ==================== Public Path Resolution ====================

    /**
     * Resolve a struct for a section name.
     *
     * Supports multi-level paths, e.g. "web.server" -> WebConfig -> ServerConfig.
     *
     * @param sectionName section name (e.g. "web", "web.server")
     * @return corresponding struct, or null if not found
     */
    fun resolveStructForSection(sectionName: String): RsStructItem? {
        val parts = sectionName.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        val typeIndex = getTypeIndex()
        var currentStruct = findStructByPrefix(parts[0]) ?: return null

        for (i in 1 until parts.size) {
            val part = parts[i]
            val field = findFieldInStruct(currentStruct, part) ?: return null
            currentStruct = resolveFieldType(field.psiElement, typeIndex.structs) ?: return null
        }

        return currentStruct
    }

    /**
     * Find a field by name in a struct.
     *
     * Supports:
     * - direct field name match
     * - match by serde rename
     * - recursive lookup through flatten fields
     *
     * @param struct struct
     * @param keyName field name
     * @return matching field model, or null if not found
     */
    fun findFieldInStruct(struct: RsStructItem, keyName: String): ConfigFieldModel? {
        val typeIndex = getTypeIndex()
        val fields = StructFieldParser.parseStructFields(struct, includeDocumentation = false) { field ->
            resolveFieldType(field, typeIndex.structs)
        }

        // Direct name match.
        fields.find { it.name == keyName }?.let { return it }
        // Match by serde rename.
        fields.find { it.getRenamedName() == keyName }?.let { return it }

        // Search in flatten fields.
        for (field in fields) {
            if (!field.isFlatten()) continue
            val flattenStruct = resolveFieldType(field.psiElement, typeIndex.structs) ?: continue
            val flattenFields = StructFieldParser.parseStructFields(flattenStruct, includeDocumentation = false) { nested ->
                resolveFieldType(nested, typeIndex.structs)
            }

            flattenFields.find { it.name == keyName }?.let { return it }
            flattenFields.find { it.getRenamedName() == keyName }?.let { return it }
        }

        return null
    }

    /**
     * Resolve field by key path.
     *
     * Supports dotted paths, e.g. "server.port": first resolve `server` in the struct, then resolve
     * `port` in the type of `server`.
     *
     * @param struct start struct
     * @param keyPath key path (e.g. "port" or "server.port")
     * @return matching field model, or null if not found
     */
    fun resolveFieldForKeyPath(struct: RsStructItem, keyPath: String): ConfigFieldModel? {
        val typeIndex = getTypeIndex()

        // Fast path: direct lookup (single-level field).
        findFieldInStruct(struct, keyPath)?.let { return it }

        // If there's no dot, we're done.
        if (!keyPath.contains(".")) return null

        // Multi-level path resolution.
        val parts = keyPath.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        var currentStruct = struct
        var currentField: ConfigFieldModel? = null

        for (i in parts.indices) {
            val part = parts[i]
            currentField = findFieldInStruct(currentStruct, part) ?: return null

            if (i == parts.lastIndex) {
                return currentField
            }

            currentStruct = resolveFieldType(currentField.psiElement, typeIndex.structs) ?: return null
        }

        return currentField
    }
}
