package com.springrs.plugin.codegen

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_BUILDER
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DESERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_JSON_SCHEMA
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_SERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_VALIDATE
import java.io.File

/**
 * Manages Cargo.toml dependencies for code generation.
 *
 * Pure on-demand: only adds dependencies actually needed by the generated code.
 * Detects from two sources:
 * 1. **Derive macros** selected by user (serde, bon, schemars, validator)
 * 2. **Column types** used in tables (chrono, rust_decimal, uuid, serde_json)
 */
object CargoDependencyManager {

    private val LOG = logger<CargoDependencyManager>()

    /** Crate name → TOML dependency line. */
    private data class CrateDep(val crate: String, val toml: String)

    private val CHRONO_DEP = CrateDep("chrono", """chrono = { version = "0.4", features = ["serde"] }""")
    private val SERDE_DEP = CrateDep("serde", """serde = { version = "1", features = ["derive"] }""")
    private val AXUM_VALID_DEP = CrateDep("axum-valid", """axum-valid = { version = "0.24", features = ["validator"] }""")

    /** Derive macro → crate dependency. Built-in derives not listed. */
    private val DERIVE_DEPS: Map<String, CrateDep> = mapOf(
        DERIVE_SERIALIZE to SERDE_DEP,
        DERIVE_DESERIALIZE to SERDE_DEP,
        DERIVE_BUILDER to CrateDep("bon", """bon = "3""""),
        DERIVE_JSON_SCHEMA to CrateDep("schemars", """schemars = { version = "0.8", features = ["chrono", "uuid1"] }"""),
        DERIVE_VALIDATE to CrateDep("validator", """validator = { version = "0.20", features = ["derive"] }"""),
    )

    /** Rust type → crate dependency. Only external crates. */
    private val TYPE_DEPS: Map<String, CrateDep> = mapOf(
        "DateTime" to CHRONO_DEP,
        "DateTimeWithTimeZone" to CHRONO_DEP,
        "Date" to CHRONO_DEP,
        "Time" to CHRONO_DEP,
        "Decimal" to CrateDep("rust_decimal", """rust_decimal = "1""""),
        "Uuid" to CrateDep("uuid", """uuid = { version = "1", features = ["v4", "serde"] }"""),
        "Json" to CrateDep("serde_json", """serde_json = "1""""),
    )

    /**
     * Check and add missing Cargo.toml dependencies.
     *
     * @param project  Current project
     * @param derives  Selected derive macro names
     * @param tables   Tables being generated (to detect column types)
     * @param routeWithValidate  Whether Route layer uses `Valid<Json<T>>` (requires axum-valid)
     * @return List of crate names that were added
     */
    fun ensureDependencies(
        project: Project,
        derives: Set<String>,
        tables: List<TableInfo> = emptyList(),
        routeWithValidate: Boolean = false
    ): List<String> {
        val basePath = project.basePath ?: return emptyList()
        val cargoFile = File(basePath, "Cargo.toml")
        if (!cargoFile.exists()) return emptyList()

        val content = cargoFile.readText()
        val needed = mutableMapOf<String, String>() // crate name → TOML line

        fun require(dep: CrateDep) { needed.putIfAbsent(dep.crate, dep.toml) }

        // 1. From derives
        for (derive in derives) { DERIVE_DEPS[derive]?.let(::require) }

        // 2. From column types
        val allTypes = tables.flatMap { t -> t.columns.map { it.rustType } }.toSet()
        for (type in allTypes) { TYPE_DEPS[type]?.let(::require) }

        // 3. Route with validation → needs axum-valid
        if (routeWithValidate) require(AXUM_VALID_DEP)

        if (needed.isEmpty()) return emptyList()

        // Filter out already present
        val missing = needed.filter { (crate, _) -> !isCratePresent(content, crate) }
        if (missing.isEmpty()) return emptyList()

        // Write
        cargoFile.writeText(addDependencies(content, missing.values.toList()))
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(cargoFile)?.let {
            VfsUtil.markDirtyAndRefresh(false, false, false, it)
        }

        val added = missing.keys.toList()
        LOG.info("Added to Cargo.toml: ${added.joinToString(", ")}")
        return added
    }

    private fun isCratePresent(content: String, crate: String): Boolean =
        Regex("""(?m)^\s*${Regex.escape(crate)}\s*=""").containsMatchIn(content)

    private fun addDependencies(content: String, lines: List<String>): String {
        val block = "\n# Added by spring-rs code generator\n${lines.joinToString("\n")}\n"
        val header = Regex("""(?m)^\[dependencies]""").find(content)

        if (header != null) {
            val next = Regex("""(?m)^\[(?!dependencies])""").find(content, header.range.last + 1)
            val pos = next?.range?.first ?: content.length
            return buildString {
                append(content, 0, pos)
                if (pos > 0 && content[pos - 1] != '\n') append('\n')
                append(block)
                if (pos < content.length) append(content, pos, content.length)
            }
        }

        return buildString {
            append(content)
            if (content.isNotEmpty() && !content.endsWith("\n")) append('\n')
            append("\n[dependencies]\n")
            append(lines.joinToString("\n"))
            append('\n')
        }
    }
}
