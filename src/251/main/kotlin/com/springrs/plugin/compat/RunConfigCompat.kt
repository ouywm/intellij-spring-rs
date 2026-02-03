package com.springrs.plugin.compat

import com.intellij.openapi.components.BaseState
import org.rust.cargo.runconfig.command.CargoConfigurationOptions

/**
 * Run configuration compatibility layer for platform version 251+.
 *
 * In 251+, [CargoConfigurationOptions] exists and can be used for persistent state.
 */
object RunConfigCompat {

    /** Returns the options class for run configuration persistent state. */
    fun getOptionsClass(): Class<out BaseState>? = CargoConfigurationOptions::class.java
}