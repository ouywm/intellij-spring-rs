package com.springrs.plugin.utils

import org.rust.lang.core.types.ty.*

/**
 * Rust type utilities.
 *
 * Helper methods related to Rust types.
 *
 * Note: for primitive types we rely on IntelliJ Rust type system types directly:
 * - `TyBool`, `TyChar`, `TyStr`, `TyInteger`, `TyFloat`, etc.
 */
object RustTypeUtils {

    // ==================== Rust Keywords ====================

    /**
     * Rust keyword list - cannot be used as identifiers.
     * https://doc.rust-lang.org/reference/keywords.html
     */
    val RUST_KEYWORDS = setOf(
        // Strict keywords
        "as", "async", "await", "break", "const", "continue", "crate", "dyn",
        "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
        "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
        "self", "Self", "static", "struct", "super", "trait", "true", "type",
        "unsafe", "use", "where", "while",
        "gen",
        // Reserved keywords
        "abstract", "become", "box", "do", "final", "macro", "override",
        "priv", "try", "typeof", "unsized", "virtual", "yield"
    )

    /**
     * Returns true if the name is a Rust keyword.
     */
    fun isKeyword(name: String): Boolean = name in RUST_KEYWORDS

    // ==================== Base Types for JSON Conversion ====================

    /** Rust type names used by JSON conversion. */
    object JsonTypes {
        const val BOOL = "bool"
        const val I64 = "i64"
        const val F64 = "f64"
        const val STRING = "String"
        const val VALUE = "Value"
    }

    // ==================== Derive Macro Constants ====================

    /** Common derive macro names. */
    object Derives {
        const val SERIALIZE = "Serialize"
        const val DESERIALIZE = "Deserialize"
        const val DEBUG = "Debug"
        const val CLONE = "Clone"
    }

    // ==================== Serde Attribute Templates ====================

    /** Templates for serde attributes. */
    object SerdeAttrs {
        fun rename(originalKey: String): String = "#[serde(rename = \"$originalKey\")]"
        fun derive(vararg names: String): String = "#[derive(${names.joinToString(", ")})]"
    }

    // ==================== Wrapper Types ====================

    /** `Option` type name. */
    const val OPTION = "Option"

    /** `pub` keyword (visibility modifier). */
    const val PUB = "pub"

    /** `"pub "` prefix used for code generation. */
    const val PUB_PREFIX = "$PUB "

    fun visibilityPrefix(isPublic: Boolean): String = if (isPublic) PUB_PREFIX else ""

    fun optionOf(innerType: String): String = "$OPTION<$innerType>"

    fun isOptionType(typeText: String): Boolean = typeText.startsWith("$OPTION<")

    /** `Vec` type name. */
    const val VEC = "Vec"

    /** `Box` type name. */
    const val BOX = "Box"

    /** `Arc` type name. */
    const val ARC = "Arc"

    /** `Rc` type name. */
    const val RC = "Rc"

    /** Set of wrapper type names. */
    val WRAPPER_TYPES = setOf(OPTION, VEC, BOX, ARC, RC)

    // ==================== Collection Types ====================

    /** `HashMap` type name. */
    const val HASH_MAP = "HashMap"

    /** `HashSet` type name. */
    const val HASH_SET = "HashSet"

    /** `BTreeMap` type name. */
    const val BTREE_MAP = "BTreeMap"

    /** `BTreeSet` type name. */
    const val BTREE_SET = "BTreeSet"

    /** `IndexMap` type name. */
    const val INDEX_MAP = "IndexMap"

    /** `IndexSet` type name. */
    const val INDEX_SET = "IndexSet"

    /** `VecDeque` type name. */
    const val VEC_DEQUE = "VecDeque"

    /** `LinkedList` type name. */
    const val LINKED_LIST = "LinkedList"

    /** `String` type name. */
    const val STRING = "String"

    /** `Cow` type name. */
    const val COW = "Cow"

