package com.springrs.plugin.codegen

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.springrs.plugin.SpringRsBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

/**
 * How to handle an existing file conflict.
 */
enum class ConflictResolution {
    /** Keep existing file, skip generation. */
    SKIP,
    /** Replace existing file with generated code. */
    OVERWRITE,
    /** Rename existing file to `.bak`, write generated code. */
    BACKUP
}

/**
 * Dialog shown when a generated file already exists.
 *
 * Offers three options: Skip / Overwrite / Backup & Overwrite.
 * Has "Apply to all" checkbox to avoid repeated prompts.
 */
class FileConflictDialog(
    project: Project,
    private val filePath: String
) : DialogWrapper(project) {

    var resolution: ConflictResolution = ConflictResolution.SKIP
        private set

    val applyToAllCheckBox = JBCheckBox(SpringRsBundle.message("codegen.conflict.apply.all"), false)

    init {
        title = SpringRsBundle.message("codegen.conflict.title")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10)
        preferredSize = Dimension(450, 150)

        add(JBLabel(SpringRsBundle.message("codegen.conflict.message", filePath)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(12)
        })
        add(applyToAllCheckBox.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction(SpringRsBundle.message("codegen.conflict.skip")) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                resolution = ConflictResolution.SKIP; close(OK_EXIT_CODE)
            }
        },
        object : DialogWrapperAction(SpringRsBundle.message("codegen.conflict.overwrite")) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                resolution = ConflictResolution.OVERWRITE; close(OK_EXIT_CODE)
            }
        },
        object : DialogWrapperAction(SpringRsBundle.message("codegen.conflict.backup")) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                resolution = ConflictResolution.BACKUP; close(OK_EXIT_CODE)
            }
        },
        cancelAction
    )
}
