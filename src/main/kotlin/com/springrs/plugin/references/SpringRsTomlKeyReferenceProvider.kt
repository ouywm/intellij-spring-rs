package com.springrs.plugin.references

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.parser.RustConfigStructParser
import com.springrs.plugin.utils.CargoUtils
import com.springrs.plugin.utils.SpringRsConstants
import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsStructItem
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.TomlTableHeader

/**
 * References for TOML key segments:
 * - `[section]` / `[a.b]` -> corresponding Rust struct (resolved by config_prefix and nesting)
 * - `key = ...` / `a.b.c = ...` -> corresponding Rust field decl
 * - inline tables: `k = { inner = 1 }` -> inner keys resolve against the field struct type
 *
 * Note: Navigation prefers the current crate; if the current crate doesn't contain the config
 * struct, we fall back to candidates from the current Cargo package + its dependency graph.
 */
class SpringRsTomlKeyReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val keySegment = element as? TomlKeySegment ?: return PsiReference.EMPTY_ARRAY
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(keySegment)) return PsiReference.EMPTY_ARRAY
        if (DumbService.isDumb(keySegment.project)) return PsiReference.EMPTY_ARRAY

        // Avoid creating references on completion dummy identifiers
        if (keySegment.text.contains(SpringRsConstants.INTELLIJ_COMPLETION_DUMMY)) return PsiReference.EMPTY_ARRAY

        val key = keySegment.parent as? TomlKey ?: return PsiReference.EMPTY_ARRAY
        val owner = key.parent

        return when (owner) {
            is TomlTableHeader,
            is TomlKeyValue -> arrayOf(SpringRsTomlKeySegmentReference(keySegment))

            else -> PsiReference.EMPTY_ARRAY
        }
    }
}