    /**
     * Types that are typically represented as TOML strings (they deserialize from strings),
     * even though the Rust type is not `String`.
     *
     * Examples:
     * - std::net::IpAddr: "0.0.0.0"
     * - std::path::PathBuf: "static"
     */
    private val STRING_LIKE_TYPES = setOf(
        "IpAddr",
        "Ipv4Addr",
        "Ipv6Addr",
        "SocketAddr",
        "PathBuf",
        "OsString",
        "Url",
        "Uuid"
    )

    /** Map-like type names. */
    private val MAP_TYPES = setOf(HASH_MAP, INDEX_MAP, BTREE_MAP)

    /** Set-like type names. */
    private val SET_TYPES = setOf(HASH_SET, INDEX_SET, BTREE_SET)

    /** All collection type names. */
    private val COLLECTION_TYPES = setOf(
        HASH_MAP, INDEX_MAP, BTREE_MAP,
        HASH_SET, INDEX_SET, BTREE_SET,
        VEC, VEC_DEQUE, LINKED_LIST,
        STRING, COW
    )

    // ==================== Code Generation ====================

    object Codegen {
        const val INDENT = "    "

        fun docLine(text: String, indent: String = INDENT): String = "$indent/// $text"

        fun docExample(valuePreview: String): String = docLine("Example: $valuePreview")
    }

    // ==================== Type Name Extraction ====================

    /**
     * Extracts the type name (stripping generic wrappers/parameters).
     *
     * Examples:
     * - "Option<String>" -> "String"
     * - "Vec<User>" -> "User"
     * - "String" -> "String"
     */
    fun extractTypeName(typeText: String): String {
        // Strip generic wrappers like Option<T>, Vec<T>, etc.
        val cleanType = typeText
            .replace("Option<", "")
            .replace("Vec<", "")
            .replace("Box<", "")
            .replace("Arc<", "")
            .replace("Rc<", "")
            .replace(">", "")
            .trim()

        return cleanType
    }

    /**
     * Extracts wrapper type name.
     *
     * Examples:
     * - "Option<String>" -> "Option"
     * - "Vec<User>" -> "Vec"
     * - "String" -> null
     */
    fun extractWrapperType(typeText: String): String? {
        return WRAPPER_TYPES.find { typeText.startsWith("$it<") }
    }

    /**
     * Returns type name from a [Ty] instance.
     *
     * Uses IntelliJ Rust type system.
     */
    fun getTypeName(ty: Ty): String {
        return when (ty) {
            is TyBool -> ty.name
            is TyChar -> ty.name
            is TyStr -> ty.name
            is TyInteger -> ty.name
            is TyFloat -> ty.name
            else -> ty.toString()
        }
    }

    // ==================== Type Checks ====================

    /**
     * Returns true if the type is a generic wrapper.
     *
     * Examples: Option<T>, Vec<T>, Box<T>
     */
    fun isGenericWrapper(typeText: String): Boolean {
        return WRAPPER_TYPES.any { typeText.startsWith("$it<") }
    }

    /**
     * Returns true if the type name is a primitive type.
     *
     * Uses IntelliJ Rust type definitions.
     */
    fun isPrimitiveType(typeName: String): Boolean {
        return when (typeName) {
            TyBool.INSTANCE.name,
            TyChar.INSTANCE.name,
            TyStr.INSTANCE.name,
            TyInteger.I8.INSTANCE.name,
            TyInteger.I16.INSTANCE.name,
            TyInteger.I32.INSTANCE.name,
            TyInteger.I64.INSTANCE.name,
            TyInteger.I128.INSTANCE.name,
            TyInteger.ISize.INSTANCE.name,
            TyInteger.U8.INSTANCE.name,
            TyInteger.U16.INSTANCE.name,
            TyInteger.U32.INSTANCE.name,
            TyInteger.U64.INSTANCE.name,
            TyInteger.U128.INSTANCE.name,
            TyInteger.USize.INSTANCE.name,
            TyFloat.F32.INSTANCE.name,
            TyFloat.F64.INSTANCE.name -> true

            else -> false
        }
    }

