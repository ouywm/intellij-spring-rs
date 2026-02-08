package com.springrs.plugin.codegen

/**
 * Single source of truth for `mod.rs` and `prelude.rs` content generation.
 *
 * Previously this logic was duplicated across [GenerateSeaOrmAction]
 * and [GenerateSeaOrmDialog].
 */
object ModuleFileGenerator {

    /** Matches `pub mod xxx;` in mod.rs files. */
    private val MOD_REGEX = Regex("""pub\s+mod\s+(\w+)\s*;""")

    /** Matches `pub use super::xxx::Entity as Xxx;` in prelude.rs. */
    private val PRELUDE_REGEX = Regex("""pub\s+use\s+super::(\w+)::Entity\s+as\s+(\w+)\s*;""")

    // ── mod.rs ──

    /** Generate fresh mod.rs content from a list of module names. */
    fun generateModContent(modules: List<String>): String =
        modules.distinct().sorted().joinToString("\n") { "pub mod $it;" } + "\n"

    /** Merge new modules into existing mod.rs content (append-only, never removes). */
    fun mergeModContent(existingContent: String, newModules: List<String>): String {
        val existing = MOD_REGEX.findAll(existingContent).map { it.groupValues[1] }.toMutableSet()
        existing.addAll(newModules)
        return generateModContent(existing.toList())
    }

    // ── prelude.rs ──

    /** Generate fresh prelude.rs content from table infos. */
    fun generatePreludeContent(tables: List<TableInfo>): String =
        tables.sortedBy { it.moduleName }
            .joinToString("\n") { "pub use super::${it.moduleName}::Entity as ${it.entityName};" } + "\n"

    /** Merge new tables into existing prelude.rs content (append-only, never removes). */
    fun mergePreludeContent(existingContent: String, tables: List<TableInfo>): String {
        val existing = PRELUDE_REGEX.findAll(existingContent)
            .associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()
        for (table in tables) {
            existing[table.moduleName] = table.entityName
        }
        return existing.entries.sortedBy { it.key }
            .joinToString("\n") { "pub use super::${it.key}::Entity as ${it.value};" } + "\n"
    }

    /** Extract module names from mod.rs content string (for parsing plan output). */
    fun extractModNames(modContent: String): List<String> =
        MOD_REGEX.findAll(modContent).map { it.groupValues[1] }.toList()

    /** Extract (moduleName -> entityName) pairs from prelude.rs content string. */
    fun extractPreludeEntries(preludeContent: String): Map<String, String> =
        PRELUDE_REGEX.findAll(preludeContent).associate { it.groupValues[1] to it.groupValues[2] }
}
