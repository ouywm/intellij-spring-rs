package com.springrs.plugin.utils

/**
 * Constants related to the spring-rs framework.
 */
object SpringRsConstants {

    // ==================== Config File Names ====================

    /** Default config file */
    const val CONFIG_FILE_DEFAULT = "app.toml"

    /** Development environment config file */
    const val CONFIG_FILE_DEV = "app-dev.toml"

    /** Test environment config file */
    const val CONFIG_FILE_TEST = "app-test.toml"

    /** Production environment config file */
    const val CONFIG_FILE_PROD = "app-prod.toml"

    /** Config file priority list (highest first) */
    val CONFIG_FILE_PRIORITY = listOf(
        CONFIG_FILE_DEFAULT,
        CONFIG_FILE_DEV,
        CONFIG_FILE_TEST,
        CONFIG_FILE_PROD
    )

    /** Environment config priority (excluding the default config) */
    val CONFIG_FILE_ENV_PRIORITY = listOf(
        CONFIG_FILE_DEV,
        CONFIG_FILE_TEST,
        CONFIG_FILE_PROD
    )

    // ==================== TOML Config Sections ====================

    /** [web] section */
    const val CONFIG_SECTION_WEB = "web"

    /** [web.openapi] section */
    const val CONFIG_SECTION_WEB_OPENAPI = "web.openapi"

    // ==================== TOML Config Keys ====================

    /** Global route prefix key */
    const val CONFIG_KEY_GLOBAL_PREFIX = "global_prefix"

    /** OpenAPI documentation prefix key */
    const val CONFIG_KEY_DOC_PREFIX = "doc_prefix"

    /** `openapi` inline-table key */
    const val CONFIG_KEY_OPENAPI = "openapi"

    // ==================== Framework Name ====================

    /**
     * spring-rs framework name.
     *
     * Used for:
     * - file path filtering (detecting spring-related dependencies)
     * - package name checks
     */
    const val FRAMEWORK_NAME = "spring"

    // ==================== IntelliJ ====================

    /**
     * Placeholder identifier injected by IntelliJ during code completion.
     *
     * IntelliJ inserts this special string at the caret position when completion is active.
     * We use it to detect completion context and avoid creating invalid references.
     */
    const val INTELLIJ_COMPLETION_DUMMY = "IntellijIdeaRulezzz"

    // ==================== spring-rs Config Attributes ====================

    /**
     * `config_prefix` attribute.
     *
     * Used as: `#[config_prefix = "web"]`
     */
    const val ATTR_CONFIG_PREFIX = "config_prefix"

    /**
     * `config_prefix` function name.
     *
     * Used as: `impl Configurable for StructName { fn config_prefix() -> &'static str { "xxx" } }`
     * The `config_prefix` method name of the `Configurable` trait.
     */
    const val CONFIG_PREFIX_FUNCTION = "config_prefix"


    // ==================== spring-rs Derive Macros ====================

    /**
     * `Configurable` derive macro.
     *
     * Used as: `#[derive(Configurable)]`
     * Marks a struct as a configurable type.
     */
    const val DERIVE_CONFIGURABLE = "Configurable"


    // ==================== Serde Attributes ====================

    /**
     * `serde` attribute name.
     *
     * Used as: `#[serde(default, flatten, ...)]`
     */
    const val ATTR_SERDE = "serde"

    /**
     * `default` sub-attribute.
     *
     * Used as:
     * - #[serde(default)]
     * - #[serde(default = "function_name")]
     */
    const val SERDE_DEFAULT = "default"

    /**
     * `flatten` sub-attribute.
     *
     * Used as: `#[serde(flatten)]`
     * Flattens nested struct fields into the current level.
     */
    const val SERDE_FLATTEN = "flatten"

    /**
     * `rename` sub-attribute.
     *
     * Used as: `#[serde(rename = "new_name")]`
     * Renames a field.
     */
    const val SERDE_RENAME = "rename"

    /**
     * `skip` sub-attribute.
     *
     * Used as: `#[serde(skip)]`
     * Skips both serialization and deserialization.
     */
    const val SERDE_SKIP = "skip"

    // ==================== Common Derive Macros ====================

    /**
     * `Debug` derive macro.
     */
    const val DERIVE_DEBUG = "Debug"

    /**
     * `Clone` derive macro.
     */
    const val DERIVE_CLONE = "Clone"

    /**
     * `Deserialize` derive macro (Serde).
     */
    const val DERIVE_DESERIALIZE = "Deserialize"

    /**
     * `Serialize` derive macro (Serde).
     */
    const val DERIVE_SERIALIZE = "Serialize"

