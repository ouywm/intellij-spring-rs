package com.springrs.plugin.codegen

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_BUILDER
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DEFAULT
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_DESERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_HASH
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_JSON_SCHEMA
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_SERIALIZE
import com.springrs.plugin.utils.SpringRsConstants.DERIVE_VALIDATE
import java.awt.*
import javax.swing.*

/**
 * Configuration dialog for Sea-ORM code generation.
 *
 * UI layout inspired by MyBatisCodeHelper-Pro:
 * - Top: entity name / primary key / table info (form layout)
 * - Output: directory fields with browse buttons (form layout)
 * - Config: derive macro checkboxes (3-column grid)
 * - Bottom tabs: per-layer toggles + layer-specific options
 */
class GenerateSeaOrmDialog(
    private val project: Project,
    private val tableNames: List<String>,
    private val tableInfos: List<TableInfo> = emptyList()
) : DialogWrapper(project) {

    private val settings = CodeGenSettingsState.getInstance(project)

    // ── Top: table info ──
    private val detectedPrefix = detectTableNamePrefix(tableNames)
    private val effectivePrefix = settings.tableNamePrefix.ifEmpty { detectedPrefix }
    private val entityNameField = JBTextField(computeEntityName(tableInfos.firstOrNull()?.name, effectivePrefix))
    private val tableNameField = JBTextField(tableNames.joinToString(", ")).apply { isEditable = false }
    private val tablePrefixField = JBTextField(effectivePrefix)
    private val columnPrefixField = JBTextField(settings.columnNamePrefix)

    // ── Output directories ──
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
    private val entityJsonSchemaCheckBox = JBCheckBox("$DERIVE_JSON_SCHEMA (schemars)", DERIVE_JSON_SCHEMA in settings.entityExtraDerives)

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

    // ── Tab: Template ──
    private val builtinRadio = JRadioButton(SpringRsBundle.message("codegen.template.builtin"), !settings.useCustomTemplate)
    private val customRadio = JRadioButton(SpringRsBundle.message("codegen.template.custom"), settings.useCustomTemplate)
    private val customPathField = createDirField(settings.customTemplatePath)

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

        ButtonGroup().apply { add(builtinRadio); add(customRadio) }
        customPathField.isEnabled = customRadio.isSelected
        customRadio.addActionListener { customPathField.isEnabled = customRadio.isSelected }
        builtinRadio.addActionListener { customPathField.isEnabled = customRadio.isSelected }

        // Live-update entity name when prefix changes
        tablePrefixField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateEntityName()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateEntityName()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateEntityName()
        })
    }

    private fun updateEntityName() {
        val name = tableInfos.firstOrNull()?.name ?: return
        entityNameField.text = computeEntityName(name, tablePrefixField.text)
    }

    // ══════════════════════════════════════════════════════════════
    // ── Layout (MyBatis-style)
    // ══════════════════════════════════════════════════════════════

    override fun createCenterPanel(): JComponent {
        val root = JPanel(GridBagLayout())
        root.preferredSize = Dimension(660, 560)
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

        // Entity name + "定制列" button
        val entityNameRow = JPanel(BorderLayout(8, 0)).apply {
            add(entityNameField, BorderLayout.CENTER)
            add(JButton(SpringRsBundle.message("codegen.customize.button")).apply {
                addActionListener { openColumnCustomizeDialog() }
            }, BorderLayout.EAST)
        }
        addFormRow(panel, row++, SpringRsBundle.message("codegen.field.entity.name"), entityNameRow)

        if (tableNames.size > 1) {
            addFormRow(panel, row++, SpringRsBundle.message("codegen.field.tables"), tableNameField)
        }

        // Prefix stripping — only show if auto-detected or previously configured
        val showPrefix = detectedPrefix.isNotEmpty() || settings.tableNamePrefix.isNotEmpty() || settings.columnNamePrefix.isNotEmpty()
        if (showPrefix) {
            val prefixPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(JBLabel(SpringRsBundle.message("codegen.field.table.prefix")))
                add(tablePrefixField.apply { columns = 8 })
                add(Box.createHorizontalStrut(12))
                add(JBLabel(SpringRsBundle.message("codegen.field.column.prefix")))
                add(columnPrefixField.apply { columns = 8 })
            }
            val hintText = if (detectedPrefix.isNotEmpty())
                "${SpringRsBundle.message("codegen.field.prefix.strip")} (${SpringRsBundle.message("codegen.prefix.detected", detectedPrefix)})"
            else SpringRsBundle.message("codegen.field.prefix.strip")
            addFormRow(panel, row, hintText, prefixPanel)
        }
        return panel
    }

    private fun openColumnCustomizeDialog() {
        val table = tableInfos.firstOrNull() ?: return
        val override = settings.tableOverrides.getOrPut(table.name) { TableOverrideConfig() }
        val dialog = ColumnCustomizeDialog(project, table.name, table.columns, override)
        if (dialog.showAndGet()) {
            // Update entity name field if custom name was set
            val customName = override.customEntityName
            if (customName != null) {
                entityNameField.text = customName
            }
        }
    }

    /**
     * Output section: directory fields with browse buttons.
     * "baseFiles" equivalent in MyBatis.
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
     * Config section: entity derive macros in a 3-column checkbox grid.
     * "config" equivalent in MyBatis.
     */
    private fun createConfigSection(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(SpringRsBundle.message("codegen.config.title"))
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 8)
        }

        // Row 0
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(entitySerdeCheckBox, gbc)
        gbc.gridx = 1; panel.add(entityBuilderCheckBox, gbc)
        gbc.gridx = 2; panel.add(entityDefaultCheckBox, gbc)

        // Row 1
        gbc.gridy = 1
        gbc.gridx = 0; panel.add(entityHashCheckBox, gbc)
        gbc.gridx = 1; panel.add(entityJsonSchemaCheckBox, gbc)

        return panel
    }

    /**
     * Bottom tabs: per-layer generate toggle + layer-specific options.
     * Tab equivalent in MyBatis.
     */
    private fun createLayerTabs(): JBTabbedPane {
        val tabs = JBTabbedPane()

        // ── Entity tab ──
        tabs.addTab("Entity", createTabPanel(entityCheckBox))

        // ── DTO tab ──
        tabs.addTab("DTO", createTabPanel(dtoCheckBox,
            checkboxRow(dtoBuilderCheckBox, dtoValidateCheckBox)))

        // ── VO tab ──
        tabs.addTab("VO", createTabPanel(voCheckBox,
            checkboxRow(voBuilderCheckBox, voJsonSchemaCheckBox)))

        // ── Service tab ──
        tabs.addTab("Service", createTabPanel(serviceCheckBox))

        // ── Route tab ──
        tabs.addTab("Route", createTabPanel(routeCheckBox, JPanel(BorderLayout(8, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(SpringRsBundle.message("codegen.route.prefix")), BorderLayout.WEST)
            add(routePrefixField, BorderLayout.CENTER)
            maximumSize = Dimension(400, preferredSize.height)
        }))

        // ── Template tab ──
        tabs.addTab(SpringRsBundle.message("codegen.template.title"), JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            add(wrapLeft(builtinRadio))
            add(wrapLeft(customRadio))
            add(Box.createVerticalStrut(4))
            add(JPanel(BorderLayout(8, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel(SpringRsBundle.message("codegen.template.path")), BorderLayout.WEST)
                add(customPathField, BorderLayout.CENTER)
                maximumSize = Dimension(500, preferredSize.height)
            })
        })

        return tabs
    }

    // ══════════════════════════════════════════════════════════════
    // ── Form helpers (MyBatis-style aligned rows)
    // ══════════════════════════════════════════════════════════════

    /**
     * Add a form row: `label:  [field]` with aligned label column.
     */
    private fun addFormRow(panel: JPanel, row: Int, label: String, field: JComponent) {
        val gbc = GridBagConstraints()
        gbc.gridy = row; gbc.insets = JBUI.insets(3, 6)

        // Label (right-aligned, fixed width)
        gbc.gridx = 0; gbc.anchor = GridBagConstraints.EAST
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel(label), gbc)

        // Field (fill horizontal)
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

    private fun buildPreviewFiles(): List<GeneratedFile> {
        data class Spec(val enabled: Boolean, val dir: String,
                        val gen: (TableInfo) -> String, val fn: (TableInfo) -> String)
        val specs = listOf(
            Spec(isEntityEnabled, entityDirField.text,
                { SeaOrmEntityGenerator.generate(it, entityExtraDerives, project) }, SeaOrmEntityGenerator::fileName),
            Spec(isDtoEnabled, dtoDirField.text,
                { DtoGenerator.generate(it, dtoExtraDerives, project) }, DtoGenerator::fileName),
            Spec(isVoEnabled, voDirField.text,
                { VoGenerator.generate(it, voExtraDerives, project) }, VoGenerator::fileName),
            Spec(isServiceEnabled, serviceDirField.text,
                { ServiceGenerator.generate(it, project) }, ServiceGenerator::fileName),
            Spec(isRouteEnabled, routeDirField.text,
                { RouteGenerator.generate(it, project) }, RouteGenerator::fileName)
        )
        return buildList {
            for (s in specs) {
                if (!s.enabled) continue
                for (t in tableInfos) {
                    try { add(GeneratedFile("${s.dir}/${s.fn(t)}", s.gen(t))) }
                    catch (ex: Exception) { LOG.warn("Preview failed for ${t.name}", ex) }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Persistence
    // ══════════════════════════════════════════════════════════════

    fun saveSettings() {
        settings.generateEntity = entityCheckBox.isSelected
        settings.entityOutputDir = entityDirField.text
        settings.entityExtraDerives = collectDerives(
            entitySerdeCheckBox to listOf(DERIVE_SERIALIZE, DERIVE_DESERIALIZE),
            entityBuilderCheckBox to listOf(DERIVE_BUILDER),
            entityDefaultCheckBox to listOf(DERIVE_DEFAULT),
            entityHashCheckBox to listOf(DERIVE_HASH),
            entityJsonSchemaCheckBox to listOf(DERIVE_JSON_SCHEMA))
        settings.generateDto = dtoCheckBox.isSelected
        settings.dtoOutputDir = dtoDirField.text
        settings.dtoExtraDerives = collectDerives(
            dtoBuilderCheckBox to listOf(DERIVE_BUILDER),
            dtoValidateCheckBox to listOf(DERIVE_VALIDATE))
        settings.generateVo = voCheckBox.isSelected
        settings.voOutputDir = voDirField.text
        settings.voExtraDerives = collectDerives(
            voBuilderCheckBox to listOf(DERIVE_BUILDER),
            voJsonSchemaCheckBox to listOf(DERIVE_JSON_SCHEMA))
        settings.generateService = serviceCheckBox.isSelected
        settings.serviceOutputDir = serviceDirField.text
        settings.generateRoute = routeCheckBox.isSelected
        settings.routeOutputDir = routeDirField.text
        settings.routePrefix = routePrefixField.text
        settings.tableNamePrefix = tablePrefixField.text
        settings.columnNamePrefix = columnPrefixField.text
        settings.useCustomTemplate = customRadio.isSelected
        settings.customTemplatePath = customPathField.text
    }

    private fun collectDerives(vararg mappings: Pair<JBCheckBox, List<String>>): MutableSet<String> {
        val derives = mutableSetOf<String>()
        for ((cb, names) in mappings) { if (cb.isSelected) derives.addAll(names) }
        return derives
    }

    // ── Public accessors ──

    val isEntityEnabled get() = entityCheckBox.isSelected
    val isDtoEnabled get() = dtoCheckBox.isSelected
    val isVoEnabled get() = voCheckBox.isSelected
    val isServiceEnabled get() = serviceCheckBox.isSelected
    val isRouteEnabled get() = routeCheckBox.isSelected

    val entityOutputDir get() = entityDirField.text
    val dtoOutputDir get() = dtoDirField.text
    val voOutputDir get() = voDirField.text
    val serviceOutputDir get() = serviceDirField.text
    val routeOutputDir get() = routeDirField.text

    val entityExtraDerives: Set<String> get() = collectDerives(
        entitySerdeCheckBox to listOf(DERIVE_SERIALIZE, DERIVE_DESERIALIZE),
        entityBuilderCheckBox to listOf(DERIVE_BUILDER),
        entityDefaultCheckBox to listOf(DERIVE_DEFAULT),
        entityHashCheckBox to listOf(DERIVE_HASH),
        entityJsonSchemaCheckBox to listOf(DERIVE_JSON_SCHEMA))
    val dtoExtraDerives: Set<String> get() = collectDerives(
        dtoBuilderCheckBox to listOf(DERIVE_BUILDER),
        dtoValidateCheckBox to listOf(DERIVE_VALIDATE))
    val voExtraDerives: Set<String> get() = collectDerives(
        voBuilderCheckBox to listOf(DERIVE_BUILDER),
        voJsonSchemaCheckBox to listOf(DERIVE_JSON_SCHEMA))

    companion object {
        private val LOG = logger<GenerateSeaOrmDialog>()

        /**
         * Auto-detect common table name prefix — pure algorithm, no hardcoded list.
         *
         * Strategy:
         * 1. Single table: find prefix up to first `_` (e.g., `t_user` → `t_`)
         *    Only if the remaining part still contains `_` (avoid stripping `user_accounts` → `user_`)
         * 2. Multiple tables: find the longest common prefix up to a `_` boundary
         *    that ALL tables share (e.g., `sys_user`, `sys_role` → `sys_`)
         *
         * Returns empty string if no prefix detected.
         */
        fun detectTableNamePrefix(tableNames: List<String>): String {
            if (tableNames.isEmpty()) return ""

            // Find longest common prefix across all table names
            val commonPrefix = tableNames.reduce { acc, name ->
                acc.commonPrefixWith(name, ignoreCase = true)
            }

            // Snap to the last `_` boundary within the common prefix
            // e.g., common = "sys_us" → snap to "sys_"
            val lastUnderscore = commonPrefix.lastIndexOf('_')
            if (lastUnderscore <= 0) return "" // No `_` or starts with `_`

            val candidate = commonPrefix.substring(0, lastUnderscore + 1)

            // Sanity check: the prefix shouldn't consume the entire shortest table name
            // (i.e., after stripping, every table should still have a meaningful name)
            val allValid = tableNames.all { name ->
                val remaining = name.removePrefix(candidate)
                remaining.isNotEmpty() && remaining != name // prefix actually matched
            }

            return if (allValid) candidate else ""
        }

        /**
         * Compute entity name from table name with prefix stripping.
         */
        private fun computeEntityName(tableName: String?, prefix: String): String {
            if (tableName == null) return ""
            val stripped = if (prefix.isNotEmpty() && tableName.startsWith(prefix, ignoreCase = true))
                tableName.removePrefix(prefix) else tableName
            return stripped.toPascalCase()
        }
    }
}