    /**
     * Returns true if the type name is an integer type.
     */
    fun isIntegerType(typeName: String): Boolean {
        return when (typeName) {
            TyInteger.I8.INSTANCE.name,
            TyInteger.I16.INSTANCE.name,
            TyInteger.I32.INSTANCE.name,
            TyInteger.I64.INSTANCE.name,
            TyInteger.I128.INSTANCE.name,
            TyInteger.ISize.INSTANCE.name,
            TyInteger.U8.INSTANCE.name,
            TyInteger.U16.INSTANCE.name,
            TyInteger.U32.INSTANCE.name,
            TyInteger.U64.INSTANCE.name,
            TyInteger.U128.INSTANCE.name,
            TyInteger.USize.INSTANCE.name -> true

            else -> false
        }
    }

    /**
     * Returns true if the type name is a float type.
     */
    fun isFloatType(typeName: String): Boolean {
        return when (typeName) {
            TyFloat.F32.INSTANCE.name,
            TyFloat.F64.INSTANCE.name -> true

            else -> false
        }
    }

    /**
     * Returns true if the type name is numeric.
     */
    fun isNumericType(typeName: String): Boolean {
        return isIntegerType(typeName) || isFloatType(typeName)
    }

    /**
     * Returns true if the type name is a collection type.
     */
    fun isCollectionType(typeName: String): Boolean {
        return typeName in COLLECTION_TYPES
    }

    /**
     * Returns true if the type text represents a Vec type.
     *
     * Examples: Vec<String>, Option<Vec<String>>, Vec<WithFields>
     */
    fun isVecType(typeText: String): Boolean {
        return typeText.startsWith("$VEC<") || typeText.contains("Vec<")
    }

    /**
     * Returns true if the type text represents `String` (or a simple wrapper around it).
     *
     * Examples: String, Option<String>
     *
     * Note: does not match Vec<String> or other complex types containing String.
     */
    fun isStringType(typeText: String): Boolean {
        // Exact match String or wrappers like Option<String>, Box<String>,
        // but not Vec<String>, HashMap<String, T>, etc.
        if (typeText == "String") return true

        // Wrapper types around String (Option, Box, Arc, Rc).
        for (wrapper in WRAPPER_TYPES) {
            if (typeText == "$wrapper<String>") return true
        }

        return false
    }

    /**
     * Returns true if the type has "string semantics".
     *
     * Such types are typically represented as TOML strings (quoted) in config files,
     * even though their Rust type is not `String`.
     *
     * Examples:
     * - IpAddr / Ipv4Addr / Ipv6Addr
     * - PathBuf
     */
    fun isStringLikeType(typeText: String): Boolean {
        if (isStringType(typeText)) return true

        val baseType = extractStructName(extractTypeName(typeText))
        return baseType in STRING_LIKE_TYPES
    }

    /**
     * Returns true if the type text represents `bool`.
     *
     * Examples: bool, Option<bool>
     */
    fun isBoolType(typeText: String): Boolean {
        return typeText == "bool" || typeText.contains("bool")
    }

    /**
     * Returns true if the type text represents a map type.
     *
     * Examples: HashMap<K, V>, IndexMap<K, V>, BTreeMap<K, V>, Option<HashMap<K, V>>
     */
    fun isMapType(typeText: String): Boolean {
        return MAP_TYPES.any { typeText.contains("$it<") }
    }

    // ==================== Type Name Extraction (PSI API) ====================

