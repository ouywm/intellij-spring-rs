package com.springrs.plugin.codegen.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.codegen.CodeGenSettingsState
import com.springrs.plugin.codegen.dialect.DatabaseType
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Main settings page for Sea-ORM code generation.
 *
 * Appears under: Settings → spring-rs → Code Generation → General
 *
 * Sections:
 * - **Database**: Default database type selection
 * - **General**: Common code generation options
 * - **Output**: Default output directories
 * - **Formatting**: Code formatting options
 */
class CodeGenGeneralConfigurable(private val project: Project) : Configurable {

    private var mainPanel: JPanel? = null

    // Database
    private var dbTypeCombo: ComboBox<String>? = null

    // General
    private var autoDetectPrefixCb: JBCheckBox? = null
    private var generateSerdeCb: JBCheckBox? = null
    private var generateActiveModelFromCb: JBCheckBox? = null
    private var generateDocCommentsCb: JBCheckBox? = null
    private var generateQueryDtoCb: JBCheckBox? = null
    private var autoInsertModCb: JBCheckBox? = null
    private var useCustomTypeMappingCb: JBCheckBox? = null

    // Output directories
    private var entityDirField: JBTextField? = null
    private var dtoDirField: JBTextField? = null
    private var voDirField: JBTextField? = null
    private var serviceDirField: JBTextField? = null
    private var routeDirField: JBTextField? = null

    // Prefix
    private var tablePrefixField: JBTextField? = null
    private var columnPrefixField: JBTextField? = null
    private var routePrefixField: JBTextField? = null

    // Formatting
    private var runRustfmtCb: JBCheckBox? = null

    // Conflict
    private var conflictStrategyCombo: ComboBox<String>? = null

    override fun getDisplayName(): String = SpringRsBundle.message("codegen.settings.general.title")

    override fun createComponent(): JComponent {
        val settings = CodeGenSettingsState.getInstance(project)

        // ── Database Type ──
        dbTypeCombo = ComboBox(
            DatabaseType.entries
                .filter { it != DatabaseType.UNKNOWN }
                .map { it.displayName }
                .toTypedArray()
        )

        val dbPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel(SpringRsBundle.message("codegen.settings.general.db.type")),
                dbTypeCombo!!
            )
            .panel
        dbPanel.border = IdeBorderFactory.createTitledBorder(
            SpringRsBundle.message("codegen.settings.general.db.section")
        )

