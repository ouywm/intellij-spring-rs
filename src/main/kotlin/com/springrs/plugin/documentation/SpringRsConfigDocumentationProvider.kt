package com.springrs.plugin.documentation

import com.intellij.codeEditor.printing.HTMLTextPainter
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.completion.SpringRsConfigFileUtil
import com.springrs.plugin.model.ConfigFieldModel
import com.springrs.plugin.parser.RustConfigStructParser
import com.springrs.plugin.utils.RustAttributeUtils
import com.springrs.plugin.utils.RustTypeUtils
import com.springrs.plugin.utils.SerdeUtils
import org.jetbrains.annotations.Nls
import org.rust.lang.core.psi.RsEnumVariant
import org.rust.lang.doc.RsDocRenderMode
import org.rust.lang.doc.documentationAsHtml
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.qualifiedName
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.TomlTableHeader
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind

/**
 * spring-rs TOML config documentation provider.
 *
 * Supports:
 * - section headers (e.g. [web] / [web.openapi])
 * - config fields (e.g. port / c.g / fields inside inline tables)
 *
 * Layout follows Spring Boot plugin style: title + description + type/default + possible values.
 */
class SpringRsConfigDocumentationProvider : AbstractDocumentationProvider() {

    private sealed class DocInfo {
        data class FieldDoc(
            val sectionName: String,
            val keyName: String,
            val field: ConfigFieldModel,
            val parser: RustConfigStructParser
        ) : DocInfo()

        data class SectionDoc(
            val sectionName: String,
            val struct: RsStructItem,
            val parser: RustConfigStructParser
        ) : DocInfo()

        data class EnumVariantDoc(
            val qualifiedPath: String,
            val variant: RsEnumVariant
        ) : DocInfo()
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        // If the cursor is on a value containing ${VAR}, let the env var provider handle it.
        if (isOnEnvVarValue(originalElement ?: element)) return null
        val docInfo = findDocInfo(element) ?: findDocInfo(originalElement) ?: return null
        return buildDocumentation(docInfo)
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? {
        if (isOnEnvVarValue(originalElement ?: element)) return null
        val docInfo = findDocInfo(element) ?: findDocInfo(originalElement) ?: return null
        return buildDocumentation(docInfo)
    }

