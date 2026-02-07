package com.springrs.plugin.codegen.template

import com.springrs.plugin.codegen.toPascalCase
import com.springrs.plugin.codegen.toSnakeCase
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Utility object exposed as `$tool` in Velocity templates.
 *
 * Provides string manipulation, collection creation, and other utilities.
 * Aligned with EasyCode's `$tool` API for familiarity.
 *
 * Usage in templates:
 * ```velocity
 * $tool.firstUpperCase($name)
 * $tool.firstLowerCase($name)
 * $tool.toPascalCase("user_accounts")
 * $tool.toSnakeCase("UserAccounts")
 * $tool.append($a, $b, $c)
 * $tool.newHashSet("a", "b", "c")
 * ```
 */
object TemplateTool {

    // ══════════════════════════════════════════════════════════════
    // ── String manipulation
    // ══════════════════════════════════════════════════════════════

    /** First character to upper case: `user` → `User` */
    fun firstUpperCase(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return str.replaceFirstChar { it.uppercase() }
    }

    /** First character to lower case: `User` → `user` */
    fun firstLowerCase(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return str.replaceFirstChar { it.lowercase() }
    }

    /** Convert to PascalCase: `user_accounts` → `UserAccounts` */
    fun toPascalCase(str: String?): String = str?.toPascalCase() ?: ""

    /** Convert to snake_case: `UserAccounts` → `user_accounts` */
    fun toSnakeCase(str: String?): String = str?.toSnakeCase() ?: ""

    /** Convert to camelCase: `user_accounts` → `userAccounts` */
    fun toCamelCase(str: String?): String = firstLowerCase(toPascalCase(str))

    /** Convert to SCREAMING_SNAKE_CASE: `userAccounts` → `USER_ACCOUNTS` */
    fun toScreamingSnake(str: String?): String = toSnakeCase(str).uppercase()

    /** Convert to kebab-case: `user_accounts` → `user-accounts` */
    fun toKebabCase(str: String?): String = toSnakeCase(str).replace("_", "-")

    /** Append multiple strings together. */
    fun append(vararg parts: Any?): String = parts.joinToString("") { it?.toString() ?: "" }

    /** Check if string is blank. */
    fun isBlank(str: String?): Boolean = str.isNullOrBlank()

    /** Check if string is not blank. */
    fun isNotBlank(str: String?): Boolean = !str.isNullOrBlank()

    // ══════════════════════════════════════════════════════════════
    // ── Collection creation
    // ══════════════════════════════════════════════════════════════

    /** Create a new HashSet. Usage: `$tool.newHashSet("a", "b", "c")` */
    fun newHashSet(vararg items: Any?): HashSet<Any?> = hashSetOf(*items)

    /** Create a new ArrayList. Usage: `$tool.newArrayList("a", "b")` */
    fun newArrayList(vararg items: Any?): ArrayList<Any?> = arrayListOf(*items)

    /** Create a new HashMap. Usage: `$tool.newHashMap()` then `$map.put("key", "val")` */
    fun newHashMap(): HashMap<String, Any?> = hashMapOf()

    // ══════════════════════════════════════════════════════════════
    // ── Type helpers
    // ══════════════════════════════════════════════════════════════

    /** Get short type name: `java.lang.String` → `String`, `Vec<u8>` → `Vec<u8>` */
    fun shortType(fullType: String?): String {
        if (fullType == null) return ""
        val lastDot = fullType.lastIndexOf('.')
        return if (lastDot >= 0) fullType.substring(lastDot + 1) else fullType
    }

    /** Wrap type in Option: `String` → `Option<String>` */
    fun optionOf(type: String?): String = if (type.isNullOrEmpty()) "Option<String>" else "Option<$type>"

    /** Wrap type in Vec: `u8` → `Vec<u8>` */
    fun vecOf(type: String?): String = if (type.isNullOrEmpty()) "Vec<String>" else "Vec<$type>"

    // ══════════════════════════════════════════════════════════════
    // ── Misc
    // ══════════════════════════════════════════════════════════════

    /** Get current time formatted string. */
    fun currTime(pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern))
    }

    /** Get current date: `2025-02-06` */
    fun currDate(): String = currTime("yyyy-MM-dd")

    /** Get current year: `2025` */
    fun currYear(): String = currTime("yyyy")

    /** Convert to string (safe null). */
    fun str(obj: Any?): String = obj?.toString() ?: ""
}
