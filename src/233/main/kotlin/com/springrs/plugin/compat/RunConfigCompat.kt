package com.springrs.plugin.compat

import com.intellij.openapi.components.BaseState

/**
 * Run configuration compatibility layer for platform version 233.
 *
 * In 233, [CargoConfigurationOptions] does not exist.
 * We return null to signal that the default options class should be used.
 */
object RunConfigCompat {

    /** Returns null in 233 — the factory will not override getOptionsClass(). */
    fun getOptionsClass(): Class<out BaseState>? = null
}