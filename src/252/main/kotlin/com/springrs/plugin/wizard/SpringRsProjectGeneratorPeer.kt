package com.springrs.plugin.wizard

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.platform.GeneratorPeerImpl
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent

/**
 * spring-rs Project Generator Peer.
 *
 * Integrates RustProjectSettingsPanel (toolchain, stdlib, envs) from the Rust plugin
 * with spring-rs specific configuration (plugin selection).
 *
 * Follows the same pattern as org.rust.ide.newProject.RsProjectGeneratorPeer.
 */
class SpringRsProjectGeneratorPeer(
    cargoProjectDir: Path = Paths.get("."),
    private val cs: CoroutineScope
) : GeneratorPeerImpl<SpringRsConfigurationData>(), Disposable {

    private val newProjectPanel = SpringRsNewProjectPanel(
        cargoProjectDir = cargoProjectDir,
        cs = cs,
        updateListener = { checkValid?.run() }
    )

    init {
        Disposer.register(this, newProjectPanel)
    }

    var checkValid: Runnable? = null

    override fun getSettings(): SpringRsConfigurationData = newProjectPanel.data

    override fun getComponent(myLocationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent {
        this.checkValid = checkValid
        return getComponent()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getComponent(): JComponent {
        val dialogPanel: DialogPanel = panel {
            newProjectPanel.attachTo(this)
        }

        // Use addPropertyChangeListener like Rust plugin does
        // When "ancestor" property changes (panel becomes visible), start toolchain detection
        dialogPanel.addPropertyChangeListener { event ->
            if (event.propertyName == "ancestor") {
                val modalityState = ModalityState.stateForComponent(dialogPanel)
                newProjectPanel.start(modalityState)
            }
        }

        return dialogPanel
    }

    override fun validate(): ValidationInfo? {
        return try {
            newProjectPanel.validateSettings()
            null
        } catch (e: ConfigurationException) {
            @Suppress("DEPRECATION")
            ValidationInfo(e.message ?: "")
        }
    }

    override fun dispose() {
        // Children are automatically disposed via Disposer
    }
}
