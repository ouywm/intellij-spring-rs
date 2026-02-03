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
import com.springrs.plugin.model.ConfigFieldModel
import com.springrs.plugin.parser.RustConfigStructParser
import com.springrs.plugin.utils.SerdeUtils
import org.rust.lang.core.psi.RsEnumItem
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind

/**
 * Reference for enum-like config values:
 *
 * `token_style = "Jwt"` -> resolves to Rust enum variant `TokenStyle::Jwt`
 *
 * Works for plain values, optional values and vec-of-enum values (array elements).
 */
class SpringRsTomlEnumValueReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? TomlLiteral ?: return PsiReference.EMPTY_ARRAY
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(literal)) return PsiReference.EMPTY_ARRAY
        if (DumbService.isDumb(literal.project)) return PsiReference.EMPTY_ARRAY

        // Only string literals can be enum variants in spring-rs config
        if (literal.kind !is TomlLiteralKind.String) return PsiReference.EMPTY_ARRAY

        // Don't create a reference for normal strings. Otherwise the IDE will show
        // "Cannot resolve symbol" on every string literal that doesn't map to an enum.
        val keyValue = PsiTreeUtil.getParentOfType(literal, TomlKeyValue::class.java) ?: return PsiReference.EMPTY_ARRAY
        val keyName = keyValue.key.text ?: return PsiReference.EMPTY_ARRAY

        val parser = RustConfigStructParser(literal.project)
        val field = resolveFieldForValueContext(parser, keyValue, keyName)
            ?: return PsiReference.EMPTY_ARRAY

        if (!field.isEnumType || field.enumTypeName.isNullOrBlank()) return PsiReference.EMPTY_ARRAY
        return arrayOf(SpringRsTomlEnumValueReference(literal))
    }

    /**
     * Resolve the field for the current value context.
     */
    private fun resolveFieldForValueContext(
        parser: RustConfigStructParser,
        keyValue: TomlKeyValue,
        keyName: String
    ): ConfigFieldModel? {
        // Inline table: `outer = { inner = "EnumValue" }`
        val inlineTable = PsiTreeUtil.getParentOfType(keyValue, TomlInlineTable::class.java)
        if (inlineTable != null) {
            val outerKeyValue = inlineTable.parent as? TomlKeyValue ?: return null
            val table = outerKeyValue.parent as? TomlTable ?: return null
            val sectionName = table.header.key?.text ?: return null

            val outerKeyName = outerKeyValue.key.text ?: return null
            val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
            val outerField = parser.resolveFieldForKeyPath(sectionStruct, outerKeyName) ?: return null
            val outerStruct = parser.resolveFieldType(outerField.psiElement, parser.getTypeIndex().structs) ?: return null

            return parser.resolveFieldForKeyPath(outerStruct, keyName)
        }

        // Normal table key-value
        val table = PsiTreeUtil.getParentOfType(keyValue, TomlTable::class.java) ?: return null
        val sectionName = table.header.key?.text ?: return null

        val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
        return parser.resolveFieldForKeyPath(sectionStruct, keyName)
    }
}

private class SpringRsTomlEnumValueReference(
    element: TomlLiteral
) : PsiReferenceBase<TomlLiteral>(element) {

    override fun resolve(): PsiElement? {
        val element = element
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(element)) return null
        if (DumbService.isDumb(element.project)) return null

        val value = (element.kind as? TomlLiteralKind.String)?.value ?: return null
        if (value.isBlank()) return null

        val keyValue = PsiTreeUtil.getParentOfType(element, TomlKeyValue::class.java) ?: return null
        val keyName = keyValue.key.text ?: return null

        val parser = RustConfigStructParser(element.project)

        val field = resolveFieldForValueContext(parser, keyValue, keyName) ?: return null
        if (!field.isEnumType || field.enumTypeName.isNullOrBlank()) return null

        val enumItem = parser.findEnumByTypeName(field.enumTypeName) ?: return null
        val variant = resolveEnumVariant(enumItem, value)
        // If the value doesn't match a variant, still resolve to the enum itself:
        // - avoids IDE "Cannot resolve symbol" diagnostics
        // - provides a useful navigation target (enum definition)
        return variant ?: enumItem
    }

    override fun calculateDefaultRangeInElement(): TextRange = TextRange.from(0, element.textLength)

    private fun resolveEnumVariant(enumItem: RsEnumItem, tomlValue: String): org.rust.lang.core.psi.RsEnumVariant? {
        return SerdeUtils.resolveEnumVariant(enumItem, tomlValue)
    }

    /**
     * Resolve the field for the current value context.
     */
    private fun resolveFieldForValueContext(
        parser: RustConfigStructParser,
        keyValue: TomlKeyValue,
        keyName: String
    ): ConfigFieldModel? {
        // Inline table: `outer = { inner = "EnumValue" }`
        val inlineTable = PsiTreeUtil.getParentOfType(keyValue, TomlInlineTable::class.java)
        if (inlineTable != null) {
            val outerKeyValue = inlineTable.parent as? TomlKeyValue ?: return null
            val table = outerKeyValue.parent as? TomlTable ?: return null
            val sectionName = table.header.key?.text ?: return null

            val outerKeyName = outerKeyValue.key.text ?: return null
            val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
            val outerField = parser.resolveFieldForKeyPath(sectionStruct, outerKeyName) ?: return null
            val outerStruct = parser.resolveFieldType(outerField.psiElement, parser.getTypeIndex().structs) ?: return null

            return parser.resolveFieldForKeyPath(outerStruct, keyName)
        }

        // Normal table key-value
        val table = PsiTreeUtil.getParentOfType(keyValue, TomlTable::class.java) ?: return null
        val sectionName = table.header.key?.text ?: return null

        val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
        return parser.resolveFieldForKeyPath(sectionStruct, keyName)
    }
}
