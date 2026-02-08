package com.springrs.plugin.codegen

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.codegen.layer.DtoLayer
import com.springrs.plugin.codegen.layer.EntityLayer
import com.springrs.plugin.codegen.layer.RouteLayer
import com.springrs.plugin.codegen.layer.ServiceLayer
import com.springrs.plugin.codegen.layer.VoLayer
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_BUILDER
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DEFAULT
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DESERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_HASH
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_JSON_SCHEMA
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_SERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_VALIDATE
import java.awt.*
import javax.swing.*
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

/**
 * Configuration dialog for Sea-ORM code generation.
 *
 * UI layout inspired by MyBatisCodeHelper-Pro:
 * - Top: entity name / primary key / table info (form layout)
 * - Output: directory fields with browse buttons (form layout)
 * - Config: derive macro checkboxes (3-column grid)
 * - Bottom tabs: per-layer toggles + layer-specific options
 *
 * Multi-table mode: left-side table list + right-side per-table config panel.
 * Each table has independent layer switches, entity name, and route prefix.
 */
class GenerateSeaOrmDialog(
    private val project: Project,
    private val tableNames: List<String>,
    private val tableInfos: List<TableInfo> = emptyList()
) : DialogWrapper(project) {

    private val settings = CodeGenSettingsState.getInstance(project)

    /** Per-table independent configuration (for multi-table mode). */
    private data class PerTableConfig(
        var entityName: String,
        var generateEntity: Boolean,
        var generateDto: Boolean,
        var generateVo: Boolean,
        var generateService: Boolean,
        var generateRoute: Boolean,
        var routePrefix: String,
        // output directories
        var entityOutputDir: String,
        var dtoOutputDir: String,
        var voOutputDir: String,
        var serviceOutputDir: String,
        var routeOutputDir: String,
        // extra derives
        var entityExtraDerives: MutableSet<String>,
        var dtoExtraDerives: MutableSet<String>,
        var voExtraDerives: MutableSet<String>
    )

    private val isMultiTable = tableNames.size > 1
    private val tableConfigs: MutableMap<String, PerTableConfig> = mutableMapOf<String, PerTableConfig>().also { map ->
        for (table in tableInfos) {
            val override = settings.tableOverrides[table.name]
            map[table.name] = PerTableConfig(
                entityName = override?.customEntityName
                    ?: computeEntityName(table.name, settings.tableNamePrefix),
                generateEntity = override?.generateEntity ?: settings.generateEntity,
                generateDto = override?.generateDto ?: settings.generateDto,
                generateVo = override?.generateVo ?: settings.generateVo,
                generateService = override?.generateService ?: settings.generateService,
                generateRoute = override?.generateRoute ?: settings.generateRoute,
                routePrefix = settings.routePrefix,
                entityOutputDir = override?.entityOutputDir ?: settings.entityOutputDir,
                dtoOutputDir = override?.dtoOutputDir ?: settings.dtoOutputDir,
                voOutputDir = override?.voOutputDir ?: settings.voOutputDir,
                serviceOutputDir = override?.serviceOutputDir ?: settings.serviceOutputDir,
                routeOutputDir = override?.routeOutputDir ?: settings.routeOutputDir,
                entityExtraDerives = override?.entityExtraDerives?.toMutableSet() ?: settings.entityExtraDerives.toMutableSet(),
                dtoExtraDerives = override?.dtoExtraDerives?.toMutableSet() ?: settings.dtoExtraDerives.toMutableSet(),
                voExtraDerives = override?.voExtraDerives?.toMutableSet() ?: settings.voExtraDerives.toMutableSet()
            )
        }
    }
    private var currentTableName: String? = null
    /** Guard flag: prevents save/load re-entrancy during config switching. */
    private var isLoadingConfig = false
    private lateinit var tableList: JBList<String>

    // ── Top: table info ──
    private val entityNameField = JBTextField(computeEntityName(tableInfos.firstOrNull()?.name, settings.tableNamePrefix))
    private val tableNameField = JBTextField(tableNames.joinToString(", ")).apply { isEditable = false }
    private val tablePrefixField = JBTextField(settings.tableNamePrefix)
    private val columnPrefixField = JBTextField(settings.columnNamePrefix)

    // ── Output directories ──
    // Base dirs (without schema suffix) — the actual stored values.
    private var entityBaseDir = settings.entityOutputDir
    private var dtoBaseDir = settings.dtoOutputDir
    private var voBaseDir = settings.voOutputDir
    private var serviceBaseDir = settings.serviceOutputDir
    private var routeBaseDir = settings.routeOutputDir

    private val entityDirField = createDirField(settings.entityOutputDir)
    private val dtoDirField = createDirField(settings.dtoOutputDir)
    private val voDirField = createDirField(settings.voOutputDir)
    private val serviceDirField = createDirField(settings.serviceOutputDir)
    private val routeDirField = createDirField(settings.routeOutputDir)

    // ── Config: entity derives (3-column grid) ──
    private val entitySerdeCheckBox = JBCheckBox("$DERIVE_SERIALIZE, $DERIVE_DESERIALIZE",
        settings.entityExtraDerives.containsAll(listOf(DERIVE_SERIALIZE, DERIVE_DESERIALIZE)))
    private val entityBuilderCheckBox = JBCheckBox("$DERIVE_BUILDER (bon)", DERIVE_BUILDER in settings.entityExtraDerives)
    private val entityDefaultCheckBox = JBCheckBox(DERIVE_DEFAULT, DERIVE_DEFAULT in settings.entityExtraDerives)
    private val entityHashCheckBox = JBCheckBox(DERIVE_HASH, DERIVE_HASH in settings.entityExtraDerives)

    // ── Tab: layer toggles ──
    private val entityCheckBox = JBCheckBox(SpringRsBundle.message("codegen.entity.generate"), settings.generateEntity)
    private val dtoCheckBox = JBCheckBox(SpringRsBundle.message("codegen.dto.generate"), settings.generateDto)
    private val voCheckBox = JBCheckBox(SpringRsBundle.message("codegen.vo.generate"), settings.generateVo)
    private val serviceCheckBox = JBCheckBox(SpringRsBundle.message("codegen.service.generate"), settings.generateService)
    private val routeCheckBox = JBCheckBox(SpringRsBundle.message("codegen.route.generate"), settings.generateRoute)

    // ── Tab: DTO derives ──
    private val dtoBuilderCheckBox = JBCheckBox("$DERIVE_BUILDER (bon)", DERIVE_BUILDER in settings.dtoExtraDerives)
    private val dtoValidateCheckBox = JBCheckBox("$DERIVE_VALIDATE (validator)", DERIVE_VALIDATE in settings.dtoExtraDerives)

    // ── Tab: VO derives ──
    private val voBuilderCheckBox = JBCheckBox("$DERIVE_BUILDER (bon)", DERIVE_BUILDER in settings.voExtraDerives)
    private val voJsonSchemaCheckBox = JBCheckBox("$DERIVE_JSON_SCHEMA (schemars)", DERIVE_JSON_SCHEMA in settings.voExtraDerives)

    // ── Tab: Route prefix ──
    private val routePrefixField = JBTextField(settings.routePrefix)

    /** Files edited in preview. If non-null, Generate uses these. */
    var previewEditedFiles: List<GeneratedFile>? = null
        private set

    init {
        title = if (tableNames.size == 1) {
            SpringRsBundle.message("codegen.dialog.title.single", tableNames.first())
        } else {
            SpringRsBundle.message("codegen.dialog.title")
        }
        setOKButtonText(SpringRsBundle.message("codegen.button.generate"))
        init()

        // Live-update entity name when prefix changes
        tablePrefixField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateEntityName()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateEntityName()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateEntityName()
        })
    }

    private fun updateEntityName() {
        if (isLoadingConfig) return
        if (isMultiTable) {
            val prefix = tablePrefixField.text
            for (table in tableInfos) {
                val config = tableConfigs[table.name] ?: continue
                val override = settings.tableOverrides[table.name]
                if (override?.customEntityName == null) {
                    config.entityName = computeEntityName(table.name, prefix)
                }
            }
            val current = currentTableName
            if (current != null) {
                entityNameField.text = tableConfigs[current]?.entityName ?: ""
            }
        } else {
            val name = tableInfos.firstOrNull()?.name ?: return
            entityNameField.text = computeEntityName(name, tablePrefixField.text)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Layout
    // ══════════════════════════════════════════════════════════════

    override fun createCenterPanel(): JComponent {
        val configPanel = createConfigPanel()

        if (!isMultiTable) {
            configPanel.preferredSize = Dimension(660, 560)
            // Single table: show effective path with schema if applicable
            currentTableName = tableNames.firstOrNull()
            val schema = currentSchemaSubDir()
            if (schema.isNotEmpty()) applySchemaToFields(schema)
            return configPanel
        }

        // ── Multi-table mode: split pane with custom divider ──
        tableList = JBList(tableNames).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            addListSelectionListener { e ->
                if (e.valueIsAdjusting || isLoadingConfig) return@addListSelectionListener
                val newTable = selectedValue ?: return@addListSelectionListener
                if (newTable == currentTableName) return@addListSelectionListener
                saveCurrentTableConfig()
                loadTableConfig(newTable)
            }
        }

        val listPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(SpringRsBundle.message("codegen.table.list.title"))
            add(JScrollPane(tableList), BorderLayout.CENTER)
            minimumSize = Dimension(160, 0)
            preferredSize = Dimension(180, 0)
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, configPanel).apply {
            dividerLocation = 180
            isOneTouchExpandable = false
            border = null

            // Custom divider: thin line + drag handle dots (same style as JsonToStruct)
            setUI(object : BasicSplitPaneUI() {
                override fun createDefaultDivider(): BasicSplitPaneDivider {
                    return object : BasicSplitPaneDivider(this) {
                        init { border = null }

                        override fun paint(g: Graphics) {
                            val g2 = g as Graphics2D
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = JBColor.border()
                            val lineX = width / 2
                            g2.drawLine(lineX, 0, lineX, height)
                            val centerY = height / 2
                            val dotRadius = JBUI.scale(2)
                            val dotSpacing = JBUI.scale(6)
                            g2.color = JBColor.GRAY
                            for (i in -1..1) {
                                val y = centerY + i * dotSpacing
                                g2.fillOval(lineX - dotRadius, y - dotRadius, dotRadius * 2, dotRadius * 2)
                            }
                        }
                    }
                }
            })
            dividerSize = JBUI.scale(8)
        }
        splitPane.preferredSize = Dimension(880, 580)

        // Default select first table
        currentTableName = tableNames.first()
        loadTableConfig(currentTableName!!)
        tableList.selectedIndex = 0

        return splitPane
    }

    /** Build the main config panel (shared by both single and multi-table modes). */
    private fun createConfigPanel(): JPanel {
        val root = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0; insets = JBUI.insets(2)
        }

        // ── 1. Top: table info (form layout) ──
        gbc.gridy = 0
        root.add(createTableInfoSection(), gbc)

        // ── 2. Output directories (form layout) ──
        gbc.gridy = 1
        root.add(createOutputSection(), gbc)

        // ── 3. Config: entity derives (3-column grid) ──
        gbc.gridy = 2
        root.add(createConfigSection(), gbc)

        // ── 4. Bottom tabs (layer-specific settings) ──
        gbc.gridy = 3; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        root.add(createLayerTabs(), gbc)

        return root
    }

    /**
     * Top section: entity name, primary key, table name.
     * Form layout matching MyBatis style.
     */
    private fun createTableInfoSection(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.emptyBottom(4)
        var row = 0

        // Entity name + "Customize Columns" + "Customize Relations" buttons
        val entityNameRow = JPanel(BorderLayout(8, 0)).apply {
            add(entityNameField, BorderLayout.CENTER)
            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            btnPanel.add(JButton(SpringRsBundle.message("codegen.customize.button")).apply {
                addActionListener { openColumnCustomizeDialog() }
            })
            btnPanel.add(JButton(SpringRsBundle.message("codegen.relation.button")).apply {
                addActionListener { openRelationCustomizeDialog() }
            })
            add(btnPanel, BorderLayout.EAST)
        }
        addFormRow(panel, row++, SpringRsBundle.message("codegen.field.entity.name"), entityNameRow)

        // Prefix stripping — always show, user manually inputs
        val prefixPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel(SpringRsBundle.message("codegen.field.table.prefix")))
            add(tablePrefixField.apply { columns = 10 })
            add(Box.createHorizontalStrut(12))
            add(JBLabel(SpringRsBundle.message("codegen.field.column.prefix")))
            add(columnPrefixField.apply { columns = 10 })
        }
        addFormRow(panel, row, SpringRsBundle.message("codegen.field.prefix.strip"), prefixPanel)
        return panel
    }

    private fun openColumnCustomizeDialog() {
        val table = if (isMultiTable) {
            tableInfos.find { it.name == currentTableName }
        } else {
            tableInfos.firstOrNull()
        } ?: return
        val override = settings.tableOverrides.getOrPut(table.name) { TableOverrideConfig() }
        val dialog = ColumnCustomizeDialog(project, table.name, table.columns, override)
        if (dialog.showAndGet()) {
            val customName = override.customEntityName
            if (customName != null) {
                entityNameField.text = customName
                if (isMultiTable) {
                    tableConfigs[table.name]?.entityName = customName
                }
            }
        }
    }

    private fun openRelationCustomizeDialog() {
        val table = if (isMultiTable) {
            tableInfos.find { it.name == currentTableName }
        } else {
            tableInfos.firstOrNull()
        } ?: return

        val allTableColumns = tableInfos.associate { it.name to it.columns }
        val selectedTableNames = tableInfos.map { it.name }.toSet()
        // Collect all tables' custom relations for bidirectional view,
        // filtered to only include relations where BOTH tables are in the current selection
        val allRelations = mutableMapOf<String, List<CustomRelationConfig>>()
        for (t in tableInfos) {
            val rels = settings.tableOverrides[t.name]?.customRelations
                ?.filter { it.targetTable in selectedTableNames }
            if (rels != null && rels.isNotEmpty()) {
                allRelations[t.name] = rels.toList()
            }
        }
        val dialog = RelationCustomizeDialog(
            project, table.name,
            tableInfos.map { it.name },
            allTableColumns,
            allRelations
        )
        if (dialog.showAndGet()) {
            val updatedRelations = dialog.getAllRelations()
            // Collect all tables that were previously involved with the current table
            val involvedTables = mutableSetOf<String>()
            for ((ownerTable, rels) in allRelations) {
                if (ownerTable == table.name || rels.any { it.targetTable == table.name }) {
                    involvedTables.add(ownerTable)
                }
            }
            // Also include tables in the updated result
            involvedTables.addAll(updatedRelations.keys)
            // Clear involved tables' relations and re-populate from dialog result
            for (tName in involvedTables) {
                val override = settings.tableOverrides.getOrPut(tName) { TableOverrideConfig() }
                // Keep relations NOT involving the current table (they weren't shown in dialog)
                val kept = override.customRelations.filter { rel ->
                    !tName.equals(table.name, ignoreCase = true)
                        && !rel.targetTable.equals(table.name, ignoreCase = true)
                }
                override.customRelations.clear()
                override.customRelations.addAll(kept)
                // Add back the relations from dialog
                val fromDialog = updatedRelations[tName] ?: emptyList()
                override.customRelations.addAll(fromDialog)
            }
        }
    }

    /**
     * Output section: directory fields with browse buttons.
     * Fields show effective path including schema subdirectory for the current table.
     */
    private fun createOutputSection(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(SpringRsBundle.message("codegen.output.title"))
        var row = 0

        addFormRow(panel, row++, "entity folder:", entityDirField)
        addFormRow(panel, row++, "dto folder:", dtoDirField)
        addFormRow(panel, row++, "vo folder:", voDirField)
        addFormRow(panel, row++, "service folder:", serviceDirField)
        addFormRow(panel, row, "route folder:", routeDirField)
        return panel
    }

    /**
     * Get the schema subdirectory for the currently selected table.
     * Returns empty string for tables in the default (public) schema.
     */
    private fun currentSchemaSubDir(): String {
        val table = if (isMultiTable) {
            tableInfos.find { it.name == currentTableName }
        } else {
            tableInfos.firstOrNull()
        }
        return table?.schemaSubDir ?: ""
    }

    /**
     * Save current dir field texts back to baseDirs (stripping the old schema suffix).
     */
    private fun saveDirBasesFromFields() {
        val schema = currentSchemaSubDir()
        val suffix = if (schema.isNotEmpty()) "/$schema" else ""
        entityBaseDir = entityDirField.text.removeSuffix(suffix)
        dtoBaseDir = dtoDirField.text.removeSuffix(suffix)
        voBaseDir = voDirField.text.removeSuffix(suffix)
        serviceBaseDir = serviceDirField.text.removeSuffix(suffix)
        routeBaseDir = routeDirField.text.removeSuffix(suffix)
    }

    /**
     * Update dir field texts to show effective paths for the given schema subdirectory.
     */
    private fun applySchemaToFields(schema: String) {
        if (schema.isNotEmpty()) {
            entityDirField.text = "$entityBaseDir/$schema"
            dtoDirField.text = "$dtoBaseDir/$schema"
            voDirField.text = "$voBaseDir/$schema"
            serviceDirField.text = "$serviceBaseDir/$schema"
            routeDirField.text = "$routeBaseDir/$schema"
        } else {
            entityDirField.text = entityBaseDir
            dtoDirField.text = dtoBaseDir
            voDirField.text = voBaseDir
            serviceDirField.text = serviceBaseDir
            routeDirField.text = routeBaseDir
        }
    }

    /**
     * Config section: entity derive macros in a 3-column checkbox grid.
     */
    private fun createConfigSection(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(SpringRsBundle.message("codegen.config.title"))
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 8)
        }

        gbc.gridy = 0
        gbc.gridx = 0; panel.add(entitySerdeCheckBox, gbc)
        gbc.gridx = 1; panel.add(entityBuilderCheckBox, gbc)
        gbc.gridx = 2; panel.add(entityDefaultCheckBox, gbc)

        gbc.gridy = 1
        gbc.gridx = 0; panel.add(entityHashCheckBox, gbc)

        return panel
    }

    /**
     * Bottom tabs: per-layer generate toggle + layer-specific options.
     */
    private fun createLayerTabs(): JBTabbedPane {
        val tabs = JBTabbedPane()

        tabs.addTab("Entity", createTabPanel(entityCheckBox))
        tabs.addTab("DTO", createTabPanel(dtoCheckBox,
            checkboxRow(dtoBuilderCheckBox, dtoValidateCheckBox)))
        tabs.addTab("VO", createTabPanel(voCheckBox,
            checkboxRow(voBuilderCheckBox, voJsonSchemaCheckBox)))
        tabs.addTab("Service", createTabPanel(serviceCheckBox))
        tabs.addTab("Route", createTabPanel(routeCheckBox, JPanel(BorderLayout(8, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(SpringRsBundle.message("codegen.route.prefix")), BorderLayout.WEST)
            add(routePrefixField, BorderLayout.CENTER)
            maximumSize = Dimension(400, preferredSize.height)
        }))

        return tabs
    }

    // ══════════════════════════════════════════════════════════════
    // ── Multi-table: save / load per-table config
    // ══════════════════════════════════════════════════════════════

    /** Save current UI control values into tableConfigs for the current table. */
    private fun saveCurrentTableConfig() {
        if (isLoadingConfig) return
        val name = currentTableName ?: return
        val config = tableConfigs[name] ?: return
        config.entityName = entityNameField.text
        config.generateEntity = entityCheckBox.isSelected
        config.generateDto = dtoCheckBox.isSelected
        config.generateVo = voCheckBox.isSelected
        config.generateService = serviceCheckBox.isSelected
        config.generateRoute = routeCheckBox.isSelected
        config.routePrefix = routePrefixField.text
        // Save output directories (strip schema suffix)
        val schema = currentSchemaSubDir()
        val suffix = if (schema.isNotEmpty()) "/$schema" else ""
        config.entityOutputDir = entityDirField.text.removeSuffix(suffix)
        config.dtoOutputDir = dtoDirField.text.removeSuffix(suffix)
        config.voOutputDir = voDirField.text.removeSuffix(suffix)
        config.serviceOutputDir = serviceDirField.text.removeSuffix(suffix)
        config.routeOutputDir = routeDirField.text.removeSuffix(suffix)
        // Save derives
        config.entityExtraDerives = collectDerives(*entityDeriveMappings)
        config.dtoExtraDerives = collectDerives(*dtoDeriveMappings)
        config.voExtraDerives = collectDerives(*voDeriveMappings)
    }

    /** Load per-table config into UI controls for the given table. */
    private fun loadTableConfig(tableName: String) {
        isLoadingConfig = true
        try {
            currentTableName = tableName
            val config = tableConfigs[tableName] ?: return
            entityNameField.text = config.entityName
            entityCheckBox.isSelected = config.generateEntity
            dtoCheckBox.isSelected = config.generateDto
            voCheckBox.isSelected = config.generateVo
            serviceCheckBox.isSelected = config.generateService
            routeCheckBox.isSelected = config.generateRoute
            routePrefixField.text = config.routePrefix
            // Load per-table output directories into base vars and apply schema suffix
            entityBaseDir = config.entityOutputDir
            dtoBaseDir = config.dtoOutputDir
            voBaseDir = config.voOutputDir
            serviceBaseDir = config.serviceOutputDir
            routeBaseDir = config.routeOutputDir
            applySchemaToFields(currentSchemaSubDir())
            // Load per-table derives into checkboxes
            loadDerivesToCheckboxes(config.entityExtraDerives, *entityDeriveMappings)
            loadDerivesToCheckboxes(config.dtoExtraDerives, *dtoDeriveMappings)
            loadDerivesToCheckboxes(config.voExtraDerives, *voDeriveMappings)
        } finally {
            isLoadingConfig = false
        }
    }

    /** Set checkbox states from a derives set. */
    private fun loadDerivesToCheckboxes(derives: Set<String>, vararg mappings: Pair<JBCheckBox, List<String>>) {
        for ((cb, names) in mappings) {
            cb.isSelected = derives.containsAll(names)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Form helpers (MyBatis-style aligned rows)
    // ══════════════════════════════════════════════════════════════

    private fun addFormRow(panel: JPanel, row: Int, label: String, field: JComponent) {
        val gbc = GridBagConstraints()
        gbc.gridy = row; gbc.insets = JBUI.insets(3, 6)

        gbc.gridx = 0; gbc.anchor = GridBagConstraints.EAST
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel(label), gbc)

        gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(field, gbc)
    }

    private fun createTabPanel(generateCheckBox: JBCheckBox, vararg extras: JComponent): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
        add(wrapLeft(generateCheckBox))
        extras.forEach { add(Box.createVerticalStrut(4)); add(it) }
    }

    private fun checkboxRow(vararg cbs: JBCheckBox): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        cbs.forEach { add(it) }
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun wrapLeft(comp: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)).apply {
        alignmentX = Component.LEFT_ALIGNMENT; add(comp)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun createDirField(initialValue: String): TextFieldWithBrowseButton =
        TextFieldWithBrowseButton().apply {
            text = initialValue
            addActionListener {
                val basePath = project.basePath ?: return@addActionListener
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle(SpringRsBundle.message("codegen.dir.chooser.title"))
                val chosen = FileChooser.chooseFile(descriptor, project, baseDir) ?: return@addActionListener
                text = if (chosen.path.startsWith(basePath))
                    chosen.path.removePrefix(basePath).removePrefix("/").removePrefix("\\")
                else chosen.path
            }
        }

    // ══════════════════════════════════════════════════════════════
    // ── Preview
    // ══════════════════════════════════════════════════════════════

    override fun createLeftSideActions(): Array<Action> {
        if (tableInfos.isEmpty()) return emptyArray()
        return arrayOf(object : DialogWrapperAction(SpringRsBundle.message("codegen.button.preview")) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                saveSettings()
                val files = buildPreviewFiles()
                if (files.isEmpty()) return
                val preview = CodePreviewDialog(project, files)
                if (preview.showAndGet()) {
                    previewEditedFiles = preview.getEditedFiles()
                    close(OK_EXIT_CODE)
                }
            }
        })
    }

    /** Re-apply current prefix inputs to tableInfos (data class copy). */
    private fun currentTableInfos(): List<TableInfo> {
        val tp = tablePrefixField.text.trim()
        val cp = columnPrefixField.text.trim()
        return tableInfos.map { it.copy(tableNamePrefix = tp, columnNamePrefix = cp) }
    }

    private fun buildPreviewFiles(): List<GeneratedFile> {
        // Ensure current table's UI state is persisted before reading tableConfigs
        if (isMultiTable) {
            saveCurrentTableConfig()
            saveDirBasesFromFields()
        }
        val layerConfigs = buildLayerConfigs()
        return CodegenPlan.plan(layerConfigs, currentTableInfos(), project)
    }

    /** Build layer configs from current dialog state (shared by preview and action). */
    private fun buildLayerConfigs(): List<LayerConfig> {
        // Collect custom relations for preview + auto-generate reverse relations
        val customRelationsMap = mutableMapOf<String, MutableList<RelationInfo>>()
        val selectedTableNames = tableInfos.map { it.name.lowercase() }.toSet()
        for (table in tableInfos) {
            val override = settings.tableOverrides[table.name] ?: continue
            val customRels = override.customRelations.map { it.toRelationInfo() }
            if (customRels.isNotEmpty()) {
                customRelationsMap.getOrPut(table.name.lowercase()) { mutableListOf() }.addAll(customRels)
                for (rel in customRels) {
                    if (rel.relationType == RelationType.BELONGS_TO && rel.targetTable.lowercase() in selectedTableNames) {
                        customRelationsMap.getOrPut(rel.targetTable.lowercase()) { mutableListOf() }
                            .add(RelationInfo(RelationType.HAS_MANY, table.name, rel.toColumn, rel.fromColumn))
                    }
                }
            }
        }

        // Build per-table derives overrides map
        val entityDerivesOverrides = tableConfigs.mapValues { (_, c) -> c.entityExtraDerives.toSet() }
        val dtoDerivesOverrides = tableConfigs.mapValues { (_, c) -> c.dtoExtraDerives.toSet() }
        val voDerivesOverrides = tableConfigs.mapValues { (_, c) -> c.voExtraDerives.toSet() }

        return listOf(
            LayerConfig(
                EntityLayer(entityExtraDerives, customRelationsMap, entityDerivesOverrides), isEntityEnabled, entityBaseDir,
                isTableEnabled = { tableConfigs[it]?.generateEntity ?: true },
                outputDirForTable = { tableConfigs[it]?.entityOutputDir ?: entityBaseDir }
            ),
            LayerConfig(
                DtoLayer(dtoExtraDerives, dtoDerivesOverrides), isDtoEnabled, dtoBaseDir,
                isTableEnabled = { tableConfigs[it]?.generateDto ?: true },
                outputDirForTable = { tableConfigs[it]?.dtoOutputDir ?: dtoBaseDir }
            ),
            LayerConfig(
                VoLayer(voExtraDerives, voDerivesOverrides), isVoEnabled, voBaseDir,
                isTableEnabled = { tableConfigs[it]?.generateVo ?: true },
                outputDirForTable = { tableConfigs[it]?.voOutputDir ?: voBaseDir }
            ),
            LayerConfig(
                ServiceLayer(), isServiceEnabled, serviceBaseDir,
                isTableEnabled = { tableConfigs[it]?.generateService ?: true },
                outputDirForTable = { tableConfigs[it]?.serviceOutputDir ?: serviceBaseDir }
            ),
            LayerConfig(
                RouteLayer(), isRouteEnabled, routeBaseDir,
                isTableEnabled = { tableConfigs[it]?.generateRoute ?: true },
                outputDirForTable = { tableConfigs[it]?.routeOutputDir ?: routeBaseDir }
            )
        )
    }

    // ══════════════════════════════════════════════════════════════
    // ── Persistence
    // ══════════════════════════════════════════════════════════════

    private val entityDeriveMappings get() = arrayOf(
        entitySerdeCheckBox to listOf(DERIVE_SERIALIZE, DERIVE_DESERIALIZE),
        entityBuilderCheckBox to listOf(DERIVE_BUILDER),
        entityDefaultCheckBox to listOf(DERIVE_DEFAULT),
        entityHashCheckBox to listOf(DERIVE_HASH))

    private val dtoDeriveMappings get() = arrayOf(
        dtoBuilderCheckBox to listOf(DERIVE_BUILDER),
        dtoValidateCheckBox to listOf(DERIVE_VALIDATE))

    private val voDeriveMappings get() = arrayOf(
        voBuilderCheckBox to listOf(DERIVE_BUILDER),
        voJsonSchemaCheckBox to listOf(DERIVE_JSON_SCHEMA))

    fun saveSettings() {
        if (isMultiTable) {
            saveCurrentTableConfig()
        }
        // Always extract base dirs (strip schema suffix) before persisting
        saveDirBasesFromFields()

        settings.generateEntity = if (isMultiTable) tableConfigs.values.any { it.generateEntity } else entityCheckBox.isSelected
        settings.entityOutputDir = entityBaseDir
        settings.entityExtraDerives = collectDerives(*entityDeriveMappings)
        settings.generateDto = if (isMultiTable) tableConfigs.values.any { it.generateDto } else dtoCheckBox.isSelected
        settings.dtoOutputDir = dtoBaseDir
        settings.dtoExtraDerives = collectDerives(*dtoDeriveMappings)
        settings.generateVo = if (isMultiTable) tableConfigs.values.any { it.generateVo } else voCheckBox.isSelected
        settings.voOutputDir = voBaseDir
        settings.voExtraDerives = collectDerives(*voDeriveMappings)
        settings.generateService = if (isMultiTable) tableConfigs.values.any { it.generateService } else serviceCheckBox.isSelected
        settings.serviceOutputDir = serviceBaseDir
        settings.generateRoute = if (isMultiTable) tableConfigs.values.any { it.generateRoute } else routeCheckBox.isSelected
        settings.routeOutputDir = routeBaseDir
        settings.routePrefix = routePrefixField.text
        settings.tableNamePrefix = tablePrefixField.text
        settings.columnNamePrefix = columnPrefixField.text

        if (isMultiTable) {
            for ((tableName, config) in tableConfigs) {
                val override = settings.tableOverrides.getOrPut(tableName) { TableOverrideConfig() }
                override.generateEntity = config.generateEntity
                override.generateDto = config.generateDto
                override.generateVo = config.generateVo
                override.generateService = config.generateService
                override.generateRoute = config.generateRoute
                override.customEntityName = config.entityName
                // Persist per-table output directories (null if same as global default)
                override.entityOutputDir = config.entityOutputDir.takeIf { it != entityBaseDir }
                override.dtoOutputDir = config.dtoOutputDir.takeIf { it != dtoBaseDir }
                override.voOutputDir = config.voOutputDir.takeIf { it != voBaseDir }
                override.serviceOutputDir = config.serviceOutputDir.takeIf { it != serviceBaseDir }
                override.routeOutputDir = config.routeOutputDir.takeIf { it != routeBaseDir }
                // Persist per-table derives (null if same as global default)
                val globalEntityDerives = collectDerives(*entityDeriveMappings)
                val globalDtoDerives = collectDerives(*dtoDeriveMappings)
                val globalVoDerives = collectDerives(*voDeriveMappings)
                override.entityExtraDerives = if (config.entityExtraDerives != globalEntityDerives) config.entityExtraDerives.toMutableSet() else null
                override.dtoExtraDerives = if (config.dtoExtraDerives != globalDtoDerives) config.dtoExtraDerives.toMutableSet() else null
                override.voExtraDerives = if (config.voExtraDerives != globalVoDerives) config.voExtraDerives.toMutableSet() else null
            }
        }
    }

    private fun collectDerives(vararg mappings: Pair<JBCheckBox, List<String>>): MutableSet<String> =
        mutableSetOf<String>().apply { for ((cb, names) in mappings) if (cb.isSelected) addAll(names) }

    // ── Public accessors ──

    val isEntityEnabled get() = if (isMultiTable) tableConfigs.values.any { it.generateEntity } else entityCheckBox.isSelected
    val isDtoEnabled get() = if (isMultiTable) tableConfigs.values.any { it.generateDto } else dtoCheckBox.isSelected
    val isVoEnabled get() = if (isMultiTable) tableConfigs.values.any { it.generateVo } else voCheckBox.isSelected
    val isServiceEnabled get() = if (isMultiTable) tableConfigs.values.any { it.generateService } else serviceCheckBox.isSelected
    val isRouteEnabled get() = if (isMultiTable) tableConfigs.values.any { it.generateRoute } else routeCheckBox.isSelected

    val entityOutputDir get() = entityBaseDir
    val dtoOutputDir get() = dtoBaseDir
    val voOutputDir get() = voBaseDir
    val serviceOutputDir get() = serviceBaseDir
    val routeOutputDir get() = routeBaseDir

    val entityExtraDerives: Set<String> get() = collectDerives(*entityDeriveMappings)
    val dtoExtraDerives: Set<String> get() = collectDerives(*dtoDeriveMappings)
    val voExtraDerives: Set<String> get() = collectDerives(*voDeriveMappings)

    // ── Per-table accessors (for Action to build layer configs) ──

    val entityOutputDirForTable: (String) -> String get() = { tableConfigs[it]?.entityOutputDir ?: entityBaseDir }
    val dtoOutputDirForTable: (String) -> String get() = { tableConfigs[it]?.dtoOutputDir ?: dtoBaseDir }
    val voOutputDirForTable: (String) -> String get() = { tableConfigs[it]?.voOutputDir ?: voBaseDir }
    val serviceOutputDirForTable: (String) -> String get() = { tableConfigs[it]?.serviceOutputDir ?: serviceBaseDir }
    val routeOutputDirForTable: (String) -> String get() = { tableConfigs[it]?.routeOutputDir ?: routeBaseDir }

    val entityDerivesOverrides: Map<String, Set<String>> get() = tableConfigs.mapValues { (_, c) -> c.entityExtraDerives.toSet() }
    val dtoDerivesOverrides: Map<String, Set<String>> get() = tableConfigs.mapValues { (_, c) -> c.dtoExtraDerives.toSet() }
    val voDerivesOverrides: Map<String, Set<String>> get() = tableConfigs.mapValues { (_, c) -> c.voExtraDerives.toSet() }

    companion object {
        private val LOG = logger<GenerateSeaOrmDialog>()

        /** Compute entity name from table name with prefix stripping. */
        private fun computeEntityName(tableName: String?, prefix: String): String {
            if (tableName == null) return ""
            val trimmed = prefix.trim()
            val stripped = if (trimmed.isNotEmpty() && tableName.startsWith(trimmed, ignoreCase = true))
                tableName.substring(trimmed.length) else tableName
            return stripped.trim().toPascalCase()
        }
    }
}