    /**
     * Extracts wrapper type and inner type using PSI API.
     *
     * Example: Option<String> -> (Option, String)
     *
     * @param typeRef type reference (PSI)
     * @return Pair(wrapper type name, inner type text); if not a wrapper, returns (null, original type text)
     */
    fun extractWrapperAndInner(typeRef: org.rust.lang.core.psi.RsTypeReference?): Pair<String?, String> {
        if (typeRef == null) {
            return Pair(null, "unknown")
        }

        val pathType = typeRef as? org.rust.lang.core.psi.RsPathType
        if (pathType == null) {
            return Pair(null, typeRef.text)
        }

        // Type name (e.g. "Option", "Vec")
        val typeName = pathType.path.referenceName

        // Check wrapper type.
        if (typeName !in WRAPPER_TYPES) {
            return Pair(null, typeRef.text)
        }

        // Extract generic arguments via PSI.
        val typeArgumentList = pathType.path.typeArgumentList
        if (typeArgumentList != null && typeArgumentList.typeReferenceList.isNotEmpty()) {
            val innerTypeRef = typeArgumentList.typeReferenceList.first()
            return Pair(typeName, innerTypeRef.text)
        }

        // No generic args: return original text.
        return Pair(null, typeRef.text)
    }

    /**
     * Extracts struct name (strips wrappers and paths).
     *
     * Example: Option<aide::openapi::Info> -> Info
     *
     * @param typeName type name
     * @return extracted struct name
     */
    fun extractStructName(typeName: String): String {
        var result = typeName

        // Handle qualified type name (e.g. aide::openapi::Info).
        if (result.contains("::")) {
            result = result.substringAfterLast("::")
        }

        return result
    }

    /**
     * Extracts inner type name (strips generic wrappers, keeps full path).
     *
     * Examples:
     * - "Option<aide::openapi::Info>" -> "aide::openapi::Info"
     * - "Vec<String>" -> "String"
     * - "Box<MyStruct>" -> "MyStruct"
     * - "aide::openapi::Info" -> "aide::openapi::Info"
     *
     * @param typeText type text
     * @return inner type name without wrappers
     */
    fun extractInnerTypeName(typeText: String): String {
        var result = typeText.trim()

        // Handle wrappers like Option<T>, Vec<T>, Box<T>, etc.
        for (wrapper in WRAPPER_TYPES) {
            val prefix = "$wrapper<"
            if (result.startsWith(prefix) && result.endsWith(">")) {
                result = result.removePrefix(prefix).removeSuffix(">").trim()
                break
            }
        }

        return result
    }

    // ==================== Type Filtering (for Nested Type Collection) ====================

    /**
     * Returns true if field type text should be skipped (no nested type collection).
     *
     * Quick check by type text to avoid parsing generic types.
     *
     * @param typeText field type text (e.g. "IndexMap<String, serde_json::Value>")
     */
    fun shouldSkipFieldType(typeText: String): Boolean {
        // Skip collection types (based on predefined constants).
        return COLLECTION_TYPES.any { typeText.contains("$it<") }
    }

    /**
     * Returns true if a struct should be skipped during nested type collection.
     *
     * Skips:
     * 1. Standard collection types (HashMap, IndexMap, Vec, BTreeMap, etc.)
     * 2. Low-level implementation details (RawTable, Core, Indices, etc.)
     * 3. Generic/recursive types (e.g. serde_json::Value)
     * 4. Standard library primitives/wrappers (Option, Result, Box, etc.)
     *
     * @param struct struct
     */
    fun shouldSkipNestedTypeCollection(struct: org.rust.lang.core.psi.RsStructItem): Boolean {
        val structName = struct.name ?: return false
        val filePath = struct.containingFile.virtualFile?.path ?: return false

        // 1. Skip all types in stdlib sources.
        if (FilePathValidator.isStandardLibrary(filePath)) {
            return true
        }

        // 2. Skip common collection types.
        if (structName in COLLECTION_TYPES) {
            return true
        }

        // 3. Skip low-level implementation details (commonly Inner/Core/Raw).
        if (structName.endsWith("Inner") ||
            structName.endsWith("Core") ||
            structName.startsWith("Raw") ||
            structName.contains("Internal")) {
            return true
        }

        // 4. Skip internal types from libraries like indexmap/hashbrown.
        if (filePath.contains("/indexmap-") || filePath.contains("/hashbrown-")) {
            // Allow top-level IndexMap/HashMap only, skip internal impl.
            if (structName !in setOf(INDEX_MAP, HASH_MAP)) {
                return true
            }
        }

        // 5. Skip serde_json::Value (recursive).
        if (filePath.contains("/serde_json-") && structName == "Value") {
            return true
        }

        return false
    }