    /**
     * `JsonSchema` derive macro (Schemars).
     */
    const val DERIVE_JSON_SCHEMA = "JsonSchema"

    /**
     * `PartialEq` derive macro (std).
     */
    const val DERIVE_PARTIAL_EQ = "PartialEq"

    /**
     * `Eq` derive macro (std).
     */
    const val DERIVE_EQ = "Eq"

    /**
     * `Default` derive macro (std).
     */
    const val DERIVE_DEFAULT = "Default"

    /**
     * `Hash` derive macro (std).
     */
    const val DERIVE_HASH = "Hash"

    /**
     * `Builder` derive macro (bon).
     */
    const val DERIVE_BUILDER = "Builder"

    /**
     * `Validate` derive macro (validator).
     */
    const val DERIVE_VALIDATE = "Validate"

    /**
     * `Service` derive macro (spring-rs).
     */
    const val DERIVE_SERVICE = "Service"

    // ==================== Sea-ORM Derive Macros ====================

    /** `DeriveEntityModel` derive macro (sea-orm). */
    const val SEA_ORM_DERIVE_ENTITY_MODEL = "DeriveEntityModel"

    /** `EnumIter` derive macro (sea-orm). */
    const val SEA_ORM_ENUM_ITER = "EnumIter"

    /** `DeriveRelation` derive macro (sea-orm). */
    const val SEA_ORM_DERIVE_RELATION = "DeriveRelation"

    // ==================== Sea-ORM Type Names ====================

    /** `Decimal` type (rust_decimal). */
    const val TYPE_DECIMAL = "Decimal"

    /** `DateTime` type (sea-orm / chrono). */
    const val TYPE_DATE_TIME = "DateTime"

    /** `DateTimeWithTimeZone` type (sea-orm / chrono). */
    const val TYPE_DATE_TIME_WITH_TZ = "DateTimeWithTimeZone"

    /** `Date` type (sea-orm / chrono). */
    const val TYPE_DATE = "Date"

    /** `Time` type (sea-orm / chrono). */
    const val TYPE_TIME = "Time"


    /**
     * `rename_all` attribute.
     *
     * Used as: `#[serde(rename_all = "snake_case")]`
     * Renames all fields or enum variants.
     */
    const val SERDE_RENAME_ALL = "rename_all"

    /**
     * `skip_serializing` attribute.
     *
     * Used as: `#[serde(skip_serializing)]`
     * Skips serialization only.
     */
    const val SERDE_SKIP_SERIALIZING = "skip_serializing"

    /**
     * `skip_deserializing` attribute.
     *
     * Used as: `#[serde(skip_deserializing)]`
     * Skips deserialization only.
     */
    const val SERDE_SKIP_DESERIALIZING = "skip_deserializing"

    /**
     * `skip_serializing_if` attribute.
     *
     * Used as: `#[serde(skip_serializing_if = "function_name")]`
     * Conditionally skips serialization.
     */
    const val SERDE_SKIP_SERIALIZING_IF = "skip_serializing_if"

    /**
     * `with` attribute.
     *
     * Used as: `#[serde(with = "module_path")]`
     * Uses a custom serialization/deserialization module.
     */
    const val SERDE_WITH = "with"

    /**
     * `borrow` attribute.
     *
     * Used as: `#[serde(borrow)]`
     * Borrows deserialized data.
     */
    const val SERDE_BORROW = "borrow"

    /**
     * `bound` attribute.
     *
     * Used as: `#[serde(bound = "T: Trait")]`
     * Specifies custom trait bounds.
     */
    const val SERDE_BOUND = "bound"

    /**
     * `tag` attribute.
     *
     * Used as: `#[serde(tag = "type")]`
     * Internal tagging for enum representation.
     */
    const val SERDE_TAG = "tag"

    /**
     * `content` attribute.
     *
     * Used as: `#[serde(content = "data")]`
     * Adjacent tagging for enum representation.
     */
    const val SERDE_CONTENT = "content"

    /**
     * `untagged` attribute.
     *
     * Used as: `#[serde(untagged)]`
     * Untagged enum representation.
     */
    const val SERDE_UNTAGGED = "untagged"

    /**
     * `transparent` attribute.
     *
     * Used as: `#[serde(transparent)]`
     * Transparent wrapper type.
     */
    const val SERDE_TRANSPARENT = "transparent"

    /**
     * `remote` attribute.
     *
     * Used as: `#[serde(remote = "Type")]`
     * Defines serialization for external types.
     */
    const val SERDE_REMOTE = "remote"

    /**
     * `getter` attribute.
     *
     * Used as: `#[serde(getter = "function_name")]`
     * Uses a custom getter function.
     */
    const val SERDE_GETTER = "getter"
}
