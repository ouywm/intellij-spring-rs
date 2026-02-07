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

    // ==================== Stream Listener Icons ====================

    /** Stream listener icon for `#[stream_listener]` functions (14x14 gutter).
     *  Uses listener icon (receiver), NOT publisher (sender). */
    @JvmField
    val StreamListener: Icon = IconLoader.getIcon("/icons/expui/gutter/listener@14x14.svg", SpringRsIcons::class.java)

    // ==================== Job Icons ====================

    /** Job/scheduled task icon for `#[cron]`, `#[fix_delay]`, `#[fix_rate]`, `#[one_shot]` functions (14x14 gutter).
     *  Uses springBeanMethod icon (method with clock-like appearance). */
    @JvmField
    val Job: Icon = IconLoader.getIcon("/icons/expui/gutter/springBeanMethod@14x14.svg", SpringRsIcons::class.java)

    // ==================== Plugin Icons ====================

    /** Plugin icon for `.add_plugin(...)` registrations. */
    @JvmField
    val Plugin: Icon = IconLoader.getIcon("/icons/expui/gutter/factoryMethodBean@14x14.svg", SpringRsIcons::class.java)

    // ==================== Cache Icons ====================

    /** Cache icon for `#[cache]` functions (14x14 gutter). */
    @JvmField
    val Cacheable: Icon = IconLoader.getIcon("/icons/expui/gutter/showCacheable@14x14.svg", SpringRsIcons::class.java)

    // ==================== Security Icons ====================

    /** Spring Security icon for sa-token macros (14x14 gutter). Spring leaf + lock. */
    @JvmField
    val SecurityRole: Icon = IconLoader.getIcon("/icons/expui/gutter/springSecurity@14x14.svg", SpringRsIcons::class.java)

    /** Auth ignored icon for `#[sa_ignore]`. Uses abstract bean (warning-like). */
    @JvmField
    val AuthIgnored: Icon = com.intellij.icons.AllIcons.General.Warning

    // ==================== Web / Socket.IO Icons ====================

    /** Spring Web icon for Socket.IO handlers. */
    @JvmField
    val SpringWeb: Icon = IconLoader.getIcon("/icons/expui/gutter/springJavaBean@14x14.svg", SpringRsIcons::class.java)

    // ==================== DI Icons ====================

    /** Dependency injection icon for `#[inject(...)]` fields. */
    @JvmField
    val Inject: Icon = IconLoader.getIcon("/icons/expui/gutter/showAutowiredDependencies@14x14.svg", SpringRsIcons::class.java)
}