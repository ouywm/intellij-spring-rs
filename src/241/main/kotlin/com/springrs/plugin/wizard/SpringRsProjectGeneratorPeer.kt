package com.springrs.plugin.wizard

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.platform.GeneratorPeerImpl
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * spring-rs Project Generator Peer — stub for platform 241.
 *
 * The real implementation lives in the 251 source set.
 * This stub compiles but is never used at runtime because the wizard
 * extension point is disabled in plugin.xml.
 */
class SpringRsProjectGeneratorPeer(
    cargoProjectDir: Path = Paths.get(".")
) : GeneratorPeerImpl<SpringRsConfigurationData>(), Disposable {

    var checkValid: Runnable? = null

    override fun getSettings(): SpringRsConfigurationData =
        SpringRsConfigurationData(rustSettings = null, selectedPlugins = emptyList(), generateExample = false)

    override fun getComponent(myLocationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent {
        this.checkValid = checkValid
        return getComponent()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getComponent(): JComponent = JPanel()

    override fun validate(): ValidationInfo? = null

    override fun dispose() {}
}