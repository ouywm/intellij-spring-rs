package com.springrs.plugin.utils

import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psi.kind
import org.rust.lang.core.types.consts.asBool
import org.rust.lang.core.types.consts.asLong
import org.rust.lang.doc.documentation
import org.rust.lang.utils.evaluation.evaluate

/**
 * Rust attribute utilities.
 *
 * Helper methods for parsing Rust attributes.
 */
object RustAttributeUtils {

    /**
     * Returns true if the struct is a config struct.
     *
     * A config struct is one that:
     * 1. has `#[derive(Configurable)]`, or
     * 2. has `#[config_prefix = "xxx"]`
     */
    fun isConfigStruct(struct: RsStructItem): Boolean {
        return hasConfigurableDerive(struct) || hasConfigPrefix(struct)
    }

    /**
     * Returns true if the struct has the `Configurable` derive.
     *
     * Example: `#[derive(Debug, Configurable, Deserialize)]`
     */
    fun hasConfigurableDerive(struct: RsStructItem): Boolean {
        // Use raw PSI: iterate `outerAttrList`.
        return struct.outerAttrList
            .map { it.metaItem }
            .filter { it.name == "derive" }
            .any { deriveAttr ->
                deriveAttr.metaItemArgs?.metaItemList
                    ?.any { it.name == SpringRsConstants.DERIVE_CONFIGURABLE } == true
            }
    }

    /**
     * Returns true if the struct has a `config_prefix` attribute.
     *
     * Example: `#[config_prefix = "web"]`
     */
    fun hasConfigPrefix(struct: RsStructItem): Boolean {
        return extractConfigPrefix(struct) != null
    }