private class SpringRsTomlKeySegmentReference(
    element: TomlKeySegment
) : PsiReferenceBase<TomlKeySegment>(element) {

    override fun resolve(): PsiElement? {
        val element = element
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(element)) return null
        if (DumbService.isDumb(element.project)) return null

        val key = element.parent as? TomlKey ?: return null
        val owner = key.parent

        val parser = RustConfigStructParser(element.project)

        // Resolve the crate root for the current TOML file.
        val tomlFilePath = element.containingFile?.virtualFile?.path
        val crateRoot = if (tomlFilePath != null) {
            CargoUtils.findCrateRootForFile(element.project, tomlFilePath)
        } else null

        return when (owner) {
            is TomlTableHeader -> {
                val headerKey = owner.key ?: return null
                val segments = PsiTreeUtil.getChildrenOfTypeAsList(headerKey, TomlKeySegment::class.java)
                val targetIndex = segments.indexOf(element)
                val sectionName = if (targetIndex == -1) {
                    headerKey.text
                } else {
                    segments
                        .take(targetIndex + 1)
                        .joinToString(".") { it.name ?: it.text }
                }
                resolveStructForSectionInCrate(parser, sectionName, crateRoot)
            }

            is TomlKeyValue -> resolveFieldForKeySegment(parser, owner, element, crateRoot)

            else -> null
        }
    }

    override fun calculateDefaultRangeInElement(): TextRange = TextRange.from(0, element.textLength)

    /**
     * Resolve the struct for a TOML section.
     *
     * Strategy:
     * 1) Prefer matches in the current crate
     * 2) If not found, fall back to matches from the current Cargo package + its dependency graph
     */
    private fun resolveStructForSectionInCrate(
        parser: RustConfigStructParser,
        sectionName: String,
        crateRoot: String?
    ): RsStructItem? {
        val project = element.project
        val index = parser.getTypeIndex()
        val cargoScope = element.containingFile?.virtualFile?.let { CargoUtils.getCargoScopeForFile(project, it) }

        // If we can resolve the crate root, search in the current crate first.
        if (crateRoot != null) {
            val structInCurrentCrate = findStructInCrate(parser, sectionName, crateRoot)
            if (structInCurrentCrate != null) {
                return structInCurrentCrate
            }
        }

        // Not found in the current crate: try other candidates (workspace crates/dependencies).
        val parts = sectionName.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        val rootPrefix = parts[0]
        val structsWithPrefixAll = index.prefixToStruct[rootPrefix] ?: emptyList()

        val allowedPackages = cargoScope?.allowedPackageNames
        val currentPackageName = cargoScope?.currentPackageName

        fun pkgNameOf(struct: RsStructItem): String? {
            val vf = struct.containingFile?.virtualFile ?: return null
            return CargoUtils.findPackageNameForFile(project, vf)
        }

        // Limit to current package + its dependency graph to avoid cross-crate collisions in workspaces.
        val structsWithPrefix = structsWithPrefixAll
            .filter { struct ->
                val pkgName = pkgNameOf(struct)
                if (allowedPackages == null) {
                    true
                } else {
                    pkgName != null && pkgName in allowedPackages
                }
            }
            .sortedWith(
                compareBy<RsStructItem> { struct ->
                    val pkgName = pkgNameOf(struct)
                    when {
                        currentPackageName != null && pkgName == currentPackageName -> 0
                        pkgName != null -> 1
                        else -> 2
                    }
                }.thenBy { pkgNameOf(it) ?: "" }
                    .thenBy { it.containingFile?.virtualFile?.path ?: "" }
            )

        for (candidate in structsWithPrefix) {
            var currentStruct = candidate
            var ok = true
            for (i in 1 until parts.size) {
                val part = parts[i]
                val field = parser.findFieldInStruct(currentStruct, part)
                if (field == null) {
                    ok = false
                    break
                }

                val nextStruct = parser.resolveFieldType(field.psiElement, index.structs)
                if (nextStruct == null) {
                    ok = false
                    break
                }
                currentStruct = nextStruct
            }
            if (ok) return currentStruct
        }

        return null
    }

    /**
     * Find the struct for a TOML section within the given crate.
     */
    private fun findStructInCrate(
        parser: RustConfigStructParser,
        sectionName: String,
        crateRoot: String
    ): RsStructItem? {
        val index = parser.getTypeIndex()

        // Find matching structs in prefixToStruct.
        val parts = sectionName.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        // Resolve the root section struct.
        val rootPrefix = parts[0]

        // Strict match: struct must be in the current crate.
        val normalizedCrateRoot = crateRoot.trimEnd('/')

        // Collect all structs that match the prefix.
        val structsWithPrefix = index.prefixToStruct[rootPrefix] ?: emptyList()

        val candidates = structsWithPrefix.filter { struct ->
            val structPath = struct.containingFile?.virtualFile?.path ?: return@filter false

            // Ensure the struct is in the current crate (path must start with crateRoot/).
            val matches = structPath.startsWith("$normalizedCrateRoot/")

            matches
        }.sortedBy { it.containingFile?.virtualFile?.path ?: "" }

        if (candidates.isEmpty()) return null

        // There should be only one match; if there are multiple, return the first one.
        var currentStruct = candidates.first()

        // If the section is nested, resolve field types step by step.
        for (i in 1 until parts.size) {
            val part = parts[i]
            val field = parser.findFieldInStruct(currentStruct, part) ?: return null
            currentStruct = parser.resolveFieldType(field.psiElement, index.structs) ?: return null
        }

        return currentStruct
    }

    private fun resolveFieldForKeySegment(
        parser: RustConfigStructParser,
        keyValue: TomlKeyValue,
        segment: TomlKeySegment,
        crateRoot: String?
    ): RsNamedFieldDecl? {
        val key = keyValue.key
        val segments = PsiTreeUtil.getChildrenOfTypeAsList(key, TomlKeySegment::class.java)
        val targetIndex = segments.indexOf(segment)
        if (targetIndex == -1) return null

        val keyParts = segments
            .map { it.name ?: it.text }
            .filter { it.isNotBlank() }
        if (keyParts.isEmpty() || targetIndex >= keyParts.size) return null

        // Inline table: `outer = { inner = ... }`
        val inlineTable = keyValue.parent as? TomlInlineTable
        if (inlineTable != null) {
            val outerKeyValue = inlineTable.parent as? TomlKeyValue ?: return null
            val table = outerKeyValue.parent as? TomlTable ?: return null
            val sectionName = table.header.key?.text ?: return null

            val outerKeyParts = PsiTreeUtil.getChildrenOfTypeAsList(outerKeyValue.key, TomlKeySegment::class.java)
                .map { it.name ?: it.text }
                .filter { it.isNotBlank() }
            if (outerKeyParts.isEmpty()) return null

            val sectionStruct = resolveStructForSectionInCrate(parser, sectionName, crateRoot) ?: return null
            val outerFieldDecl = resolveFieldDeclByPath(parser, sectionStruct, outerKeyParts, outerKeyParts.lastIndex)
                ?: return null
            val outerStruct = parser.resolveFieldType(outerFieldDecl, parser.getTypeIndex().structs) ?: return null

            return resolveFieldDeclByPath(parser, outerStruct, keyParts, targetIndex)
        }

        // Normal key inside a table
        val table = keyValue.parent as? TomlTable ?: return null
        val sectionName = table.header.key?.text ?: return null

        val sectionStruct = resolveStructForSectionInCrate(parser, sectionName, crateRoot) ?: return null
        return resolveFieldDeclByPath(parser, sectionStruct, keyParts, targetIndex)
    }

    private fun resolveFieldDeclByPath(
        parser: RustConfigStructParser,
        struct: RsStructItem,
        keyParts: List<String>,
        targetIndex: Int
    ): RsNamedFieldDecl? {
        var currentStruct = struct

        for (i in 0..targetIndex) {
            val part = keyParts.getOrNull(i) ?: return null
            val field = parser.findFieldInStruct(currentStruct, part) ?: return null

            if (i == targetIndex) {
                return field.psiElement
            }

            val nextStruct = parser.resolveFieldType(field.psiElement, parser.getTypeIndex().structs) ?: return null
            currentStruct = nextStruct
        }

        return null
    }
}
