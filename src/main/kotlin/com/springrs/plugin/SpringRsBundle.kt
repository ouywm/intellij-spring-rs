package com.springrs.plugin

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * Resource bundle path for spring-rs plugin messages.
 */
@NonNls
const val BUNDLE = "messages.SpringRsBundle"

/**
 * spring-rs plugin message bundle.
 *
 * Provides access to localized strings.
 */
object SpringRsBundle : DynamicBundle(BUNDLE) {

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return getMessage(key, *params)
    }
}
