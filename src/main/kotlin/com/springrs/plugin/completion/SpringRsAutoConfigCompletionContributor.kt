package com.springrs.plugin.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.springrs.plugin.icons.SpringRsIcons
import org.rust.lang.core.psi.RsMetaItem
import org.rust.lang.core.psi.ext.name

/**
 * Completion contributor for `#[auto_config(...)]` macro.
 *
 * Provides completion for spring-rs configurator types:
 * - WebConfigurator
 * - JobConfigurator
 * - StreamConfigurator
 */
class SpringRsAutoConfigCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement()
                .inside(RsMetaItem::class.java),
            AutoConfigCompletionProvider()
        )
    }

    private class AutoConfigCompletionProvider : CompletionProvider<CompletionParameters>() {

        companion object {
            /** Known spring-rs configurator types. */
            private val CONFIGURATORS = listOf(
                ConfiguratorInfo("WebConfigurator", "spring_web", "Registers route handlers"),
                ConfiguratorInfo("JobConfigurator", "spring_job", "Registers scheduled jobs"),
                ConfiguratorInfo("StreamConfigurator", "spring_stream", "Registers stream listeners"),
            )

            private data class ConfiguratorInfo(val name: String, val crate: String, val description: String)
        }

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            // Check that we're inside an `auto_config` attribute.
            val element = parameters.position
            val metaItem = element.parent as? RsMetaItem
                ?: element.parent?.parent as? RsMetaItem
                ?: return

            // Walk up to find the root attribute meta item.
            val rootMeta = findRootMetaItem(metaItem) ?: return
            if (rootMeta.name != "auto_config") return

            for (configurator in CONFIGURATORS) {
                val builder = LookupElementBuilder.create(configurator.name)
                    .withIcon(SpringRsIcons.SpringScan)
                    .withTypeText(configurator.crate)
                    .withTailText(" — ${configurator.description}", true)

                result.addElement(builder)
            }
        }

        private fun findRootMetaItem(meta: RsMetaItem): RsMetaItem? {
            var current: RsMetaItem? = meta
            while (current != null) {
                val parent = current.parent?.parent
                if (parent is RsMetaItem) {
                    current = parent
                } else {
                    return current
                }
            }
            return current
        }
    }
}
