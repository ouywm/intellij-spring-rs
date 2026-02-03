<div align="center">
  <img src="docs/images/spring-rs-logo.svg" alt="spring-rs Logo" width="120"/>
</div>

<h1 align="center">spring-rs Plugin for RustRover</h1>

<div align="center">
  IDE support for the <a href="https://github.com/spring-rs/spring-rs">spring-rs</a> framework — an application framework written in Rust, inspired by Java's SpringBoot
</div>

<div align="center">
  <b>English</b> ｜ <a href="README_zh.md">中文</a>
</div>

<div align="center">
  <a href="https://plugins.jetbrains.com/"><img src="https://img.shields.io/badge/JetBrains-Plugin-orange" alt="JetBrains Plugin"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"/></a>
</div>

<br/>

## Features

- **TOML Configuration Support** - Smart completion, validation, navigation, and documentation for `app.toml` config files
- **Route Tools** - Visual tool window for browsing and searching HTTP routes
- **Run Configuration** - Dedicated spring-rs run configuration with custom icons
- **JSON to Rust Struct** - Convert JSON to Rust struct definitions with serde attributes
- **Custom Icons** - spring-rs themed icons for config files and entry points

## Requirements

- RustRover 2025.2+ or IntelliJ IDEA 2023.3+ with the Rust plugin installed
- A Rust project using `spring-rs` dependencies

## Installation

1. Open your IDE and go to **Settings/Preferences** > **Plugins**
2. Click **Marketplace** and search for "spring-rs"
3. Click **Install** and restart your IDE

