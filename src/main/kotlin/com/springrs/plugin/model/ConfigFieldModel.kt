package com.springrs.plugin.model

import org.rust.lang.core.psi.RsNamedFieldDecl
import org.rust.lang.core.psi.RsStructItem

/**
 * Config struct model.
 */
data class ConfigStructModel(
    /** Struct name */
    val name: String,
    /** `config_prefix` value (for top-level configs) */
    val configPrefix: String?,
    /** Whether this is a top-level config (has `Configurable` derive and `config_prefix`) */
    val isTopLevel: Boolean,
    /** Field list */
    val fields: List<ConfigFieldModel>,
    /** PSI element */
    val psiElement: RsStructItem,
    /** Doc comments */
    val documentation: String?,
    /** Derive macro list (e.g. Debug, Deserialize, JsonSchema) */
    val derives: List<String>?,
    /** Visibility (e.g. pub, pub(crate)) */
    val visibility: String?,
    /** Conditional compilation (e.g. cfg(feature = "openapi")) */
    val conditionalCompilation: String?,
    /** All attributes on the struct (e.g. serde(rename_all = "...")) */
    val attributes: Map<String, String>?,
    /** Whether this represents an enum */
    val isEnum: Boolean,
    /** Enum variant names (if enum) */
    val enumVariants: List<String>?
)

/**
 * Config field model.
 */
data class ConfigFieldModel(
    /** Field name */
    val name: String,
    /** Full type text (e.g. Option<String>) */
    val type: String,
    /** Wrapper type (e.g. Option, Vec, Box); null if none */
    val wrapperType: String?,
    /** Inner type (e.g. String); equals [type] when there is no wrapper */
    val innerType: String,
    /** Doc comments */
    val documentation: String?,
    /** Whether this is a struct type */
    val isStructType: Boolean,
    /** Target struct model (if struct type) */
    val structModel: ConfigStructModel?,
    /** Whether this is an enum type */
    val isEnumType: Boolean,
    /** Enum type name (if enum type), e.g. LogLevel */
    val enumTypeName: String?,
    /** Default function name (from `#[serde(default = "function_name")]`) */
    val defaultFunction: String?,
    /** Default value (if it can be resolved from the function) */
    val defaultValue: String?,
    /** Visibility (e.g. pub, pub(crate)) */
    val visibility: String?,
    /** Conditional compilation (e.g. cfg(feature = "openapi")) */
    val conditionalCompilation: String?,
    /** All attributes on the field (serde/default/etc). Value can be null for flag attributes. */
    val attributes: Map<String, String?>?,
    /** PSI element */
    val psiElement: RsNamedFieldDecl
) {
    /**
     * Returns true if the field has `serde(flatten)`.
     */
    fun isFlatten(): Boolean = attributes?.containsKey("flatten") == true

    /**
     * Returns true if the field has `serde(skip)`.
     */
    fun isSkip(): Boolean = attributes?.containsKey("skip") == true

    /**
     * Returns the renamed field name (if `serde(rename = "...")` exists).
     */
    fun getRenamedName(): String? = attributes?.get("rename")
}
