package com.springrs.plugin.codegen

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.springrs.plugin.codegen.settings.TypeMappingEntry
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DESERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_SERIALIZE

/**
 * Persistent settings for Sea-ORM code generation.
 *
 * Stored per-project in `.idea/springRsCodeGen.xml`.
 * All user choices are remembered across IDE restarts.
 */
@State(
    name = "SpringRsCodeGenSettings",
    storages = [Storage("springRsCodeGen.xml")]
)
@Service(Service.Level.PROJECT)
class CodeGenSettingsState : PersistentStateComponent<CodeGenSettingsState> {

    // ── Layer switches ──
    var generateEntity: Boolean = true
    var generateDto: Boolean = true
    var generateVo: Boolean = false
    var generateService: Boolean = true
    var generateRoute: Boolean = true

    // ── Output directories ──
    var entityOutputDir: String = "src/entity"
    var dtoOutputDir: String = "src/dto"
    var voOutputDir: String = "src/vo"
    var serviceOutputDir: String = "src/service"
    var routeOutputDir: String = "src/route"

    // ── Route prefix ──
    var routePrefix: String = "/api"

    // ── Extra derives ──
    var entityExtraDerives: MutableSet<String> = mutableSetOf(DERIVE_SERIALIZE, DERIVE_DESERIALIZE)
    var dtoExtraDerives: MutableSet<String> = mutableSetOf()
    var voExtraDerives: MutableSet<String> = mutableSetOf()

    // ── Prefix stripping ──
    var tableNamePrefix: String = ""
    var columnNamePrefix: String = ""

    // ── File conflict ──
    var fileConflictStrategy: String = "ASK"  // ASK, SKIP, OVERWRITE, BACKUP

    // ── Per-table overrides (key = original table name) ──
    var tableOverrides: MutableMap<String, TableOverrideConfig> = mutableMapOf()

    // ── Template ──
    var useCustomTemplate: Boolean = false
    var customTemplatePath: String = ".spring-rs/templates"

    // ══════════════════════════════════════════════════════════════
    // ── General Settings (main settings page) ──
    // ══════════════════════════════════════════════════════════════

    /** Default database type: POSTGRESQL, MYSQL, SQLITE */
    var defaultDatabaseType: String = "POSTGRESQL"

    /** Whether to use custom type mappings (override dialect defaults) */
    var useCustomTypeMapping: Boolean = false

    /** Whether to auto-detect and strip table name prefix */
    var autoDetectTablePrefix: Boolean = true

    /** Whether to run rustfmt after code generation */
    var runRustfmtAfterGeneration: Boolean = true

    /** Whether to generate serde derives on Entity */
    var generateSerdeOnEntity: Boolean = true

    /** Whether to generate sea-orm ActiveModel From impl on DTO */
    var generateActiveModelFrom: Boolean = true

    /** Whether to add doc comments with table/column info */
    var generateDocComments: Boolean = true

    /** Whether to generate Query DTO with filter builder */
    var generateQueryDto: Boolean = true

    /** Whether to auto-insert module declarations into mod.rs */
    var autoInsertModDeclaration: Boolean = true

    // ══════════════════════════════════════════════════════════════
    // ── Type Mapping (TypeMapper settings page) ──
    // ══════════════════════════════════════════════════════════════

    /**
     * Custom type mapping entries, grouped by dialect name.
     *
     * Key: dialect name ("PostgreSQL", "MySQL", "SQLite")
     * Value: list of TypeMappingEntry for that dialect
     *
     * When empty, dialect defaults from [TypeMappingEntry.defaultsForDialect] are used.
     */
    var typeMappingGroups: MutableMap<String, MutableList<TypeMappingEntry>> = mutableMapOf()

    /** Currently active type mapping group name. */
    var activeTypeMappingGroup: String = "Default"

    // ══════════════════════════════════════════════════════════════
    // ── Template Groups ──
    // ══════════════════════════════════════════════════════════════

    /**
     * Template groups, each containing templates for all 5 layers.
     *
     * Key: group name ("Default", "Minimal", "Custom", ...)
     * Value: map of template name → template content
     */
    var templateGroupContents: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /**
     * Global variables per template group.
     *
     * Key: group name
     * Value: map of variable name → value
     */
    var templateGroupVariables: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /** Currently active template group name. */
    var activeTemplateGroup: String = "Default"

    override fun getState(): CodeGenSettingsState = this

    override fun loadState(state: CodeGenSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): CodeGenSettingsState {
            return project.getService(CodeGenSettingsState::class.java)
        }
    }
}
