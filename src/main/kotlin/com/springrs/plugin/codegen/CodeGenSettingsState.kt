package com.springrs.plugin.codegen

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
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
