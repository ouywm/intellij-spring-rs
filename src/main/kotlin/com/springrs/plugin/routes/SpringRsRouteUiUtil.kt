package com.springrs.plugin.routes

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * UI helpers for spring-rs routes, jobs, and components.
 */
object SpringRsRouteUiUtil {

    fun getMethodColor(method: String): Color {
        return when (method.uppercase()) {
            "GET" -> JBColor(Color(0x00, 0x7A, 0xCC), Color(0x61, 0xAF, 0xEF))
            "POST" -> JBColor(Color(0x39, 0x8A, 0x39), Color(0x98, 0xC3, 0x79))
            "PUT" -> JBColor(Color(0xB5, 0x8A, 0x00), Color(0xE5, 0xC0, 0x7B))
            "DELETE" -> JBColor(Color(0xC7, 0x3E, 0x3E), Color(0xE0, 0x6C, 0x75))
            "PATCH" -> JBColor(Color(0x9B, 0x59, 0xB6), Color(0xC6, 0x78, 0xDD))
            else -> UIUtil.getLabelForeground()
        }
    }

    /**
     * Returns a display color for a job scheduling type.
     */
    fun getJobTypeColor(type: String): Color {
        return when (type.uppercase()) {
            "CRON" -> JBColor(Color(0x00, 0x7A, 0xCC), Color(0x61, 0xAF, 0xEF))
            "FIX_DELAY" -> JBColor(Color(0xB5, 0x8A, 0x00), Color(0xE5, 0xC0, 0x7B))
            "FIX_RATE" -> JBColor(Color(0x39, 0x8A, 0x39), Color(0x98, 0xC3, 0x79))
            "ONE_SHOT" -> JBColor(Color(0x9B, 0x59, 0xB6), Color(0xC6, 0x78, 0xDD))
            else -> UIUtil.getLabelForeground()
        }
    }

    /**
     * Returns a display color for a component type.
     */
    fun getComponentTypeColor(type: String): Color {
        return when (type.uppercase()) {
            "SERVICE" -> JBColor(Color(0x39, 0x8A, 0x39), Color(0x98, 0xC3, 0x79))
            "CONFIGURATION" -> JBColor(Color(0xB5, 0x8A, 0x00), Color(0xE5, 0xC0, 0x7B))
            "PLUGIN" -> JBColor(Color(0x9B, 0x59, 0xB6), Color(0xC6, 0x78, 0xDD))
            else -> UIUtil.getLabelForeground()
        }
    }
}