    // ==================== Type Resolution (PSI API) ====================

    /**
     * Resolves the enum item for a type reference (if it is an enum type).
     *
     * Supports wrappers like Option<T>, Vec<T>.
     * Uses PSI API for resolution, which is more reliable than regex.
     *
     * @param typeRef type reference
     * @return enum definition if the type is an enum, otherwise null
     */
    fun resolveEnumType(typeRef: org.rust.lang.core.psi.RsTypeReference): org.rust.lang.core.psi.RsEnumItem? {
        val pathType = typeRef as? org.rust.lang.core.psi.RsPathType ?: return null

        // Check whether it's a generic wrapper type via PSI.
        val typeArgumentList = pathType.path.typeArgumentList

        if (typeArgumentList != null && typeArgumentList.typeReferenceList.isNotEmpty()) {
            // Generic wrapper (Option<T>, Vec<T>, ...): resolve inner type.
            val innerTypeRef = typeArgumentList.typeReferenceList.first()
            val innerPathType = innerTypeRef as? org.rust.lang.core.psi.RsPathType

            if (innerPathType != null) {
                val resolved = innerPathType.path.reference?.resolve()
                val enumItem = resolved as? org.rust.lang.core.psi.RsEnumItem

                // Accept enums that are not stdlib and not macro-expanded.
                if (enumItem != null && isValidExternalEnum(enumItem)) {
                    return enumItem
                }
            }

            return null
        }

        // Not a generic wrapper: resolve directly.
        val resolved = pathType.path.reference?.resolve()
        val enumItem = resolved as? org.rust.lang.core.psi.RsEnumItem

        // Accept enums that are not stdlib and not macro-expanded.
        if (enumItem != null && isValidExternalEnum(enumItem)) {
            return enumItem
        }

        return null
    }

    /**
     * Returns true if the enum is a resolvable external enum (not stdlib, not macro-expanded).
     */
    private fun isValidExternalEnum(enumItem: org.rust.lang.core.psi.RsEnumItem): Boolean {
        val filePath = enumItem.containingFile.virtualFile?.path ?: return false
        return FilePathValidator.isValidExternalFile(filePath)
    }

    // ==================== Default Value Examples (for TOML samples) ====================

    /**
     * Returns a default value example for a type (for TOML config examples).
     *
     * Note: this is not Rust's `Default` trait behavior.
     * It's only used to generate sample values in TOML configs.
     */
    fun getDefaultValueExample(typeName: String): String {
        return when (typeName) {
            // Bool
            TyBool.INSTANCE.name -> "false"

            // Char
            TyChar.INSTANCE.name -> "'a'"

            // String
            TyStr.INSTANCE.name -> "\"\""
            "String" -> "\"\""

            // Integer
            TyInteger.I8.INSTANCE.name,
            TyInteger.I16.INSTANCE.name,
            TyInteger.I32.INSTANCE.name,
            TyInteger.I64.INSTANCE.name,
            TyInteger.I128.INSTANCE.name,
            TyInteger.ISize.INSTANCE.name,
            TyInteger.U8.INSTANCE.name,
            TyInteger.U16.INSTANCE.name,
            TyInteger.U32.INSTANCE.name,
            TyInteger.U64.INSTANCE.name,
            TyInteger.U128.INSTANCE.name,
            TyInteger.USize.INSTANCE.name -> "0"

            // Float
            TyFloat.F32.INSTANCE.name,
            TyFloat.F64.INSTANCE.name -> "0.0"

            // Other types (best-effort based on type name)
            else -> when {
                typeName.startsWith("Option<") -> "# Optional field"
                typeName.startsWith("Vec<") -> "[]"
                typeName.startsWith("HashMap<") || typeName.startsWith("BTreeMap<") -> "{}"
                typeName.startsWith("HashSet<") || typeName.startsWith("BTreeSet<") -> "[]"
                else -> "# Custom type: $typeName"
            }
        }
    }
}
