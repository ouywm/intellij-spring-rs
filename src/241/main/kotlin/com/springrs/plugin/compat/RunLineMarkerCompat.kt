package com.springrs.plugin.compat

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.RsElementTypes
import javax.swing.Icon

/**
 * RunLineMarker compatibility layer for platform version 241.
 *
 * In 241, the `elementType` extension property does not exist (must use node.elementType),
 * and Info constructor requires a tooltip provider function.
 */
object RunLineMarkerCompat {

    /** Check if element is an identifier token (for run marker placement). */
    fun isIdentifier(element: PsiElement): Boolean =
        element.node.elementType == RsElementTypes.IDENTIFIER

    /** Create a RunLineMarkerContributor.Info that replaces other markers. */
    fun createInfo(icon: Icon, actions: Array<AnAction>): RunLineMarkerContributor.Info {
        // In 241, Info constructor requires: (icon, tooltipProvider, actions...)
        return object : RunLineMarkerContributor.Info(icon, { "spring-rs" }, *actions) {
            override fun shouldReplace(other: RunLineMarkerContributor.Info): Boolean = true
        }
    }
}