Or install manually:
1. Download the plugin ZIP from [Releases](https://github.com/spring-rs/spring-rs-plugin/releases)
2. Go to **Settings/Preferences** > **Plugins** > **Gear icon** > **Install Plugin from Disk...**
3. Select the downloaded ZIP file

---

## TOML Configuration Support

The plugin provides comprehensive IDE support for spring-rs configuration files (`app.toml`, `app-dev.toml`, `app-prod.toml`, etc.).

### Smart Completion

Auto-complete section names, keys, and values based on Rust struct definitions annotated with `#[derive(Configurable)]` `#[config_prefix = "xxx"]`.

<img src="docs/images/toml-section-completion.png" alt="TOML Section Completion" width="700"/>

<img src="docs/images/toml-key-completion.png" alt="TOML Key Completion" width="700"/>

<img src="docs/images/toml-value-completion.png" alt="TOML Value Completion" width="700"/>

### Enum Value Completion

Enum fields show all available variants from the Rust enum definition.

<img src="docs/images/enum-completion.png" alt="Enum Value Completion" width="700"/>

### Navigation

**Ctrl+Click** (or **Cmd+Click** on macOS) on any TOML key or section to jump directly to the corresponding Rust struct field.

<img src="docs/images/toml-navigation.gif" alt="TOML to Rust Navigation" width="700"/>

### Validation

Real-time inspections for:
- Unknown sections
- Unknown keys
- Type mismatches (e.g., string where integer expected)
- Invalid enum values

<img src="docs/images/toml-validation.png" alt="TOML Validation" width="700"/>

Quick-fixes are available for common issues:
- Add/remove quotes
- Wrap value in array
- Convert to inline table

<img src="docs/images/toml-quickfix.png" alt="TOML Quick Fix" width="700"/>

### Documentation

Hover over any TOML key or section (**Ctrl+Q** / **F1**) to see:
- Field type
- Default value
- Doc comments from Rust source
- Possible enum values

<img src="docs/images/toml-documentation.png" alt="TOML Documentation" width="700"/>

---

## Route Explorer

### Routes Tool Window

A dedicated panel listing every HTTP route in your project, grouped by module and file.

<img src="docs/images/routes-toolwindow.png" alt="Routes Tool Window" width="700"/>

Features:
- **Tree View** - Routes organized by crate > module > file
- **Color-coded Methods** - GET (green), POST (blue), PUT (orange), DELETE (red), PATCH (purple)
- **Search** - Filter routes by path, method, or handler name
- **Module Filter** - Show routes from specific crates only
- **Double-click** - Navigate directly to the handler function

### Route Gutter Icons

Gutter icons on handler functions showing the HTTP method and path.

<img src="docs/images/route-line-markers.png" alt="Route Line Markers" width="700"/>

Supports both:
- **Attribute macros**: `#[get("/path")]`, `#[post("/path")]`, `#[route("/path", method = "GET")]`
- **Router builder**: `Router::new().route("/path", get(handler))`

<img src="docs/images/router-builder.png" alt="Router Builder Support" width="700"/>

---

## Run Configuration

### spring-rs Run Configuration

Dedicated run configuration type with spring-rs branding.

<img src="docs/images/run-configuration.png" alt="Run Configuration" width="700"/>

### Gutter Run Icon

Custom spring-rs icon on `main()` function for quick launch.

<img src="docs/images/main-run-icon.png" alt="Main Run Icon" width="700"/>

Right-click the gutter icon to:
- Run the application
- Debug the application
- Edit run configuration

---

## JSON to Rust Struct

Convert JSON to Rust struct definitions with proper serde attributes.

### How to Use

1. **Right-click in editor** > **JSON to Rust Struct**, or
2. **Generate menu** (**Alt+Insert** / **Cmd+N**) > **JSON to Rust Struct**

<img src="docs/images/json-to-struct-menu.png" alt="JSON to Struct Menu" width="700"/>

### Conversion Dialog

Paste your JSON and configure options:
- Struct name
- Derive macros (Serialize, Deserialize, Debug, Clone)
- Field visibility (pub)
- serde rename attributes

<img src="docs/images/json-to-struct-dialog.png" alt="JSON to Struct Dialog" width="700"/>

### Features

- Nested objects become nested structs
- Arrays become `Vec<T>`
- Null values become `Option<T>`
- camelCase automatically converts to snake_case with serde rename
- Mixed types become `serde_json::Value`

---

## Custom File Icons

### Config File Icons

spring-rs configuration files (`app.toml`, `app-*.toml`) display a custom leaf icon.

<img src="docs/images/config-file-icon.png" alt="Config File Icon" width="400"/>

---

## Service & Injection Markers

### Service Markers

Gutter icons indicate spring-rs services and their injection points.

<img src="docs/images/service-markers.png" alt="Service Markers" width="700"/>

### Injection Markers

Visual indicators for `#[inject]` fields showing dependency relationships.

<img src="docs/images/inject-markers.png" alt="Inject Markers" width="700"/>

Click the icon to navigate between services and their injection sites.

---

## Configuration

The plugin automatically detects spring-rs projects by checking for spring-related dependencies in `Cargo.toml`:

```toml
[dependencies]
spring = "0.4"
spring-web = "0.4"
spring-sqlx = "0.4"
```

No additional configuration is required.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/spring-rs/spring-rs-plugin.git
cd spring-rs-plugin

# Build the plugin
./gradlew buildPlugin

# Run IDE with the plugin (for testing)
./gradlew runIde

# Run tests
./gradlew test
```

### Multi-Platform Build

Build for different IDE versions:

```bash
# RustRover 2025.2 (default)
./gradlew -PplatformVersion=252 buildPlugin

# IntelliJ IDEA 2024.1
./gradlew -PplatformVersion=241 buildPlugin

# IntelliJ IDEA 2023.3
./gradlew -PplatformVersion=233 buildPlugin
```

The built plugin ZIP is located at `build/distributions/`.

---

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## Related Links

- [spring-rs Framework](https://github.com/spring-rs/spring-rs)
- [spring-rs Documentation](https://spring-rs.github.io/)
- [JetBrains Plugin Repository](https://plugins.jetbrains.com/)

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## Sponsor

If this plugin is helpful to you, please consider supporting me:

<div align="center">
  <table>
    <tr>
      <td align="center"><b>WeChat</b></td>
      <td align="center"><b>Alipay</b></td>
    </tr>
    <tr>
      <td><img src="docs/images/wechat.jpeg" alt="WeChat" width="200"/></td>
      <td><img src="docs/images/alipay.jpeg" alt="Alipay" width="200"/></td>
    </tr>
  </table>
</div>