    companion object {
        private val ENV_VAR_PATTERN = Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*(?::[^}]*)?\}""")
    }

    /**
     * Returns true if the element is inside (or is) a TOML string value that contains `${VAR}`.
     * In that case, we yield to [SpringRsEnvVarDocumentationProvider].
     */
    private fun isOnEnvVarValue(element: PsiElement?): Boolean {
        if (element == null) return false
        val literal = PsiTreeUtil.getParentOfType(element, TomlLiteral::class.java)
            ?: (element as? TomlLiteral)
            ?: return false
        if (literal.kind !is TomlLiteralKind.String) return false
        // Check if this literal is a VALUE (not a key).
        val parent = literal.parent
        if (parent is TomlKeyValue && parent.value == literal) {
            return ENV_VAR_PATTERN.containsMatchIn(literal.text)
        }
        return false
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val docInfo = findDocInfo(element) ?: findDocInfo(originalElement) ?: return null
        return when (docInfo) {
            is DocInfo.SectionDoc -> {
                val structName = docInfo.struct.name ?: SpringRsBundle.message("springrs.common.unknown")
                "[${docInfo.sectionName}] -> $structName"
            }
            is DocInfo.FieldDoc -> "${docInfo.keyName}: ${docInfo.field.type}"
            is DocInfo.EnumVariantDoc -> docInfo.qualifiedPath
        }
    }

    private fun findDocInfo(element: PsiElement?): DocInfo? {
        if (element == null) return null
        if (!SpringRsConfigFileUtil.isSpringRsConfigFile(element)) return null

        val parser = RustConfigStructParser(element.project)
        val typeIndex = parser.getTypeIndex()

        findSectionDoc(element, parser, typeIndex)?.let { return it }
        findEnumVariantDoc(element, parser, typeIndex)?.let { return it }
        return findFieldDoc(element, parser, typeIndex)
    }

    private fun findEnumVariantDoc(
        element: PsiElement,
        parser: RustConfigStructParser,
        typeIndex: RustConfigStructParser.TypeIndex
    ): DocInfo.EnumVariantDoc? {
        // Provide enum variant docs only on values, e.g. token_style = "Jwt".
        val literal = findParentTomlLiteral(element) ?: return null
        val keyValue = literal.parent as? TomlKeyValue ?: return null

        val keyName = keyValue.key.text ?: return null
        val rawValue = literal.text?.trim() ?: return null
        val tomlValue = rawValue.trim('"', '\'')

        // Inline table value, e.g. "info" in: cors = { level = "info" }
        val inlineTable = findParentInlineTable(keyValue)
        val field = if (inlineTable != null) {
            val parentKeyValue = inlineTable.parent as? TomlKeyValue ?: return null
            val table = parentKeyValue.parent as? TomlTable ?: return null
            val sectionName = table.header.key?.text ?: return null

            val parentKeyName = parentKeyValue.key.text ?: return null
            val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
            val parentField = parser.resolveFieldForKeyPath(sectionStruct, parentKeyName) ?: return null
            val parentStruct = parser.resolveFieldType(parentField.psiElement, typeIndex.structs) ?: return null
            parser.resolveFieldForKeyPath(parentStruct, keyName)
        } else {
            val table = keyValue.parent as? TomlTable ?: return null
            val sectionName = table.header.key?.text ?: return null

            val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
            parser.resolveFieldForKeyPath(sectionStruct, keyName)
        } ?: return null

        if (!field.isEnumType || field.enumTypeName == null) return null

        val enumItem = parser.findEnumByTypeName(field.enumTypeName) ?: return null
        val variant = resolveEnumVariant(enumItem, tomlValue) ?: return null

        val enumQualified = enumItem.qualifiedName ?: enumItem.name ?: return null
        val variantRustName = variant.identifier.text ?: return null
        val qualifiedPath = "$enumQualified::$variantRustName"
        return DocInfo.EnumVariantDoc(
            qualifiedPath = qualifiedPath,
            variant = variant
        )
    }

    private fun findParentTomlLiteral(element: PsiElement): org.toml.lang.psi.TomlLiteral? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < 8) {
            if (current is org.toml.lang.psi.TomlLiteral) return current
            // No need to search beyond table level.
            if (current is TomlTable) return null
            current = current.parent
            depth++
        }
        return null
    }

    private fun findSectionDoc(
        element: PsiElement,
        parser: RustConfigStructParser,
        typeIndex: RustConfigStructParser.TypeIndex
    ): DocInfo.SectionDoc? {
        val tableHeader = when (element) {
            is TomlTableHeader -> element
            is TomlKey -> if (element.parent is TomlTableHeader) element.parent as TomlTableHeader else null
            is TomlKeySegment -> {
                val key = element.parent as? TomlKey
                if (key?.parent is TomlTableHeader) key.parent as TomlTableHeader else null
            }
            else -> {
                var current: PsiElement? = element
                var depth = 0
                var foundHeader: TomlTableHeader? = null
                while (current != null && depth < 5) {
                    if (current is TomlTableHeader) {
                        foundHeader = current
                        break
                    }
                    current = current.parent
                    depth++
                }
                foundHeader
            }
        } ?: return null

        val sectionName = tableHeader.key?.text ?: return null
        val struct = parser.resolveStructForSection(sectionName) ?: return null
        return DocInfo.SectionDoc(sectionName, struct, parser)
    }

    private fun findFieldDoc(
        element: PsiElement,
        parser: RustConfigStructParser,
        typeIndex: RustConfigStructParser.TypeIndex
    ): DocInfo.FieldDoc? {
        val keyValue = when (element) {
            is TomlKey -> element.parent as? TomlKeyValue
            is TomlKeySegment -> element.parent?.parent as? TomlKeyValue
            is TomlKeyValue -> element
            else -> {
                var current: PsiElement? = element
                while (current != null && current !is TomlKeyValue) {
                    current = current.parent
                }
                current as? TomlKeyValue
            }
        } ?: return null

        val inlineTable = findParentInlineTable(keyValue)
        if (inlineTable != null) {
            return findFieldDocFromInlineTable(keyValue, inlineTable, parser, typeIndex)
        }

        val keyName = keyValue.key.text ?: return null
        val table = keyValue.parent as? TomlTable ?: return null
        val sectionName = table.header.key?.text ?: return null

        val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
        val field = parser.resolveFieldForKeyPath(sectionStruct, keyName) ?: return null
        return DocInfo.FieldDoc(sectionName, keyName, field, parser)
    }

    private fun findFieldDocFromInlineTable(
        keyValue: TomlKeyValue,
        inlineTable: TomlInlineTable,
        parser: RustConfigStructParser,
        typeIndex: RustConfigStructParser.TypeIndex
    ): DocInfo.FieldDoc? {
        val parentKeyValue = inlineTable.parent as? TomlKeyValue ?: return null
        val table = parentKeyValue.parent as? TomlTable ?: return null
        val sectionName = table.header.key?.text ?: return null

        val parentKeyName = parentKeyValue.key.text ?: return null
        val fieldName = keyValue.key.text ?: return null
        val fullKeyName = "$parentKeyName.$fieldName"

        val sectionStruct = parser.resolveStructForSection(sectionName) ?: return null
        val parentField = parser.resolveFieldForKeyPath(sectionStruct, parentKeyName) ?: return null
        val parentStruct = parser.resolveFieldType(parentField.psiElement, typeIndex.structs) ?: return null
        val field = parser.resolveFieldForKeyPath(parentStruct, fieldName) ?: return null

        return DocInfo.FieldDoc(sectionName, fullKeyName, field, parser)
    }

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

    @Nls
    private fun buildDocumentation(info: DocInfo): String {
        return when (info) {
            is DocInfo.FieldDoc -> buildFieldDocumentation(info)
            is DocInfo.SectionDoc -> buildSectionDocumentation(info)
            is DocInfo.EnumVariantDoc -> buildEnumVariantDocumentation(info)
        }
    }

    @Nls
    private fun buildSectionDocumentation(info: DocInfo.SectionDoc): String {
        val struct = info.struct
        val unknown = SpringRsBundle.message("springrs.common.unknown")
        val qualified = struct.qualifiedName ?: struct.name ?: unknown
        val modulePath = qualified.substringBeforeLast("::", qualified)
        val vis = struct.vis?.text?.trim().takeIf { !it.isNullOrBlank() }
        val signature = buildString {
            if (vis != null) append(vis).append(' ')
            append("struct ")
            append(struct.name ?: unknown)
        }

        val signatureHtml = highlightRustFragment(struct, signature)

        val docHtml = struct.documentationAsHtml(renderMode = RsDocRenderMode.QUICK_DOC_POPUP)
            ?: RustAttributeUtils.extractDocComments(struct)?.let { renderPlainTextAsHtml(it) }

        return buildString {
            definition(this) { def ->
                def.append(grayed("[${info.sectionName}] | $modulePath"))
                def.append("<br>")
                def.append(signatureHtml)
            }
            if (!docHtml.isNullOrBlank()) {
                append("\n")
                content(this) { it.append(docHtml) }
            }
        }
    }

    @Nls
    private fun buildFieldDocumentation(info: DocInfo.FieldDoc): String {
        val field = info.field
        val parser = info.parser
        val fieldDecl = field.psiElement
        val parentStruct = PsiTreeUtil.getParentOfType(fieldDecl, RsStructItem::class.java)
        val unknown = SpringRsBundle.message("springrs.common.unknown")
        val structQualified = if (parentStruct == null) {
            unknown
        } else {
            parentStruct.qualifiedName ?: parentStruct.name ?: unknown
        }
        val rustFieldName = fieldDecl.identifier.text
        val rustFieldType = fieldDecl.typeReference?.text ?: field.type
        val vis = fieldDecl.vis?.text?.trim().takeIf { !it.isNullOrBlank() }
        val signature = buildString {
            if (vis != null) append(vis).append(' ')
            append(rustFieldName)
            append(": ")
            append(rustFieldType)
        }

        val signatureHtml = highlightRustFragment(fieldDecl, signature)

        val docHtml = fieldDecl.documentationAsHtml(renderMode = RsDocRenderMode.QUICK_DOC_POPUP)
            ?: field.documentation?.let { renderPlainTextAsHtml(it) }

        val contentHtml = buildString {
            if (!docHtml.isNullOrBlank()) {
                append(docHtml)
            }

            // Make it obvious whether the value is backed by a struct/enum type (helps reading config files).
            appendTypeBlockIfAvailable(parser, field, fieldDecl)

            // Defaults are very important in config files; keep them even if doc already includes "Default: ...".
            field.defaultValue?.let { defaultValue ->
                append("<p><b>${SpringRsBundle.message("springrs.documentation.default.value")}</b> <code>")
                append(esc(defaultValue))
                append("</code></p>")
            }

            // For enums/bools, show candidate values (no external docs links).
            if (field.isEnumType && field.enumTypeName != null) {
                val enumItem = parser.findEnumByTypeName(field.enumTypeName)
                if (enumItem != null) {
                    val variants = parser.parseEnumVariants(enumItem)
                    if (variants.isNotEmpty()) {
                        append("<p><b>${SpringRsBundle.message("springrs.documentation.possible.values")}</b> ")
                        append(variants.joinToString(", ") { "<code>${esc(it)}</code>" })
                        append("</p>")
                    }
                }
            } else if (RustTypeUtils.extractTypeName(field.type) == "bool") {
                append("<p><b>${SpringRsBundle.message("springrs.documentation.possible.values")}</b> <code>true</code>, <code>false</code></p>")
            }
        }

        return buildString {
            definition(this) { def ->
                def.append(grayed("[${info.sectionName}] / ${info.keyName} | $structQualified"))
                def.append("<br>")
                def.append(signatureHtml)
            }
            if (contentHtml.isNotBlank()) {
                append("\n")
                content(this) { it.append(contentHtml) }
            }
        }
    }

    @Nls
    private fun buildEnumVariantDocumentation(info: DocInfo.EnumVariantDoc): String {
        val variantName = info.variant.identifier.text ?: SpringRsBundle.message("springrs.common.unknown")
        val signatureHtml = highlightRustFragment(info.variant, variantName)

        val docHtml = info.variant.documentationAsHtml(renderMode = RsDocRenderMode.QUICK_DOC_POPUP)
            ?: RustAttributeUtils.extractDocComments(info.variant)?.let { renderPlainTextAsHtml(it) }

        return buildString {
            definition(this) { def ->
                def.append(grayed(info.qualifiedPath))
                def.append("<br>")
                def.append(signatureHtml)
            }
            if (!docHtml.isNullOrBlank()) {
                append("\n")
                content(this) { it.append(docHtml) }
            }
        }
    }

    private fun highlightRustFragment(context: PsiElement, code: String): String {
        return try {
            val html = HTMLTextPainter.convertCodeFragmentToHTMLFragmentWithInlineStyles(context, code)
            stripOuterPre(html).trim().ifBlank { "<code>${esc(code)}</code>" }
        } catch (_: Throwable) {
            "<code>${esc(code)}</code>"
        }
    }

    private fun stripOuterPre(html: String): String {
        val start = html.indexOf("<pre")
        if (start == -1) return html
        val startEnd = html.indexOf('>', start)
        if (startEnd == -1) return html
        val end = html.lastIndexOf("</pre>")
        if (end == -1 || end <= startEnd) return html
        return html.substring(startEnd + 1, end)
    }

    private fun renderPlainTextAsHtml(doc: String): String {
        val normalized = doc.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isBlank()) return ""
        return "<p>${esc(normalized).replace("\n", "<br>")}</p>"
    }

    private inline fun definition(buffer: StringBuilder, block: (StringBuilder) -> Unit) {
        buffer.append(DocumentationMarkup.DEFINITION_START)
        block(buffer)
        buffer.append(DocumentationMarkup.DEFINITION_END)
    }

    private inline fun content(buffer: StringBuilder, block: (StringBuilder) -> Unit) {
        buffer.append(DocumentationMarkup.CONTENT_START)
        block(buffer)
        buffer.append(DocumentationMarkup.CONTENT_END)
    }

    private fun grayed(text: String): String = "<span style='color: #808080;'>${esc(text)}</span>"

    private fun StringBuilder.appendTypeBlockIfAvailable(
        parser: RustConfigStructParser,
        field: ConfigFieldModel,
        fieldDecl: org.rust.lang.core.psi.RsNamedFieldDecl
    ) {
        // Enum type
        if (field.isEnumType && field.enumTypeName != null) {
            val enumItem = parser.findEnumByTypeName(field.enumTypeName) ?: return
            val qualified = enumItem.qualifiedName ?: enumItem.name ?: return
            val modulePath = qualified.substringBeforeLast("::", qualified)
            val vis = enumItem.vis?.text?.trim().takeIf { !it.isNullOrBlank() }
            val signature = buildString {
                if (vis != null) append(vis).append(' ')
                append("enum ")
                append(enumItem.name ?: SpringRsBundle.message("springrs.common.unknown"))
            }
            val signatureHtml = highlightRustFragment(enumItem, signature)
            appendTypeBlock(modulePath, signatureHtml)
            return
        }

        // Struct type (only if we can resolve it; primitives/aliases will just skip).
        if (!field.isStructType) return
        val typeIndex = parser.getTypeIndex()
        val structItem = parser.resolveFieldType(fieldDecl, typeIndex.structs) ?: return
        val qualified = structItem.qualifiedName ?: structItem.name ?: return
        val modulePath = qualified.substringBeforeLast("::", qualified)
        val vis = structItem.vis?.text?.trim().takeIf { !it.isNullOrBlank() }
        val signature = buildString {
            if (vis != null) append(vis).append(' ')
            append("struct ")
            append(structItem.name ?: SpringRsBundle.message("springrs.common.unknown"))
        }
        val signatureHtml = highlightRustFragment(structItem, signature)
        appendTypeBlock(modulePath, signatureHtml)
    }

    private fun StringBuilder.appendTypeBlock(modulePath: String, signatureHtml: String) {
        append("<div style='margin-top: 8px;'>")
        append(grayed(modulePath))
        append("<br>")
        // Use <pre> here to preserve monospace without relying on DocumentationMarkup wrappers.
        append("<pre style='margin: 4px 0 0 0;'>")
        append(signatureHtml)
        append("</pre>")
        append("</div>")
    }

    private fun resolveEnumVariant(
        enumItem: org.rust.lang.core.psi.RsEnumItem,
        tomlValue: String
    ): org.rust.lang.core.psi.RsEnumVariant? {
        return SerdeUtils.resolveEnumVariant(enumItem, tomlValue)
    }

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)
}
