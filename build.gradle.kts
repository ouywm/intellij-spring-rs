import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"

}

val platformVersion: String = providers.gradleProperty("platformVersion").orNull ?: "251"

val platformProps = Properties().apply {
    val f = file("gradle-$platformVersion.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun prop(name: String, default: String? = null): String {
    return providers.gradleProperty(name).orNull
        ?: platformProps.getProperty(name)
        ?: default
        ?: error("Missing Gradle property '$name' (platformVersion=$platformVersion)")
}

val pluginBaseVersion = prop("pluginVersion", "0.1.0")
val ideProduct = prop("ideProduct", "rustRover")
val ideVersion = prop("ideVersion", "2025.2")
val ijSinceBuild = prop("sinceBuild", platformVersion)
val ijUntilBuild = prop("untilBuild", "$platformVersion.*")

group = "com.springrs"
version = "$pluginBaseVersion-$platformVersion"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // Kotlin standard library
    implementation(kotlin("stdlib"))

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))

    intellijPlatform {
        // Use a specific IDE distribution per platform line.
        when (ideProduct) {
            "rustRover" -> rustRover(ideVersion)
            // For 2025.3+ you can use `intellijIdea(ideVersion)`.
            "ideaIC" -> intellijIdeaCommunity(ideVersion)
            "ideaIU" -> intellijIdeaUltimate(ideVersion)
            else -> error("Unsupported ideProduct='$ideProduct'. Expected: rustRover, ideaIC, ideaIU")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Plugin dependencies
        // RustRover bundles Rust plugin; for IntelliJ IDEA you must provide a marketplace version manually.
        if (ideProduct == "rustRover") {
            bundledPlugin("com.jetbrains.rust")
        } else {
            val rustPlugin = prop("rustPlugin", "")
            require(rustPlugin.isNotBlank()) {
                "ideProduct=$ideProduct requires -PrustPlugin=<pluginId:version> (e.g. -PrustPlugin=com.jetbrains.rust:241.19072.14)"
            }
            plugin(rustPlugin)
        }
        bundledPlugin("org.toml.lang")
        // Database Tools for Sea-ORM code generation (optional at runtime)
        bundledPlugin("com.intellij.database")
        // com.intellij.modules.json exists only in 251+
        if (platformVersion.toInt() >= 251) {
            bundledPlugin("com.intellij.modules.json")
        }
    }
}

// IntelliJ Platform configuration (IntelliJ Platform Gradle Plugin 2.x)
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = ijSinceBuild
            untilBuild = ijUntilBuild
        }

        changeNotes = """
        """.trimIndent()
    }

    // Work around local environment JDK/layout issues during instrumentation.
    // If you need IntelliJ instrumentation (nullability assertions / GUI Designer forms),
    // remove this line or set it back to `true`.
    instrumentCode.set(false)
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    patchPluginXml {
        sinceBuild.set(ijSinceBuild)
        untilBuild.set(ijUntilBuild)
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // Version-specific source directories (like intellij-rust project)
    sourceSets {
        main {
            kotlin.srcDirs("src/$platformVersion/main/kotlin")
        }
        test {
            kotlin.srcDirs("src/$platformVersion/test/kotlin")
        }
    }
}

// Version-specific resources
sourceSets {
    main {
        resources.srcDirs("src/$platformVersion/main/resources")
    }
}
