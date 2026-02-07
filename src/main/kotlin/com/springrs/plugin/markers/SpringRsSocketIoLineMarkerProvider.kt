package com.springrs.plugin.markers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.icons.SpringRsIcons
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.name

/**
 * Gutter icons for spring-rs Socket.IO macros.
 *
 * Shows icons on functions annotated with:
 * - #[on_connection]
 * - #[on_disconnect]
 * - #[subscribe_message("event_name")]
 * - #[on_fallback]
 */
class SpringRsSocketIoLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {

    companion object {
        private val SOCKETIO_ATTRS = mapOf(
            "on_connection" to "Connection Handler",
            "on_disconnect" to "Disconnect Handler",
            "subscribe_message" to "Message Subscription",
            "on_fallback" to "Fallback Handler"
        )
    }

    override fun getName(): String = SpringRsBundle.message("springrs.socketio.marker.name")
    override fun getIcon() = SpringRsIcons.SpringWeb

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        val fn = element.parent as? RsFunction ?: return null
        if (fn.identifier != element) return null

        var attrDisplayName: String? = null
        var eventName: String? = null

        for (attr in fn.outerAttrList) {
            val meta = attr.metaItem
            val name = meta.name ?: continue
            val display = SOCKETIO_ATTRS[name] ?: continue

            attrDisplayName = display

            // Extract event name for subscribe_message("event").
            if (name == "subscribe_message") {
                val args = meta.metaItemArgs
                if (args != null) {
                    val argsText = args.text?.trim()?.removeSurrounding("(", ")") ?: ""
                    val match = Regex("""^"([^"]*)".*""").find(argsText.trim())
                    eventName = match?.groupValues?.get(1)
                }
            }
            break
        }

        if (attrDisplayName == null) return null

        val fnName = fn.name ?: "unknown"
        val tooltip = buildString {
            append("Socket.IO: $attrDisplayName")
            append("\nHandler: $fnName")
            eventName?.let { append("\nEvent: $it") }
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            SpringRsIcons.SpringWeb,
            { _: PsiElement -> tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { SpringRsBundle.message("springrs.socketio.marker.name") }
        )
    }
}
