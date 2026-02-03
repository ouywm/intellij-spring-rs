package com.springrs.plugin.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTableHeader

/**
 * spring-rs config completion contributor.
 *
 * Provides smart completion for spring-rs config files (e.g. app.toml).
 *
 * Features:
 * 1. Section header completion: completes section names in [xxx]
 * 2. Key completion: completes config keys inside a section
 * 3. Smart value insertion: inserts a suitable value template based on field type
 *
 * Conditions:
 * - The project must be a spring-rs project (spring-related crates in dependencies)
 * - File name must match app.toml or app-{env}.toml
 *
 * Example:
 * ```toml
 * [web]  # ← section completion (triggered after typing '[')
 * host = "0.0.0.0"  # ← key completion
 * port = 8080
 * ```
 */
class SpringRsConfigCompletionContributor : CompletionContributor() {

    init {
        // 1. Provide completion for TOML section headers.
        // Pattern: xxx in [xxx]
        // After typing '[', the IDE creates a placeholder TomlKeySegment.
        // Trigger completion here to show all available sections.
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement()
                .withParent(TomlKeySegment::class.java)
                .inside(TomlTableHeader::class.java),
            SpringRsSectionCompletionProvider()
        )

        // 2. Provide completion for TOML config keys.
        // Pattern: key in key = value
        // When typing inside a section, show all available keys.
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement()
                .withParent(TomlKeySegment::class.java)
                .withSuperParent(2, TomlKey::class.java)
                .withSuperParent(3, TomlKeyValue::class.java),
            SpringRsKeyCompletionProvider()
        )

        // 3. Provide completion for TOML values.
        // Pattern: value in key = value
        // When typing after '=', show values based on the field type.
        // Note: don't match only TomlLiteral because there is no TomlLiteral before the user types a value.
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement()
                .inside(TomlKeyValue::class.java),
            SpringRsValueCompletionProvider()
        )

        // 4. Provide completion for inline table keys.
        // Pattern: key2 in {key = value, key2 = ...}
        // When typing inside an inline table, show all struct fields.
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement()
                .inside(org.toml.lang.psi.TomlInlineTable::class.java),
            SpringRsInlineTableKeyCompletionProvider()
        )
    }
}