    /**
     * Extracts the struct's `config_prefix` attribute value.
     *
     * Supports two forms:
     * 1. Attribute form: `#[config_prefix = "my-plugin"]`
     * 2. Manual impl form: `impl Configurable for StructName { fn config_prefix() -> &'static str { "my-plugin" } }`
     *
     * Example:
     * ```rust
     * #[config_prefix = "my-plugin"]
     * struct MyConfig { }
     * ```
     * Returns `"my-plugin"`.
     *
     * @param struct Rust struct
     * @return `config_prefix` value, or null if absent
     */
    fun extractConfigPrefix(struct: RsStructItem): String? {
        // Approach 1: extract from the attribute via raw PSI: `#[config_prefix = "xxx"]`.
        for (attr in struct.outerAttrList) {
            val meta = attr.metaItem
            if (meta.name == SpringRsConstants.ATTR_CONFIG_PREFIX) {
                // Try reading `litExpr` directly.
                val litExpr = meta.litExpr
                if (litExpr != null) {
                    return litExpr.stringValue
                }

                // Try reading after `=` (the `#[config_prefix = "xxx"]` form).
                val eq = meta.eq
                if (eq != null) {
                    // Find the expression after `=`.
                    var sibling = eq.nextSibling
                    while (sibling != null) {
                        if (sibling is org.rust.lang.core.psi.RsLitExpr) {
                            return sibling.stringValue
                        }
                        sibling = sibling.nextSibling
                    }
                }

                // Fallback: extract from text.
                val attrText = attr.text
                val regex = Regex("""config_prefix\s*=\s*"([^"]+)"""")
                val match = regex.find(attrText)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        }

        // Approach 2: extract from a manual `Configurable` impl.
        return extractConfigPrefixFromImpl(struct)
    }

    /**
     * Extracts `config_prefix` from a manual `Configurable` trait implementation.
     */
    private fun extractConfigPrefixFromImpl(struct: RsStructItem): String? {
        val structName = struct.name ?: return null
        val containingFile = struct.containingFile as? org.rust.lang.core.psi.RsFile ?: return null

        val impls = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(containingFile, org.rust.lang.core.psi.RsImplItem::class.java)

        val configurableImpl = impls.find { impl ->
            val traitRef = impl.traitRef
            val typeRef = impl.typeReference
            val isConfigurable = traitRef?.path?.referenceName == SpringRsConstants.DERIVE_CONFIGURABLE
            val isForThisStruct = typeRef?.text == structName
            isConfigurable && isForThisStruct
        } ?: return null

        val functions = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(configurableImpl, org.rust.lang.core.psi.RsFunction::class.java)
        val configPrefixFn = functions.find { it.identifier.text == SpringRsConstants.CONFIG_PREFIX_FUNCTION } ?: return null

        val blockExpr = configPrefixFn.block
        if (blockExpr != null) {
            val litExprs = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(blockExpr, org.rust.lang.core.psi.RsLitExpr::class.java)
            val stringLit = litExprs.firstOrNull { it.kind is org.rust.lang.core.psi.RsLiteralKind.String }
            if (stringLit != null) {
                return stringLit.stringValue
            }
        }

        return null
    }

    /**
     * Extracts default value function name from `serde` attributes.
     *
     * Extracts the function name from `#[serde(default = "function_name")]`.
     *
     * Examples:
     * - `#[serde(default = "default_port")]` -> `"default_port"`
     * - `#[serde(default)]` -> null
     */
    fun extractDefaultValue(field: RsNamedFieldDecl): String? {
        return SerdeUtils.extractSerdeSubAttribute(field, SpringRsConstants.SERDE_DEFAULT)
    }

    /**
     * Returns true if the field has `serde(flatten)`.
     *
     * Example: `#[serde(flatten)]`
     */
    fun hasSerdeFlattened(field: RsNamedFieldDecl): Boolean {
        return SerdeUtils.hasSerdeSubAttribute(field, SpringRsConstants.SERDE_FLATTEN)
    }

    /**
     * Extracts the `serde(rename = "...")` value from a field.
     *
     * Extracts the rename value from `#[serde(rename = "new_name")]`.
     *
     * Example:
     * - `#[serde(rename = "static")]` -> `"static"`
     */
    fun extractSerdeRename(field: RsNamedFieldDecl): String? {
        return SerdeUtils.extractSerdeSubAttribute(field, SpringRsConstants.SERDE_RENAME)
    }

    /**
     * Returns true if the field has `serde(skip)`.
     *
     * Example: `#[serde(skip)]`
     */
    fun hasSerdeSkip(field: RsNamedFieldDecl): Boolean {
        return SerdeUtils.hasSerdeSubAttribute(field, SpringRsConstants.SERDE_SKIP)
    }

    /**
     * Extracts doc comments using Rust plugin facilities.
     *
     * Handles both `///` and `#[doc = "..."]` formats.
     *
     * @param element PSI element with docs/attributes
     * @return documentation text, or null if blank
     */
    fun extractDocComments(element: org.rust.lang.core.psi.ext.RsDocAndAttributeOwner): String? {
        return element.documentation(withInner = false, withMacroExpansion = false).takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the conditional compilation (`cfg`) attribute text from a field.
     *
     * Example: `#[cfg(feature = "openapi")]`
     *
     * @param field field declaration
     * @return cfg text, or null if absent
     */
    fun extractConditionalCompilation(field: RsNamedFieldDecl): String? {
        // Use raw PSI: iterate `outerAttrList`.
        return field.outerAttrList
            .map { it.metaItem }
            .find { it.name == "cfg" }
            ?.text
    }



    /**
     * Parses a default value from a default-value function.
     *
     * Handles functions like:
     * ```rust
     * fn default_true() -> bool { true }
     * fn default_port() -> u16 { 8080 }
     * ```
     *
     * Uses `expandedItemsCached` to look up top-level items (performance),
     * and `expandedTailExpr` to handle tail expressions and macro expansions.
     *
     * @param field field declaration
     * @param functionName default function name
     * @return default value string, or null if it can't be resolved
     */
    fun parseDefaultValueFromFunction(field: RsNamedFieldDecl, functionName: String): String? {
        // Handle stdlib `Type::default` pattern (e.g. `bool::default`, `String::default`).
        if (functionName.endsWith("::default")) {
            val typeName = functionName.removeSuffix("::default")
            val defaultValue = RustTypeUtils.getDefaultValueExample(typeName)
            // If it's not "?", we recognized that type and can return an example.
            if (defaultValue != "?") {
                return defaultValue
            }
        }

        val containingFile = field.containingFile as? org.rust.lang.core.psi.ext.RsItemsOwner ?: return null

        val defaultFunction = containingFile.expandedItemsCached.named[functionName]
            ?.filterIsInstance<org.rust.lang.core.psi.RsFunction>()
            ?.firstOrNull()
            ?: return null

        val block = defaultFunction.block ?: return null

        // Method 1: use `expandedTailExpr` (preferred; handles macros automatically).
        block.expandedTailExpr?.let { return extractValueFromExpr(it) }

        // Method 2: find an explicit `return` expression (fallback).
        com.intellij.psi.util.PsiTreeUtil.findChildOfType(block, org.rust.lang.core.psi.RsRetExpr::class.java)?.expr?.let {
            return extractValueFromExpr(it)
        }

        return null
    }

    /**
     * Extracts a TOML-friendly value from an expression (using the Rust plugin's constant evaluator).
     *
     * Uses IntelliJ Rust constant evaluation to handle literals, const expressions, path expressions, etc.
     *
     * Supported:
     * - literals: `true`, `false`, `123`, `"string"`
     * - const expressions
     * - path expressions: `None`
     * - complex calls: fallback to text-based heuristics
     */
    private fun extractValueFromExpr(expr: org.rust.lang.core.psi.RsExpr?): String? {
        if (expr == null) return null

        // Rust plugin constant evaluation.
        val evaluated = expr.evaluate()

        return when {
            // Boolean
            evaluated.asBool() != null -> evaluated.asBool().toString()
            // Integer
            evaluated.asLong() != null -> evaluated.asLong().toString()
            // String
            evaluated is org.rust.lang.core.types.consts.CtValue && evaluated.expr is org.rust.lang.utils.evaluation.ConstExpr.Value.Str ->
                "\"${(evaluated.expr as org.rust.lang.utils.evaluation.ConstExpr.Value.Str).value}\""
            // Evaluation failed: try to extract a meaningful value from expression text.
            else -> extractValueFromExprText(expr.text)
        }
    }

    /**
     * Extracts a meaningful TOML value from expression text.
     *
     * Handles common Rust expression patterns:
     * - "string".to_string() / "string".into() → "string"
     * - String::from("string") → "string"
     * - IpAddr::V4(Ipv4Addr::new(a, b, c, d)) → "a.b.c.d"
     * - bool::default() → null (handled by type-level defaults)
     * - unrecognized complex expressions → null
     */
    private fun extractValueFromExprText(text: String): String? {
        val trimmed = text.trim()

        // Pattern 1: `"literal".to_string()` / `"literal".into()` / `"literal".to_owned()`
        val dotMethodPattern = Regex("""^"(.+)"\s*\.\s*(?:to_string|into|to_owned)\s*\(\s*\)$""")
        dotMethodPattern.find(trimmed)?.let {
            return "\"${it.groupValues[1]}\""
        }

        // Pattern 2: `String::from("literal")` / `String::new()` etc.
        val stringFromPattern = Regex("""^String\s*::\s*from\s*\(\s*"(.+)"\s*\)$""")
        stringFromPattern.find(trimmed)?.let {
            return "\"${it.groupValues[1]}\""
        }

        // Pattern 3: `IpAddr::V4(Ipv4Addr::new(a, b, c, d))`
        val ipAddrPattern = Regex("""^IpAddr\s*::\s*V4\s*\(\s*Ipv4Addr\s*::\s*new\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)\s*\)$""")
        ipAddrPattern.find(trimmed)?.let {
            val (a, b, c, d) = it.destructured
            return "\"$a.$b.$c.$d\""
        }

        // Pattern 4: `IpAddr::V6(Ipv6Addr::new(...))`
        val ipv6Pattern = Regex("""^IpAddr\s*::\s*V6\s*\(\s*Ipv6Addr\s*::\s*new\s*\((.+)\)\s*\)$""")
        ipv6Pattern.find(trimmed)?.let {
            val parts = it.groupValues[1].split(",").map { p -> p.trim() }
            if (parts.size == 8 && parts.all { p -> p.matches(Regex("\\d+")) }) {
                val hexParts = parts.map { p -> p.toInt().toString(16) }
                return "\"${hexParts.joinToString(":")}\""
            }
        }

        // Pattern 5: `Ipv4Addr::new(a, b, c, d)` (without `IpAddr` wrapper)
        val ipv4DirectPattern = Regex("""^Ipv4Addr\s*::\s*new\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$""")
        ipv4DirectPattern.find(trimmed)?.let {
            val (a, b, c, d) = it.destructured
            return "\"$a.$b.$c.$d\""
        }

        // Pattern 6: simple float literal
        if (trimmed.matches(Regex("""^\d+\.\d+$"""))) {
            return trimmed
        }

        // Pattern 7: simple string literal (no method calls)
        if (trimmed.matches(Regex("""^"[^"]*"$"""))) {
            return trimmed
        }

        // Unrecognized complex expression: return null (don't show as a default value).
        return null
    }
}
