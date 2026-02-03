# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build plugin
./gradlew buildPlugin

# Run IDE with plugin (for testing)
./gradlew runIde

# Run tests
./gradlew test

# Build for different platform versions
./gradlew -PplatformVersion=251 buildPlugin  # RustRover 2025.1+ (default)
./gradlew -PplatformVersion=241 buildPlugin  # IntelliJ 2024.1
./gradlew -PplatformVersion=233 buildPlugin  # IntelliJ 2023.3
```

The built plugin ZIP is located at `build/distributions/`.

## Architecture Overview

This is an IntelliJ plugin for **spring-rs** (Rust Spring framework). It provides IDE support for TOML configuration files and HTTP route management.

### Core Data Flow

```
Rust Source Files (#[derive(Configurable)] structs)
        ↓
RustConfigStructParser (parses & caches TypeIndex)
        ↓
ConfigFieldModel / ConfigStructModel (data models)
        ↓
┌───────────────┬──────────────────┬─────────────────┐
│ Completion    │ Annotator        │ Documentation   │
│ (TOML keys/   │ (validation,     │ (hover info)    │
│  values)      │  type checking)  │                 │
└───────────────┴──────────────────┴─────────────────┘
```

### Key Modules

**parser/** - Core data source for all features
- `RustConfigStructParser`: Scans Rust files for `#[derive(Configurable)]` structs, builds cached `TypeIndex` (structs, enums, prefixToStruct mapping). Uses `CachedValuesManager` with PSI modification tracking.
- `StructFieldParser`: Parses individual struct fields (type, serde attributes, defaults, visibility).

**model/** - Data structures
- `ConfigFieldModel`: Represents a config field with name, type, wrapper (Option/Vec), serde attributes, documentation.
- `ConfigStructModel`: Represents a config struct with fields, prefix, derives.

**completion/** - TOML auto-completion
- `SpringRsConfigCompletionContributor`: Registers 4 completion providers for sections, keys, values, and inline tables.
- Pattern matching uses `PlatformPatterns` with TOML PSI types (`TomlTableHeader`, `TomlKeyValue`, `TomlInlineTable`).

**annotator/** - TOML validation
- `SpringRsConfigAnnotator`: Real-time validation for unknown sections, keys, type mismatches. Provides quick-fixes.

**routes/** - HTTP route management
- `SpringRsRouteIndex`: Collects routes from `#[get]`/`#[post]` attributes and `Router::new().route()` calls. Cached with modification tracking.
- `SpringRsRouteUtil`: Parses route paths, handles nest prefixes, joins path segments.

**markers/** - Gutter icons
- `SpringRsLineMarkerProvider`: Config struct → TOML navigation
- `SpringRsRouteLineMarkerProvider`: HTTP method badges on handler functions
- `SpringRsServiceLineMarkerProvider`: Component injection markers
- `SpringRsInjectLineMarkerProvider`: Field injection markers

**references/** - Navigation
- TOML key → Rust struct field navigation (Ctrl+Click)
- Enum value → Rust enum definition

**toolwindow/** - Routes panel
- `SpringRsRoutesToolWindow`: Lists all HTTP routes, grouped by module, with search/filter.

### Caching Strategy

The plugin uses IntelliJ's `CachedValuesManager` extensively:
- **Dependency index**: Tracks spring-related crates, invalidated by `rustStructureModificationTrackerInDependencies`
- **Project index**: Tracks project files, invalidated by `PsiModificationTracker`
- **Route index**: Invalidated by custom `SpringRsRouteModificationTracker` + TOML language tracker

### Multi-Platform Support

Build configuration supports multiple IDE versions via property files:
- `gradle.properties`: Default platform version
- `gradle-251.properties`: RustRover 2025.1+
- `gradle-241.properties`: IntelliJ 2024.1
- `gradle-233.properties`: IntelliJ 2023.3

### Dependencies

- **Rust plugin** (`com.jetbrains.rust`): For Rust PSI types (`RsStructItem`, `RsFunction`, etc.)
- **TOML plugin** (`org.toml.lang`): For TOML PSI types
- **JSON module** (`com.intellij.modules.json`): For JSON handling (251+)

### File Detection

Config files are detected by pattern: `app.toml` or `app-{env}.toml` (e.g., `app-dev.toml`).
Project detection uses `SpringProjectDetector` which checks for spring-rs dependencies in Cargo.toml.