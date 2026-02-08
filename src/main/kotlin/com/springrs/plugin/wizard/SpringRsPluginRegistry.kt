package com.springrs.plugin.wizard

import com.springrs.plugin.SpringRsBundle

/**
 * Plugin categories for grouping in the wizard UI.
 */
enum class PluginCategory(val messageKey: String) {
    WEB("wizard.category.web"),
    DATABASE("wizard.category.database"),
    MESSAGING("wizard.category.messaging"),
    STORAGE("wizard.category.storage"),
    JOBS("wizard.category.jobs"),
    SECURITY("wizard.category.security"),
    OBSERVABILITY("wizard.category.observability");

    val displayName: String get() = SpringRsBundle.message(messageKey)
}

/**
 * Contract for a spring-rs plugin definition.
 *
 * Each plugin encapsulates its identity, dependency info, configuration,
 * and project structure requirements. Implement this interface (or extend
 * [AbstractSpringRsPlugin]) to define a new spring-rs plugin.
 *
 * ## Adding a new plugin
 *
 * 1. Create an `object` extending [AbstractSpringRsPlugin]
 * 2. Override required properties: [id], [description], [category], [pluginClassName], [dependency]
 * 3. Override optional properties as needed (e.g., [configSection], [extraDeps])
 * 4. Add the object to [SpringRsPluginRegistry.plugins]
 * 5. Add a corresponding constant in [SpringRsPluginRegistry]
 */
interface SpringRsPlugin {

    // ── Identity ──
    val id: String
    val displayName: String
    val description: String
    val category: PluginCategory

    // ── Code generation ──
    val dirName: String
    val moduleName: String
    val templatePath: String
    val pluginClassName: String
    val crateName: String

    // ── Cargo.toml dependencies ──
    val dependency: String
    val extraDeps: String get() = ""
    /** Build dependencies for Cargo.toml (e.g., tonic-build for gRPC). */
    val buildDeps: String get() = ""

    /**
     * Resolve the dependency line based on all selected plugins.
     * Override this to conditionally add features when certain plugins are co-selected.
     * Default implementation returns [dependency] unchanged.
     */
    fun resolveDependency(selectedPluginIds: Set<String>): String = dependency

    // ── TOML configuration ──
    val configSection: String get() = ""
    /** Configurator name for `#[auto_config]`, empty if not supported. */
    val configuratorName: String get() = ""

    // ── Project structure ──
    /** Whether this plugin requires special project structure (e.g., gRPC with proto/). */
    val specialStructure: Boolean get() = false
}

/**
 * Convenience base class with smart defaults derived from [id].
 *
 * Auto-derived properties (override any to customize):
 * - [displayName] = [id]
 * - [dirName] = id with "spring-" prefix removed, dashes → underscores
 * - [moduleName] = [dirName]
 * - [templatePath] = "example-{dirName}.rs"
 * - [crateName] = id with dashes → underscores
 */
abstract class AbstractSpringRsPlugin : SpringRsPlugin {
    override val displayName: String get() = id
    override val dirName: String get() = id.removePrefix("spring-").replace("-", "_")
    override val moduleName: String get() = dirName
    override val templatePath: String get() = "example-${id.removePrefix("spring-").replace("-", "_")}.rs"
    override val crateName: String get() = id.replace("-", "_")
}

// ══════════════════════════════════════════════════════════════
// ── Concrete Plugin Definitions
// ══════════════════════════════════════════════════════════════

// ── Web ──

object SpringWebPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-web"
    override val description = "Web framework based on Axum"
    override val category = PluginCategory.WEB
    override val pluginClassName = "WebPlugin"
    override val dependency = """spring-web = "0.4""""
    override val configuratorName = "WebConfigurator"
    override val configSection = """
        |[web]
        |port = 8080
        |graceful = true
    """.trimMargin()
}

object SpringGrpcPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-grpc"
    override val description = "gRPC server/client via Tonic"
    override val category = PluginCategory.WEB
    override val pluginClassName = "GrpcPlugin"
    override val dependency = """spring-grpc = "0.4""""
    override val extraDeps = "tonic = \"0.13\"\nprost = \"0.13\""
    override val buildDeps = """tonic-build = "0.13""""
    override val specialStructure = true
}

// ── Database ──

object SpringPostgresPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-postgres"
    override val description = "PostgreSQL using tokio-postgres"
    override val category = PluginCategory.DATABASE
    override val pluginClassName = "PgPlugin"
    override val dependency = """spring-postgres = "0.4""""
    override val configSection = """
        |[postgres]
        |connect = "postgres://user:password@localhost/dbname"
    """.trimMargin()
}

object SpringSqlxPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-sqlx"
    override val description = "SQL database using SQLx"
    override val category = PluginCategory.DATABASE
    override val pluginClassName = "SqlxPlugin"
    override val dependency = """spring-sqlx = { version = "0.4", features = ["postgres"] }"""
    override val configSection = """
        |[sqlx]
        |uri = "postgres://user:password@localhost:5432/dbname"
    """.trimMargin()
}

object SpringSeaOrmPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-sea-orm"
    override val description = "SQL database using SeaORM"
    override val category = PluginCategory.DATABASE
    override val pluginClassName = "SeaOrmPlugin"
    override val dependency = """spring-sea-orm = { version = "0.4", features = ["postgres"] }"""
    override val extraDeps = """sea-orm = "1""""
    override val configSection = """
        |[sea-orm]
        |uri = "postgres://user:password@localhost/dbname"
        |enable_logging = true
    """.trimMargin()

    override fun resolveDependency(selectedPluginIds: Set<String>): String {
        return if (SpringRsPluginRegistry.WEB in selectedPluginIds) {
            """spring-sea-orm = { version = "0.4", features = ["postgres", "with-web"] }"""
        } else {
            dependency
        }
    }
}

// ── Messaging ──

object SpringStreamPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-stream"
    override val description = "Message streaming (Kafka, Redis Stream)"
    override val category = PluginCategory.MESSAGING
    override val pluginClassName = "StreamPlugin"
    override val dependency = """spring-stream = { version = "0.4", features = ["file", "json"] }"""
    override val extraDeps = """futures-util = "0.3""""
    override val configSection = """
        |[stream]
        |uri = "file://./stream"
        |
        |[stream.file]
        |connect = { create_file = "CreateIfNotExists" }
    """.trimMargin()
}

object SpringMailPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-mail"
    override val description = "Email sending via SMTP"
    override val category = PluginCategory.MESSAGING
    override val pluginClassName = "MailPlugin"
    override val dependency = """spring-mail = "0.4""""
    override val configSection = """
        |[mail]
        |host = "smtp.gmail.com"
        |port = 465
        |secure = true
        |auth = { user = "your-email@gmail.com", password = "your-password" }
    """.trimMargin()
}

// ── Storage ──

object SpringRedisPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-redis"
    override val description = "Redis client integration"
    override val category = PluginCategory.STORAGE
    override val pluginClassName = "RedisPlugin"
    override val dependency = """spring-redis = "0.4""""
    override val configSection = """
        |[redis]
        |uri = "redis://127.0.0.1/"
    """.trimMargin()
}

object SpringOpenDalPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-opendal"
    override val description = "Storage abstraction via OpenDAL"
    override val category = PluginCategory.STORAGE
    override val dirName = "storage"
    override val pluginClassName = "OpenDALPlugin"
    override val dependency = """spring-opendal = { version = "0.4", features = ["services-fs"] }"""
    override val configSection = """
        |[opendal]
        |scheme = "fs"
        |options = { root = "/tmp" }
    """.trimMargin()
}

// ── Jobs ──

object SpringJobPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-job"
    override val description = "Scheduled jobs with cron"
    override val category = PluginCategory.JOBS
    override val pluginClassName = "JobPlugin"
    override val dependency = """spring-job = "0.4""""
    override val configuratorName = "JobConfigurator"
}

object SpringApalisPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-apalis"
    override val description = "Background job processing"
    override val category = PluginCategory.JOBS
    override val pluginClassName = "ApalisPlugin"
    override val dependency = """spring-apalis = { version = "0.4", features = ["redis"] }"""
    override val extraDeps = """serde = { version = "1", features = ["derive"] }"""
}

// ── Security ──

object SpringSaTokenPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-sa-token"
    override val description = "Authentication & authorization"
    override val category = PluginCategory.SECURITY
    override val dirName = "auth"
    override val pluginClassName = "SaTokenPlugin"
    override val dependency = """spring-sa-token = "0.4""""
    override val configSection = """
        |[sa-token]
        |token_name = "Authorization"
        |timeout = 86400
        |active_timeout = 3600
        |auto_renew = true
        |is_concurrent = true
        |is_share = true
        |token_style = "Uuid"
    """.trimMargin()
}

// ── Observability ──

object SpringOpenTelemetryPlugin : AbstractSpringRsPlugin() {
    override val id = "spring-opentelemetry"
    override val description = "Observability (tracing, metrics, logging)"
    override val category = PluginCategory.OBSERVABILITY
    override val dirName = "telemetry"
    override val pluginClassName = "OpenTelemetryPlugin"
    override val dependency = """spring-opentelemetry = { version = "0.4", features = ["jaeger"] }"""
    override val configSection = """
        |[logger]
        |pretty_backtrace = true
    """.trimMargin()
}

// ══════════════════════════════════════════════════════════════
// ── Registry
// ══════════════════════════════════════════════════════════════

/**
 * spring-rs Plugin Registry.
 *
 * Central collection point for all [SpringRsPlugin] definitions.
 * New plugins are registered by adding them to [plugins].
 */
object SpringRsPluginRegistry {

    // Plugin ID constants (used in SpringRsConfigurationData, SpringRsProjectGenerator, etc.)
    const val WEB = "spring-web"
    const val JOB = "spring-job"
    const val STREAM = "spring-stream"
    const val MAIL = "spring-mail"
    const val REDIS = "spring-redis"
    const val OPENDAL = "spring-opendal"
    const val POSTGRES = "spring-postgres"
    const val SQLX = "spring-sqlx"
    const val SEA_ORM = "spring-sea-orm"
    const val APALIS = "spring-apalis"
    const val OPENTELEMETRY = "spring-opentelemetry"
    const val SA_TOKEN = "spring-sa-token"
    const val GRPC = "spring-grpc"

    /**
     * All registered plugins, ordered by category then by display name.
     * Add new plugins here.
     */
    private val plugins: List<SpringRsPlugin> = listOf(
        // Web
        SpringWebPlugin,
        SpringGrpcPlugin,
        // Database
        SpringPostgresPlugin,
        SpringSqlxPlugin,
        SpringSeaOrmPlugin,
        // Messaging
        SpringStreamPlugin,
        SpringMailPlugin,
        // Storage
        SpringRedisPlugin,
        SpringOpenDalPlugin,
        // Jobs
        SpringJobPlugin,
        SpringApalisPlugin,
        // Security
        SpringSaTokenPlugin,
        // Observability
        SpringOpenTelemetryPlugin
    )

    private val pluginMap = plugins.associateBy { it.id }

    fun get(id: String): SpringRsPlugin? = pluginMap[id]

    fun all(): List<SpringRsPlugin> = plugins

    fun ids(): Set<String> = pluginMap.keys

    /**
     * Convert to selectable items for UI (includes category).
     */
    fun toSelectableItems(): List<SpringRsSelectableItem> =
        plugins.map { SpringRsSelectableItem(it.id, it.displayName, it.description, it.category.displayName) }

    /**
     * Get plugins grouped by category, preserving enum order.
     */
    fun byCategory(): Map<PluginCategory, List<SpringRsPlugin>> =
        PluginCategory.entries.associateWith { cat -> plugins.filter { it.category == cat } }
            .filterValues { it.isNotEmpty() }
}
