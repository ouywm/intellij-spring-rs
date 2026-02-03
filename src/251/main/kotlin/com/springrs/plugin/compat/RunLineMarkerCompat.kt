package com.springrs.plugin.compat

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.RsElementTypes
import org.rust.lang.core.psi.ext.elementType
import javax.swing.Icon

/**
 * RunLineMarker compatibility layer for platform version 251+.
 *
 * In 251+, the `elementType` extension property exists on PsiElement,
 * and Info constructor accepts (Icon, Array<AnAction>).
 */
object RunLineMarkerCompat {

    /** Check if element is an identifier token (for run marker placement). */
    fun isIdentifier(element: PsiElement): Boolean =
        element.elementType == RsElementTypes.IDENTIFIER

    /** Create a RunLineMarkerContributor.Info that replaces other markers. */
    fun createInfo(icon: Icon, actions: Array<AnAction>): RunLineMarkerContributor.Info {
        return object : RunLineMarkerContributor.Info(icon, actions) {
            override fun shouldReplace(other: RunLineMarkerContributor.Info): Boolean = true
        }
    }
}