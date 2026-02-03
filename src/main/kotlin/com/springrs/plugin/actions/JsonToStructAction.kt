package com.springrs.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.springrs.plugin.SpringRsBundle
import org.rust.lang.RsFileType

/**
 * JSON to Rust struct action.
 *
 * Can be used in any file; for Rust files it also inserts required imports automatically.
 */
class JsonToStructAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        // Enable as long as there is an editor.
        e.presentation.isEnabledAndVisible = editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val isRustFile = psiFile?.fileType == RsFileType

        val dialog = JsonToStructDialog(project)

        // Prefill JSON from clipboard if possible.
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null)
            if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val clipboardText = contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                if (clipboardText != null && (clipboardText.trim().startsWith("{") || clipboardText.trim().startsWith("["))) {
                    dialog.setJsonContent(clipboardText)
                }
            }
        } catch (_: Exception) {
            // Ignore clipboard access failures.
        }

        if (dialog.showAndGet()) {
            val rustCode = dialog.getRustCode()
            if (rustCode.isNotBlank()) {
                // Save JSON to history.
                val jsonContent = dialog.getJsonContent()
                if (jsonContent.isNotBlank()) {
                    JsonToStructHistoryService.getInstance().addToHistory(jsonContent)
                }

                val requiredImports = if (isRustFile) dialog.getRequiredImports() else emptyList()
                val insertAtFileEnd = dialog.isInsertAtFileEnd()

                WriteCommandAction.runWriteCommandAction(project, SpringRsBundle.message("json.to.struct.command.name"), null, {
                    val document = editor.document

                    // 1) Read caret offset (before any edits).
                    val originalCaretOffset = editor.caretModel.offset

                    // 2) Insert imports only for Rust files.
                    var importInsertion: ImportInsertion? = null
                    if (isRustFile && requiredImports.isNotEmpty()) {
                        importInsertion = insertImportsAtTop(document, requiredImports, originalCaretOffset)
                    }

                    // 3) Choose insertion point.
                    // - caret: use the caret offset (adjusted if we inserted imports before it)
                    // - end: always append to the end of file (after imports were inserted)
                    val adjustedOffset = if (insertAtFileEnd) document.textLength
                    else importInsertion?.caretOffsetAfterImports ?: originalCaretOffset

                    // 4) Insert generated struct code at adjusted caret offset.
                    val insertText = if (adjustedOffset > 0 && adjustedOffset <= document.textLength &&
                        document.getText(TextRange(adjustedOffset - 1, adjustedOffset)) != "\n") {
                        "\n\n$rustCode\n"
                    } else {
                        "$rustCode\n"
                    }

                    document.insertString(adjustedOffset, insertText)
                    editor.caretModel.moveToOffset(adjustedOffset + insertText.length)
                })
            }
        }
    }

    /**
     * Insert required `use` statements near the top of the file.
     *
     * Rust crate-level inner attributes and inner doc comments must stay at the beginning of the file, so we
     * insert imports after them (and other non-doc headers), but before outer attributes/doc comments/items.
     *
     * @return insertion info, or null if nothing was inserted
     */
    private data class ImportInsertion(val caretOffsetAfterImports: Int)

    private data class TextEdit(val start: Int, val end: Int, val replacement: String)

    private fun insertImportsAtTop(
        document: com.intellij.openapi.editor.Document,
        imports: List<String>,
        originalCaretOffset: Int
    ): ImportInsertion? {
        val fileText = document.text

        val insertOffset = findRustImportsInsertionOffset(fileText)

        // Figure out the "imports section" (usually the first block of `use` statements after crate-level headers).
        val (_, existingUseStmts) = collectImportSectionUseStatements(fileText, insertOffset)

        // 1) Parse required imports and compute per-base ordered item list.
        val requiredOrderByBase = linkedMapOf<String, LinkedHashSet<String>>()
        for (useStatement in imports) {
            val spec = parseSimpleUseSpec(useStatement) ?: continue
            val ordered = requiredOrderByBase.getOrPut(spec.basePath) { linkedSetOf() }
            ordered.addAll(spec.items)
        }

        // 2) Compute missing items (avoid E0252 duplicate imports).
        val missingByBase = linkedMapOf<String, LinkedHashSet<String>>()
        val rawStatementsToInsert = mutableListOf<String>()

        for (useStatement in imports) {
            val spec = parseSimpleUseSpec(useStatement)
            if (spec == null) {
                // Fallback: best-effort dedup by full text check.
                if (!fileText.contains(useStatement)) rawStatementsToInsert.add(useStatement)
                continue
            }

            for (item in spec.items) {
                if (!isImportedViaUseStatement(fileText, spec.basePath, item)) {
                    missingByBase.getOrPut(spec.basePath) { linkedSetOf() }.add(item)
                }
            }
        }

        if (missingByBase.isEmpty() && rawStatementsToInsert.isEmpty()) return null

        // 3) Try to merge missing items into an existing `use base::...;` statement to keep imports compact.
        //    Example: existing `use serde::Deserialize;` + missing `Serialize`
        //          -> rewrite as `use serde::{Serialize, Deserialize};`
        val edits = mutableListOf<TextEdit>()

        val parsedExisting = existingUseStmts.mapNotNull { stmt ->
            parseExistingUseStatement(stmt.text)?.let { parsed ->
                parsed.copy(start = stmt.start, end = stmt.end)
            }
        }

        for ((basePath, missingItems) in missingByBase) {
            if (missingItems.isEmpty()) continue
            val requiredOrder = requiredOrderByBase[basePath].orEmpty()
            if (requiredOrder.isEmpty()) continue

            val candidate = parsedExisting.firstOrNull { existing ->
                existing.basePath == basePath &&
                    existing.items.isNotEmpty() &&
                    existing.items.none { it == "*" } &&
                    existing.items.any { it in requiredOrder }
            } ?: continue

            val union = linkedSetOf<String>().apply {
                addAll(candidate.items)
                addAll(missingItems)
            }

            // Build stable item order: required order first, then any remaining existing items.
            val orderedItems = buildList {
                for (i in requiredOrder) if (i in union) add(i)
                for (i in candidate.items) if (i !in this) add(i)
            }

            if (orderedItems.size <= candidate.items.size) continue // nothing to add

            val newStmt = buildString {
                append(candidate.indent)
                append(candidate.pubPrefix)
                append("use ")
                append(basePath)
                append("::{")
                append(orderedItems.joinToString(", "))
                append("};")
            }

            edits.add(TextEdit(candidate.start, candidate.end, newStmt))
            missingItems.clear()
        }

        // 4) Build insert text for remaining missing imports.
        val insertText = buildString {
            for ((basePath, items) in missingByBase) {
                if (items.isEmpty()) continue
                val requiredOrder = requiredOrderByBase[basePath].orEmpty()
                val orderedItems = if (requiredOrder.isNotEmpty()) {
                    requiredOrder.filter { it in items }
                } else {
                    items.toList()
                }

                if (orderedItems.size == 1) {
                    append("use ")
                    append(basePath)
                    append("::")
                    append(orderedItems.single())
                    append(";\n")
                } else {
                    append("use ")
                    append(basePath)
                    append("::{")
                    append(orderedItems.joinToString(", "))
                    append("};\n")
                }
            }

            for (raw in rawStatementsToInsert) {
                append(raw)
                append("\n")
            }

            if (isNotEmpty()) append("\n") // separate with an empty line
        }

        // 5) Apply text edits (replace) first, then insert at the original insert offset.
        //    Apply replacements in reverse order to keep offsets valid.
        for (edit in edits.sortedByDescending { it.start }) {
            document.replaceString(edit.start, edit.end, edit.replacement)
        }

        if (insertText.isNotEmpty()) {
            document.insertString(insertOffset, insertText)
        }

        // 6) Compute caret shift relative to original caret offset.
        val allEditsForCaret = buildList {
            addAll(edits)
            if (insertText.isNotEmpty()) add(TextEdit(insertOffset, insertOffset, insertText))
        }

        val caretAfterImports = computeCaretOffsetAfterEdits(originalCaretOffset, allEditsForCaret)
        return ImportInsertion(caretAfterImports)
    }

    private data class ImportSectionUseStmt(val start: Int, val end: Int, val text: String)

    private fun collectImportSectionUseStatements(
        fileText: String,
        startOffset: Int
    ): Pair<Int, List<ImportSectionUseStmt>> {
        val length = fileText.length
        var i = startOffset
        val stmts = mutableListOf<ImportSectionUseStmt>()

        while (i < length) {
            i = skipWhitespace(fileText, i)
            if (i >= length) break

            // Line comment (including doc comments).
            if (fileText.startsWith("//", i)) {
                i = skipLine(fileText, i)
                continue
            }

            // Block comment (including doc comments).
            if (fileText.startsWith("/*", i)) {
                i = skipBlockComment(fileText, i)
                continue
            }

            if (startsWithUseOrPubUse(fileText, i)) {
                val stmtStart = i
                val semi = fileText.indexOf(';', i)
                if (semi == -1) break
                val stmtEnd = semi + 1
                stmts.add(ImportSectionUseStmt(stmtStart, stmtEnd, fileText.substring(stmtStart, stmtEnd)))
                i = stmtEnd
                continue
            }

            break
        }

        return Pair(i, stmts)
    }

    private fun startsWithUseOrPubUse(text: String, start: Int): Boolean {
        if (text.startsWith("use", start) && (start + 3 >= text.length || text[start + 3].isWhitespace())) {
            return true
        }

        if (!text.startsWith("pub", start)) return false
        var i = start + 3

        // pub(crate) / pub(in ...) / pub(super) / pub(self)
        if (i < text.length && text[i] == '(') {
            var depth = 0
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
                i++
            }
        }

        // Require at least one whitespace after pub(...)
        if (i >= text.length || !text[i].isWhitespace()) return false
        while (i < text.length && text[i].isWhitespace()) i++

        return text.startsWith("use", i) && (i + 3 >= text.length || text[i + 3].isWhitespace())
    }

    private val USE_PREFIX_CAPTURE_REGEX =
        Regex("^(\\s*)((?:pub(?:\\([^)]*\\))?\\s+)?)use\\s+", setOf(RegexOption.DOT_MATCHES_ALL))

    private data class ParsedExistingUseStatement(
        val indent: String,
        val pubPrefix: String,
        val basePath: String,
        val items: List<String>,
        val start: Int = 0,
        val end: Int = 0
    )

    private fun parseExistingUseStatement(statementText: String): ParsedExistingUseStatement? {
        val m = USE_PREFIX_CAPTURE_REGEX.find(statementText) ?: return null
        val indent = m.groupValues[1]
        val pubPrefix = m.groupValues[2]

        var content = statementText.substring(m.range.last + 1).trim()
        if (!content.endsWith(";")) return null
        content = content.removeSuffix(";").trim().removePrefix("::")

        // Group import: foo::{a, b}
        val groupIdx = content.indexOf("::{")
        if (groupIdx >= 0) {
            val base = content.substring(0, groupIdx)
            val groupText = extractOuterBracedContent(content.substring(groupIdx + 2)) ?: return null
            val items = splitTopLevelComma(groupText)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.substringBefore(" as ").trim() }
                .filter { it != "self" }
            return ParsedExistingUseStatement(indent, pubPrefix, base, items)
        }

        val lastColon = content.lastIndexOf("::")
        if (lastColon < 0) return null
        val base = content.substring(0, lastColon)
        val itemSpec = content.substring(lastColon + 2).trim()
        val item = itemSpec.substringBefore(" as ").trim()
        return ParsedExistingUseStatement(indent, pubPrefix, base, listOf(item))
    }

    private fun computeCaretOffsetAfterEdits(originalCaretOffset: Int, edits: List<TextEdit>): Int {
        val normalized = edits.sortedWith(compareBy<TextEdit> { it.start }.thenByDescending { it.end })
        var caret = originalCaretOffset
        var deltaSoFar = 0
        for (edit in normalized) {
            val effectiveStart = edit.start + deltaSoFar
            val effectiveEnd = edit.end + deltaSoFar
            val oldLen = effectiveEnd - effectiveStart
            val newLen = edit.replacement.length

            if (effectiveStart <= caret) {
                caret += newLen - oldLen
            }
            deltaSoFar += newLen - oldLen
        }
        return caret
    }

    private fun findRustImportsInsertionOffset(fileText: String): Int {
        val length = fileText.length
        if (length == 0) return 0

        var i = 0

        // Skip UTF-8 BOM if present.
        if (fileText[0] == '\uFEFF') i++

        // Shebang must remain the very first line in script-like files.
        if (i + 2 < length && fileText[i] == '#' && fileText[i + 1] == '!' && fileText[i + 2] == '/') {
            i = skipLine(fileText, i)
        }

        while (i < length) {
            i = skipWhitespace(fileText, i)
            if (i >= length) break

            // Inner doc comment.
            if (fileText.startsWith("//!", i)) {
                i = skipLine(fileText, i)
                continue
            }

            // Outer doc comment - stop before it so it stays attached to the following item.
            if (fileText.startsWith("///", i)) break

            // Non-doc line comment.
            if (fileText.startsWith("//", i)) {
                i = skipLine(fileText, i)
                continue
            }

            // Inner doc block comment.
            if (fileText.startsWith("/*!", i)) {
                i = skipBlockComment(fileText, i)
                continue
            }

            // Outer doc block comment - stop before it so it stays attached to the following item.
            if (fileText.startsWith("/**", i)) break

            // Non-doc block comment.
            if (fileText.startsWith("/*", i)) {
                i = skipBlockComment(fileText, i)
                continue
            }

            // Inner attribute: #![...]
            if (fileText.startsWith("#!", i) && isInnerAttributeStart(fileText, i)) {
                i = skipInnerAttribute(fileText, i)
                continue
            }

            // Outer attribute/doc/item begins - insert before it.
            break
        }

        return i
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun skipLine(text: String, start: Int): Int {
        val nl = text.indexOf('\n', start)
        return if (nl == -1) text.length else nl + 1
    }

    /**
     * Rust block comments can be nested.
     */
    private fun skipBlockComment(text: String, start: Int): Int {
        if (!text.startsWith("/*", start)) return start
        var i = start
        var depth = 0
        while (i + 1 < text.length) {
            if (text[i] == '/' && text[i + 1] == '*') {
                depth++
                i += 2
                continue
            }
            if (text[i] == '*' && text[i + 1] == '/') {
                depth--
                i += 2
                if (depth <= 0) return i
                continue
            }
            i++
        }
        return text.length
    }

    private fun isInnerAttributeStart(text: String, offset: Int): Boolean {
        if (!text.startsWith("#!", offset)) return false
        var i = offset + 2
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == '['
    }

    /**
     * Skips an inner attribute starting at [start] ("#![...]" potentially spanning multiple lines).
     */
    private fun skipInnerAttribute(text: String, start: Int): Int {
        if (!text.startsWith("#!", start)) return start
        var i = start + 2
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '[') return start

        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '[' -> {
                    depth++
                    i++
                }
                ']' -> {
                    depth--
                    i++
                    if (depth <= 0) return i
                }
                else -> i++
            }
        }
        return text.length
    }

    /**
     * Check whether file text already contains the specified import.
     */
    private fun isImportPresentInText(useStatement: String, fileText: String): Boolean {
        val spec = parseSimpleUseSpec(useStatement) ?: return false
        return spec.items.all { item -> isImportedViaUseStatement(fileText, spec.basePath, item) }
    }

    private data class SimpleUseSpec(val basePath: String, val items: List<String>)

    private fun parseSimpleUseSpec(useStatement: String): SimpleUseSpec? {
        val trimmed = useStatement.trim()
        if (!trimmed.startsWith("use ") || !trimmed.endsWith(";")) return null

        val content = trimmed
            .removePrefix("use ")
            .removeSuffix(";")
            .trim()
            .removePrefix("::")

        // Group import: foo::{a, b}
        val groupIdx = content.indexOf("::{")
        if (groupIdx >= 0) {
            val base = content.substring(0, groupIdx)
            val groupText = extractOuterBracedContent(content.substring(groupIdx + 2)) ?: return null
            val items = splitTopLevelComma(groupText)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.substringBefore(" as ").trim() }
                .filter { it != "self" }
            return SimpleUseSpec(base, items)
        }

        val lastColon = content.lastIndexOf("::")
        if (lastColon < 0) return null

        val base = content.substring(0, lastColon)
        val itemSpec = content.substring(lastColon + 2).trim()
        val item = itemSpec.substringBefore(" as ").trim()
        return SimpleUseSpec(base, listOf(item))
    }

    private val USE_STATEMENT_REGEX =
        Regex("(?m)^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?use\\s+[^;]*;")

    private val USE_PREFIX_REGEX =
        Regex("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?use\\s+")

    private fun isImportedViaUseStatement(fileText: String, basePath: String, item: String): Boolean {
        for (m in USE_STATEMENT_REGEX.findAll(fileText)) {
            val content = m.value
                .trim()
                .removeSuffix(";")
                .replace(USE_PREFIX_REGEX, "")
                .trim()
                .removePrefix("::")

            if (!content.startsWith("$basePath::")) continue
            val remainder = content.removePrefix("$basePath::").trimStart()

            if (remainder.startsWith("*")) return true

            if (remainder.startsWith("{")) {
                val groupText = extractOuterBracedContent(remainder) ?: continue
                val items = splitTopLevelComma(groupText)
                for (rawItem in items) {
                    val i = rawItem.trim()
                    if (i.isEmpty() || i == "self") continue
                    val first = i.substringBefore(" as ").trim()
                    if (first == "*" || first == item) return true
                }
                continue
            }

            val direct = remainder.substringBefore(" as ").trim()
            if (direct == "*" || direct == item) return true
        }

        return false
    }

    private fun extractOuterBracedContent(text: String): String? {
        if (!text.startsWith("{")) return null
        var depth = 0
        for (i in text.indices) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(1, i)
                }
            }
        }
        return null
    }

    private fun splitTopLevelComma(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0

        for (i in text.indices) {
            when (val ch = text[i]) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    result.add(text.substring(start, i))
                    start = i + 1
                }
                else -> Unit
            }
        }

        if (start <= text.length) {
            result.add(text.substring(start))
        }

        return result
    }
}
