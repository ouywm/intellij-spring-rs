package com.springrs.plugin.references

import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import org.rust.toml.tomlPluginIsAbiCompatible
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlLiteral

/**
 * Provides TOML -> Rust references for spring-rs config files.
 *
 * Enables:
 * - Go to Declaration (Ctrl/Cmd+B) from sections/keys/enum values to Rust definitions
 * - Find Usages from Rust structs/fields/enums back to TOML files (because TOML contains references)
 */
class SpringRsReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        if (!tomlPluginIsAbiCompatible()) return

        registrar.registerReferenceProvider(
            psiElement(TomlKeySegment::class.java),
            SpringRsTomlKeyReferenceProvider()
        )

        registrar.registerReferenceProvider(
            psiElement(TomlLiteral::class.java),
            SpringRsTomlEnumValueReferenceProvider()
        )

        // ${VAR} references in TOML string values -> navigate to .env file definitions.
        registrar.registerReferenceProvider(
            psiElement(TomlLiteral::class.java),
            SpringRsTomlEnvVarReferenceProvider()
        )
    }
}

