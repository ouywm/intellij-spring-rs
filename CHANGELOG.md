# Changelog

## [Unreleased]

## [0.2.0] - 2026-02-08

### Added

- Search Everywhere support for routes, jobs, stream listeners, and components
- Environment variable completion, validation, documentation, and navigation for `${VAR}` in TOML
- Unified tool window with six types: Endpoint, Job, Stream Listener, Component, Configuration, Plugin
- Gutter line markers for jobs, stream listeners, cache, sa-token, Socket.IO, and middlewares
- `#[auto_config]` completion for `WebConfigurator` and `JobConfigurator`
- New project wizard with plugin selection, crates.io dependency search, and example code generation
- Sea-ORM code generation (Entity/DTO/VO/Service/Route) with live preview, prefix stripping, relation detection, column customization, and incremental merge
- Template system with `$tool` utilities and `$callback` output control
- Route conflict detection across files within the same crate, with click-to-navigate QuickFix
- Dependency injection validation for `#[inject(component)]` with framework component whitelist
- Component index scanning Service, Configuration, and Plugin definitions
- Handler parameter parser for `Path`, `Query`, `Json`, `Component`, `Config`, etc.
- Settings page with type mapping and template editor

### Changed

- Plugin registry refactored to interface + abstract base + singleton pattern
- Tool window upgraded from routes-only to unified multi-type view
- Code generation methods split into smaller focused functions
- Dependency versions updated: `tonic`/`prost`/`tonic-build` to 0.13, `sea-orm` to 1, `spring-stream` added `json` feature

### Removed

- main.rs icon override (`SpringRsMainFileIconProvider`)

## [0.1.0] - 2026-02-03

### Added

- TOML configuration completion, navigation, validation, and documentation
- Enum value completion from Rust enum definitions
- Routes tool window with tree view, color-coded methods, search, and filter
- Route gutter line markers for attribute macros and Router builder
- Dedicated spring-rs run configuration with gutter run icon
- Service and injection gutter markers with navigation
- JSON to Rust struct converter with two-pane dialog and history
- Custom leaf icon for `app.toml` / `app-*.toml` config files
- Platform support: RustRover 2025.1+ (251), RustRover 2024.1 (241)

[Unreleased]: https://github.com/ouywm/intellij-spring-rs/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/ouywm/intellij-spring-rs/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ouywm/intellij-spring-rs/releases/tag/v0.1.0
