package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import com.springrs.plugin.lifecycle.SpringRsProjectLifetimeDisposable
import com.springrs.plugin.utils.RustAttributeUtils
import org.rust.ide.lineMarkers.RsLineMarkerInfoUtils
import org.rust.lang.core.psi.RsStructItem

/**
 * Rust -> TOML gutter navigation for spring-rs config.
 *
 * Minimal:
 * - Show a gutter icon on config structs (those with config_prefix) to navigate to `[prefix]` section in app*.toml.
 */
class SpringRsLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = SpringRsBundle.message("springrs.config.marker.name")

    override fun getIcon() = SpringRsIcons.SpringRs

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        // Struct marker: anchor on struct identifier
        val struct = element.parent as? RsStructItem
        if (struct != null && struct.identifier == element) {
            val prefix = RustAttributeUtils.extractConfigPrefix(struct) ?: return null

            return RsLineMarkerInfoUtils.create(
                element,
                element.textRange,
                SpringRsIcons.SpringRs,
                NavigateToTomlHandler(prefix, null),
                GutterIconRenderer.Alignment.LEFT
            ) { SpringRsBundle.message("springrs.config.marker.tooltip.navigate.section", prefix) }
        }

        return null
    }

    private class NavigateToTomlHandler(
        private val sectionName: String,
        private val keyName: String?
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: java.awt.event.MouseEvent?, elt: PsiElement) {
            ReadAction.nonBlocking<com.intellij.openapi.fileEditor.OpenFileDescriptor?> {
                SpringRsTomlNavigationUtil.computeNavigationDescriptor(
                    project = elt.project,
                    sectionName = sectionName,
                    keyName = keyName,
                    sourceElement = elt  // Pass the source element to resolve current crate scope.
                )
            }
                // Do not use Project itself as a disposable parent (inspection). Use a project-level disposable service.
                .expireWith(elt.project.service<SpringRsProjectLifetimeDisposable>())
                .finishOnUiThread(ModalityState.any()) { descriptor ->
                    descriptor?.navigate(true)
                }
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
        }
    }
}
