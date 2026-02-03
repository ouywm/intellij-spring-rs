package com.springrs.plugin.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * spring-rs plugin icon definitions.
 */
object SpringRsIcons {

    // ==================== Main Icons ====================

    /** spring-rs main icon (16x16). Used for plugin branding. */
    @JvmField
    val SpringRs: Icon = IconLoader.getIcon("/icons/spring-rs.svg", SpringRsIcons::class.java)

    /** spring-rs logo icon (16x16). Used for tool windows. */
    @JvmField
    val SpringRsLogo: Icon = IconLoader.getIcon("/icons/spring-rust-logo.svg", SpringRsIcons::class.java)

    // ==================== Gutter Icons ====================

    /** Run icon used near `main` functions. */
    @JvmField
    val RunIcon: Icon = IconLoader.getIcon("/icons/springboot/gutter/springBoot.svg", SpringRsIcons::class.java)

    /** Spring leaf icon (14x14). Generic Spring indicator. */
    @JvmField
    val SpringLeaf: Icon = IconLoader.getIcon("/icons/expui/gutter/spring@14x14.svg", SpringRsIcons::class.java)

    /** Bean icon used to mark components. */
    @JvmField
    val SpringBean: Icon = IconLoader.getIcon("/icons/expui/gutter/springBean@14x14.svg", SpringRsIcons::class.java)

    /** spring-rs bean icon for `#[derive(Service)]` structs (14x14 gutter). */
    @JvmField
    val SpringRsBean: Icon = IconLoader.getIcon("/icons/expui/gutter/springBean@14x14.svg", SpringRsIcons::class.java)

    /** Config icon for config classes. */
    @JvmField
    val SpringConfig: Icon = IconLoader.getIcon("/icons/expui/gutter/springConfig@14x14.svg", SpringRsIcons::class.java)

    /** spring-rs TOML config icon for `#[config_prefix]` structs. */
    @JvmField
    val SpringRsTomlConfig: Icon = IconLoader.getIcon("/icons/expui/gutter/springRsTomlConfig@14x14.svg", SpringRsIcons::class.java)

    /** Request mapping icon for route handlers. */
    @JvmField
    val RequestMapping: Icon = IconLoader.getIcon("/icons/expui/gutter/requestMapping@14x14.svg", SpringRsIcons::class.java)

    /** Spring scan icon for `auto_config` attribute macro. */
    @JvmField
    val SpringScan: Icon = IconLoader.getIcon("/icons/expui/gutter/springScan@14x14.svg", SpringRsIcons::class.java)

    // ==================== DI Icons ====================

    /** Dependency injection icon for `#[inject(...)]` fields. */
    @JvmField
    val Inject: Icon = IconLoader.getIcon("/icons/expui/gutter/showAutowiredDependencies@14x14.svg", SpringRsIcons::class.java)
}