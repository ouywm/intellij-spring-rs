package com.springrs.plugin.config.parser

/**
 * Rust field attributes (from macros).
 *
 * Represents various field-level attributes such as `#[serde(flatten)]`, `#[serde(default)]`, etc.
 */
sealed class FieldAttribute {
    /**
     * `#[serde(flatten)]` - flattens nested struct fields into the parent struct.
     */
    object Flatten : FieldAttribute()

    /**
     * `#[serde(default)]` - uses the default value.
     */
    object Default : FieldAttribute()

    /**
     * `#[serde(default = "function_name")]` - uses the specified function's return value as the default.
     */
    data class DefaultWith(val functionName: String) : FieldAttribute()

    /**
     * `#[serde(rename = "new_name")]` - renames the field.
     */
    data class Rename(val newName: String) : FieldAttribute()

    /**
     * `#[serde(skip)]` - skips serialization/deserialization.
     */
    object Skip : FieldAttribute()

    /**
     * Any other unrecognized attribute.
     */
    data class Other(val name: String, val value: String?) : FieldAttribute()
}

/**
 * Converts a list of [FieldAttribute] to a map.
 *
 * @return attributes map (key: attribute name, value: attribute value or null)
 */
fun List<FieldAttribute>.toAttributesMap(): Map<String, String?>? {
    if (isEmpty()) {
        return null
    }

    val map = mutableMapOf<String, String?>()

    forEach { attr ->
        when (attr) {
            is FieldAttribute.Flatten -> map["flatten"] = null
            is FieldAttribute.Default -> map["default"] = null
            is FieldAttribute.DefaultWith -> map["default"] = attr.functionName
            is FieldAttribute.Rename -> map["rename"] = attr.newName
            is FieldAttribute.Skip -> map["skip"] = null
            is FieldAttribute.Other -> map[attr.name] = attr.value
        }
    }

    return map
}
