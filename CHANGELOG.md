# Changelog

## [0.1.0] - 2026-02-03

### Added

#### TOML Configuration Support
- **Smart Completion** — Auto-complete section names, keys, and values in `app.toml` / `app-*.toml`, based on Rust structs with `#[derive(Configurable)]` `#[config_prefix = "xxx"]`
- **Navigation** — Ctrl+Click on TOML keys/sections to jump to Rust struct/field; gutter icons on Rust structs navigate back to TOML
- **Validation** — Real-time inspections for unknown sections, keys, type mismatches, with quick-fixes (add/remove quotes, wrap in array, etc.)
- **Documentation** — Hover (Ctrl+Q) on TOML keys to see field type, default value, and doc comments
- **Enum Value Completion** — Enum fields show all available variants with navigation support

#### Route Explorer
- **Routes Tool Window** — Tree view listing all HTTP routes, grouped by crate/module/file
- **Color-coded Methods** — GET (green), POST (blue), PUT (orange), DELETE (red), PATCH (purple)
- **Search & Filter** — Filter routes by path, method, or handler name
- **Route Line Markers** — Gutter icons on handler functions showing HTTP method and path
- **Dual Pattern Support** — Supports attribute macros (`#[get("/path")]`) and Router builder (`Router::new().route()`)

#### Run Configuration
- **spring-rs Run Configuration** — Dedicated run config type with spring-rs branding
- **Gutter Run Icon** — Custom spring-rs icon on `main()` for quick launch

#### Service & Injection Markers
- **Service Markers** — Gutter icons for spring-rs services
- **Injection Markers** — Visual indicators for `#[inject]` fields with navigation

#### JSON to Rust Struct
- Two-pane dialog with JSON input and Rust output preview
- Options: Serialize, Deserialize, Debug, Clone derives
- Options: `#[serde(rename_all)]`, `Option<T>` wrapping, public fields/structs
- Value comments showing example JSON values
- History support for previous conversions
- Format JSON button

#### Custom File Icons
- spring-rs leaf icon for `app.toml` / `app-*.toml` config files

#### Platform Compatibility
- RustRover 2025.1+ (251)
- RustRover 2024.1 (241)