        // ── General Options ──
        autoDetectPrefixCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.auto.detect.prefix"),
            settings.autoDetectTablePrefix
        )
        generateSerdeCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.generate.serde"),
            settings.generateSerdeOnEntity
        )
        generateActiveModelFromCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.generate.active.model"),
            settings.generateActiveModelFrom
        )
        generateDocCommentsCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.generate.doc"),
            settings.generateDocComments
        )
        generateQueryDtoCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.generate.query.dto"),
            settings.generateQueryDto
        )
        autoInsertModCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.auto.insert.mod"),
            settings.autoInsertModDeclaration
        )
        useCustomTypeMappingCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.use.custom.type.mapping"),
            settings.useCustomTypeMapping
        )

        val generalPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = IdeBorderFactory.createTitledBorder(
                SpringRsBundle.message("codegen.settings.general.options.section")
            )
            add(autoDetectPrefixCb)
            add(generateSerdeCb)
            add(generateActiveModelFromCb)
            add(generateDocCommentsCb)
            add(generateQueryDtoCb)
            add(autoInsertModCb)
            add(useCustomTypeMappingCb)
        }

        // ── Output Directories ──
        entityDirField = JBTextField(settings.entityOutputDir)
        dtoDirField = JBTextField(settings.dtoOutputDir)
        voDirField = JBTextField(settings.voOutputDir)
        serviceDirField = JBTextField(settings.serviceOutputDir)
        routeDirField = JBTextField(settings.routeOutputDir)

        val outputPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Entity:", entityDirField!!)
            .addLabeledComponent("DTO:", dtoDirField!!)
            .addLabeledComponent("VO:", voDirField!!)
            .addLabeledComponent("Service:", serviceDirField!!)
            .addLabeledComponent("Route:", routeDirField!!)
            .panel
        outputPanel.border = IdeBorderFactory.createTitledBorder(
            SpringRsBundle.message("codegen.settings.general.output.section")
        )

        // ── Prefix & Route ──
        tablePrefixField = JBTextField(settings.tableNamePrefix, 15)
        columnPrefixField = JBTextField(settings.columnNamePrefix, 15)
        routePrefixField = JBTextField(settings.routePrefix, 15)

        val prefixPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                SpringRsBundle.message("codegen.settings.general.table.prefix"),
                tablePrefixField!!
            )
            .addLabeledComponent(
                SpringRsBundle.message("codegen.settings.general.column.prefix"),
                columnPrefixField!!
            )
            .addLabeledComponent(
                SpringRsBundle.message("codegen.settings.general.route.prefix"),
                routePrefixField!!
            )
            .panel
        prefixPanel.border = IdeBorderFactory.createTitledBorder(
            SpringRsBundle.message("codegen.settings.general.prefix.section")
        )

        // ── Formatting ──
        runRustfmtCb = JBCheckBox(
            SpringRsBundle.message("codegen.settings.general.run.rustfmt"),
            settings.runRustfmtAfterGeneration
        )

        conflictStrategyCombo = ComboBox(arrayOf("ASK", "SKIP", "OVERWRITE", "BACKUP"))

        val formatPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = IdeBorderFactory.createTitledBorder(
                SpringRsBundle.message("codegen.settings.general.format.section")
            )
            add(runRustfmtCb)
            add(Box.createVerticalStrut(4))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel(SpringRsBundle.message("codegen.settings.general.conflict.strategy")))
                add(Box.createHorizontalStrut(8))
                add(conflictStrategyCombo)
            })
        }

        // ── Assemble (GridBagLayout for proper scrolling) ──
        val content = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(4, 0)
        }

        content.add(dbPanel, gbc.also { it.gridy = 0 })
        content.add(generalPanel, gbc.also { it.gridy = 1 })
        content.add(outputPanel, gbc.also { it.gridy = 2 })
        content.add(prefixPanel, gbc.also { it.gridy = 3 })
        content.add(formatPanel, gbc.also { it.gridy = 4 })

        // Filler to push content to top
        content.add(JPanel().apply { preferredSize = Dimension(0, 0) },
            gbc.also { it.gridy = 5; it.weighty = 1.0 })

        mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JBScrollPane(content).apply {
                border = BorderFactory.createEmptyBorder()
                verticalScrollBar.unitIncrement = 16
            }, BorderLayout.CENTER)
        }

        // Load values
        reset()

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = CodeGenSettingsState.getInstance(project)
        return dbTypeCombo?.selectedItem?.toString() != settings.defaultDatabaseType.let {
            DatabaseType.valueOf(it).displayName
        }
                || autoDetectPrefixCb?.isSelected != settings.autoDetectTablePrefix
                || generateSerdeCb?.isSelected != settings.generateSerdeOnEntity
                || generateActiveModelFromCb?.isSelected != settings.generateActiveModelFrom
                || generateDocCommentsCb?.isSelected != settings.generateDocComments
                || generateQueryDtoCb?.isSelected != settings.generateQueryDto
                || autoInsertModCb?.isSelected != settings.autoInsertModDeclaration
                || useCustomTypeMappingCb?.isSelected != settings.useCustomTypeMapping
                || entityDirField?.text != settings.entityOutputDir
                || dtoDirField?.text != settings.dtoOutputDir
                || voDirField?.text != settings.voOutputDir
                || serviceDirField?.text != settings.serviceOutputDir
                || routeDirField?.text != settings.routeOutputDir
                || tablePrefixField?.text != settings.tableNamePrefix
                || columnPrefixField?.text != settings.columnNamePrefix
                || routePrefixField?.text != settings.routePrefix
                || runRustfmtCb?.isSelected != settings.runRustfmtAfterGeneration
                || conflictStrategyCombo?.selectedItem?.toString() != settings.fileConflictStrategy
    }

    override fun apply() {
        val settings = CodeGenSettingsState.getInstance(project)

        settings.defaultDatabaseType = dbTypeToEnum(dbTypeCombo?.selectedItem?.toString())
        settings.autoDetectTablePrefix = autoDetectPrefixCb?.isSelected ?: true
        settings.generateSerdeOnEntity = generateSerdeCb?.isSelected ?: true
        settings.generateActiveModelFrom = generateActiveModelFromCb?.isSelected ?: true
        settings.generateDocComments = generateDocCommentsCb?.isSelected ?: true
        settings.generateQueryDto = generateQueryDtoCb?.isSelected ?: true
        settings.autoInsertModDeclaration = autoInsertModCb?.isSelected ?: true
        settings.useCustomTypeMapping = useCustomTypeMappingCb?.isSelected ?: false

        settings.entityOutputDir = entityDirField?.text ?: "src/entity"
        settings.dtoOutputDir = dtoDirField?.text ?: "src/dto"
        settings.voOutputDir = voDirField?.text ?: "src/vo"
        settings.serviceOutputDir = serviceDirField?.text ?: "src/service"
        settings.routeOutputDir = routeDirField?.text ?: "src/route"

        settings.tableNamePrefix = tablePrefixField?.text ?: ""
        settings.columnNamePrefix = columnPrefixField?.text ?: ""
        settings.routePrefix = routePrefixField?.text ?: "/api"

        settings.runRustfmtAfterGeneration = runRustfmtCb?.isSelected ?: true
        settings.fileConflictStrategy = conflictStrategyCombo?.selectedItem?.toString() ?: "ASK"
    }

    override fun reset() {
        val settings = CodeGenSettingsState.getInstance(project)

        val dbDisplay = try {
            DatabaseType.valueOf(settings.defaultDatabaseType).displayName
        } catch (_: Exception) {
            DatabaseType.POSTGRESQL.displayName
        }
        dbTypeCombo?.selectedItem = dbDisplay

        autoDetectPrefixCb?.isSelected = settings.autoDetectTablePrefix
        generateSerdeCb?.isSelected = settings.generateSerdeOnEntity
        generateActiveModelFromCb?.isSelected = settings.generateActiveModelFrom
        generateDocCommentsCb?.isSelected = settings.generateDocComments
        generateQueryDtoCb?.isSelected = settings.generateQueryDto
        autoInsertModCb?.isSelected = settings.autoInsertModDeclaration
        useCustomTypeMappingCb?.isSelected = settings.useCustomTypeMapping

        entityDirField?.text = settings.entityOutputDir
        dtoDirField?.text = settings.dtoOutputDir
        voDirField?.text = settings.voOutputDir
        serviceDirField?.text = settings.serviceOutputDir
        routeDirField?.text = settings.routeOutputDir

        tablePrefixField?.text = settings.tableNamePrefix
        columnPrefixField?.text = settings.columnNamePrefix
        routePrefixField?.text = settings.routePrefix

        runRustfmtCb?.isSelected = settings.runRustfmtAfterGeneration
        conflictStrategyCombo?.selectedItem = settings.fileConflictStrategy
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    private fun dbTypeToEnum(displayName: String?): String {
        return DatabaseType.entries
            .find { it.displayName == displayName }
            ?.name ?: DatabaseType.POSTGRESQL.name
    }
}
