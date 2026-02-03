package com.springrs.plugin.wizard

/**
 * spring-rs Plugin Registry.
 *
 * Single source of truth for all plugin metadata.
 * Each plugin is defined once with all its properties.
 */
object SpringRsPluginRegistry {

    // Plugin ID constants
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
     * Complete plugin definition - all metadata in one place.
     */
    data class PluginDefinition(
        val id: String,
        val displayName: String,
        val description: String,
        val dirName: String,
        val moduleName: String,
        val templatePath: String,
        val pluginClassName: String,
        val crateName: String,
        val dependency: String,
        val extraDeps: String = "",
        val configSection: String = "",
        /** Configurator name for #[auto_config], empty if not supported */
        val configuratorName: String = "",
        /** Build dependencies for Cargo.toml (e.g., tonic-build for grpc) */
        val buildDeps: String = "",
        /** Whether this plugin requires special project structure (e.g., grpc) */
        val specialStructure: Boolean = false
    )

    private val plugins = listOf(
        PluginDefinition(
            id = WEB,
            displayName = "spring-web",
            description = "Web framework based on Axum",
            dirName = "web",
            moduleName = "web",
            templatePath = "example-web.rs",
            pluginClassName = "WebPlugin",
            crateName = "spring_web",
            dependency = """spring-web = "0.4"""",
            configuratorName = "WebConfigurator",
            configSection = """
                |[web]
                |port = 8080
                |graceful = true
            """.trimMargin()
        ),
        PluginDefinition(
            id = JOB,
            displayName = "spring-job",
            description = "Scheduled jobs with cron",
            dirName = "job",
            moduleName = "job",
            templatePath = "example-job.rs",
            pluginClassName = "JobPlugin",
            crateName = "spring_job",
            dependency = """spring-job = "0.4"""",
            configuratorName = "JobConfigurator"
        ),
        PluginDefinition(
            id = STREAM,
            displayName = "spring-stream",
            description = "Message streaming (Kafka, Redis Stream)",
            dirName = "stream",
            moduleName = "stream",
            templatePath = "example-stream.rs",
            pluginClassName = "StreamPlugin",
            crateName = "spring_stream",
            dependency = """spring-stream = { version = "0.4", features = ["file"] }""",
            extraDeps = """futures-util = "0.3"""",
            configSection = """
                |[stream]
                |uri = "file://./stream"
                |
                |[stream.file]
                |connect = { create_file = "CreateIfNotExists" }
            """.trimMargin()
        ),
        PluginDefinition(
            id = MAIL,
            displayName = "spring-mail",
            description = "Email sending via SMTP",
            dirName = "mail",
            moduleName = "mail",
            templatePath = "example-mail.rs",
            pluginClassName = "MailPlugin",
            crateName = "spring_mail",
            dependency = """spring-mail = "0.4"""",
            configSection = """
                |[mail]
                |host = "smtp.gmail.com"
                |port = 465
                |secure = true
                |auth = { user = "your-email@gmail.com", password = "your-password" }
            """.trimMargin()
        ),
        PluginDefinition(
            id = REDIS,
            displayName = "spring-redis",
            description = "Redis client integration",
            dirName = "redis",
            moduleName = "redis",
            templatePath = "example-redis.rs",
            pluginClassName = "RedisPlugin",
            crateName = "spring_redis",
            dependency = """spring-redis = "0.4"""",
            configSection = """
                |[redis]
                |uri = "redis://127.0.0.1/"
            """.trimMargin()
        ),
        PluginDefinition(
            id = OPENDAL,
            displayName = "spring-opendal",
            description = "Storage abstraction via OpenDAL",
            dirName = "storage",
            moduleName = "storage",
            templatePath = "example-opendal.rs",
            pluginClassName = "OpenDALPlugin",
            crateName = "spring_opendal",
            dependency = """spring-opendal = { version = "0.4", features = ["services-fs"] }""",
            configSection = """
                |[opendal]
                |scheme = "fs"
                |options = { root = "/tmp" }
            """.trimMargin()
        ),
        PluginDefinition(
            id = POSTGRES,
            displayName = "spring-postgres",
            description = "PostgreSQL using tokio-postgres",
            dirName = "postgres",
            moduleName = "postgres",
            templatePath = "example-postgres.rs",
            pluginClassName = "PgPlugin",
            crateName = "spring_postgres",
            dependency = """spring-postgres = "0.4"""",
            configSection = """
                |[postgres]
                |connect = "postgres://user:password@localhost/dbname"
            """.trimMargin()
        ),
        PluginDefinition(
            id = SQLX,
            displayName = "spring-sqlx",
            description = "SQL database using SQLx",
            dirName = "sqlx",
            moduleName = "sqlx",
            templatePath = "example-sqlx.rs",
            pluginClassName = "SqlxPlugin",
            crateName = "spring_sqlx",
            dependency = """spring-sqlx = { version = "0.4", features = ["postgres"] }""",
            configSection = """
                |[sqlx]
                |uri = "postgres://user:password@localhost:5432/dbname"
            """.trimMargin()
        ),
        PluginDefinition(
            id = SEA_ORM,
            displayName = "spring-sea-orm",
            description = "SQL database using SeaORM",
            dirName = "sea_orm",
            moduleName = "sea_orm",
            templatePath = "example-sea_orm.rs",
            pluginClassName = "SeaOrmPlugin",
            crateName = "spring_sea_orm",
            dependency = """spring-sea-orm = { version = "0.4", features = ["postgres"] }""",
            configSection = """
                |[sea-orm]
                |uri = "postgres://user:password@localhost/dbname"
                |enable_logging = true
            """.trimMargin()
        ),
        PluginDefinition(
            id = APALIS,
            displayName = "spring-apalis",
            description = "Background job processing",
            dirName = "apalis",
            moduleName = "apalis",
            templatePath = "example-apalis.rs",
            pluginClassName = "ApalisPlugin",
            crateName = "spring_apalis",
            dependency = """spring-apalis = { version = "0.4", features = ["redis"] }""",
            extraDeps = """serde = { version = "1", features = ["derive"] }"""
            // Note: apalis uses redis config from spring-redis
        ),
        PluginDefinition(
            id = OPENTELEMETRY,
            displayName = "spring-opentelemetry",
            description = "Observability (tracing, metrics, logging)",
            dirName = "telemetry",
            moduleName = "telemetry",
            templatePath = "example-opentelemetry.rs",
            pluginClassName = "OpenTelemetryPlugin",
            crateName = "spring_opentelemetry",
            dependency = """spring-opentelemetry = { version = "0.4", features = ["jaeger"] }""",
            configSection = """
                |[logger]
                |pretty_backtrace = true
            """.trimMargin()
        ),
        PluginDefinition(
            id = SA_TOKEN,
            displayName = "spring-sa-token",
            description = "Authentication & authorization",
            dirName = "auth",
            moduleName = "auth",
            templatePath = "example-sa_token.rs",
            pluginClassName = "SaTokenPlugin",
            crateName = "spring_sa_token",
            dependency = """spring-sa-token = "0.4"""",
            configSection = """
                |[sa-token]
                |token_name = "Authorization"
                |timeout = 86400
                |active_timeout = 3600
                |auto_renew = true
                |is_concurrent = true
                |is_share = true
                |token_style = "Uuid"
            """.trimMargin()
        ),
        PluginDefinition(
            id = GRPC,
            displayName = "spring-grpc",
            description = "gRPC server/client via Tonic",
            dirName = "grpc",
            moduleName = "grpc",
            templatePath = "example-grpc.rs",
            pluginClassName = "GrpcPlugin",
            crateName = "spring_grpc",
            dependency = """spring-grpc = "0.4"""",
            extraDeps = "tonic = \"0.11\"\nprost = \"0.12\"",
            buildDeps = """tonic-build = "0.13"""",
            specialStructure = true
        )
    )

    private val pluginMap = plugins.associateBy { it.id }

    fun get(id: String): PluginDefinition? = pluginMap[id]

    fun all(): List<PluginDefinition> = plugins

    fun ids(): Set<String> = pluginMap.keys

    /**
     * Convert to selectable items for UI.
     */
    fun toSelectableItems(): List<SpringRsSelectableItem> =
        plugins.map { SpringRsSelectableItem(it.id, it.displayName, it.description) }
}