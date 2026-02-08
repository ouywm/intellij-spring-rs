<div align="center">
  <img src="docs/images/spring-rs-logo.svg" alt="spring-rs Logo" width="120"/>
</div>

<h1 align="center">spring-rs Plugin for RustRover</h1>

<div align="center">
  为 <a href="https://github.com/spring-rs/spring-rs">spring-rs</a> 框架提供的 IDE 支持 — 一个用 Rust 编写的应用框架，灵感来自 Java 的 SpringBoot
</div>

<div align="center">
  <a href="README.md">English</a> ｜ <b>中文</b>
</div>

<div align="center">
  <a href="https://plugins.jetbrains.com/"><img src="https://img.shields.io/badge/JetBrains-Plugin-orange" alt="JetBrains Plugin"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"/></a>
</div>

<br/>

## 功能特性

- **新建项目向导** - 引导式创建 spring-rs 项目，支持插件选择、示例代码生成和 crates.io 依赖搜索
- **Sea-ORM 代码生成** - 从数据库表生成 Entity/DTO/VO/Service/Route 完整 CRUD 层，支持多表模式、列自定义、外键定义、模板分组、类型映射和实时预览
- **TOML 配置支持** - 为 `app.toml` 配置文件提供智能补全、验证、导航和文档
- **环境变量支持** - 在 TOML 中对 `${VAR}` 引用提供自动补全、验证、文档和导航
- **全局搜索** - 通过 Search Everywhere 搜索 HTTP 路由、定时任务、流监听器和组件
- **统一工具窗口** - 浏览所有 spring-rs 项目元素：端点、任务、流监听器、组件、配置、插件
- **边栏图标标记** - 为路由、任务、流监听器、缓存、sa-token、Socket.IO、中间件提供可视化图标
- **路由冲突检测** - 跨文件重复路由检测，支持一键跳转到冲突位置
- **`#[auto_config]` 补全** - 自动补全配置器类型（`WebConfigurator`、`JobConfigurator`）
- **依赖注入验证** - 验证 `#[inject(component)]` 类型，内置框架组件白名单
- **运行配置** - 专用的 spring-rs 运行配置，带有自定义图标
- **JSON 转 Rust 结构体** - 将 JSON 转换为带有 serde 属性的 Rust 结构体定义
- **自定义图标** - 为配置文件提供 spring-rs 主题图标

## 系统要求

- RustRover 2025.1+ 或安装了 Rust 插件的 IntelliJ IDEA 2023.3+
- 使用 `spring-rs` 依赖的 Rust 项目

## 安装方法

1. 打开 IDE，进入 **Settings/Preferences** > **Plugins**
2. 点击 **Marketplace** 并搜索 "spring-rs"
3. 点击 **Install** 并重启 IDE

或手动安装：
1. 从 [Releases](https://github.com/spring-rs/spring-rs-plugin/releases) 下载插件 ZIP 文件
2. 进入 **Settings/Preferences** > **Plugins** > **齿轮图标** > **Install Plugin from Disk...**
3. 选择下载的 ZIP 文件

---

## 全局搜索 (Search Everywhere)

双击 **Shift** 打开 Search Everywhere，即可搜索整个项目中的 spring-rs 项目元素。

<img src="docs/images/search-everywhere.png" alt="全局搜索" width="700"/>

支持的搜索类型：
- **HTTP 路由** — 按路径（如 `/api/users`）或处理函数名搜索
- **定时任务** — 按函数名搜索，显示 cron/延迟/频率表达式
- **流监听器** — 按主题名称搜索
- **组件** — 搜索 Service、Configuration 和 Plugin 定义

每个结果显示图标、名称、详情和类型标签。点击即可跳转到源代码。

---

## TOML 配置支持

本插件为 spring-rs 配置文件（`app.toml`、`app-dev.toml`、`app-prod.toml` 等）提供全面的 IDE 支持。

### 智能补全

基于带有 `#[derive(Configurable)]` `#[config_prefix = "xxx"]` 注解的 Rust 结构体定义，自动补全 section 名称、键和值。

<img src="docs/images/toml-section-completion.png" alt="TOML Section 补全" width="700"/>

<img src="docs/images/toml-key-completion.png" alt="TOML Key 补全" width="700"/>

<img src="docs/images/toml-value-completion.png" alt="TOML Value 补全" width="700"/>

### 枚举值补全

枚举字段会显示 Rust 枚举定义中的所有可用变体。

<img src="docs/images/enum-completion.png" alt="枚举值补全" width="700"/>

### 导航

**Ctrl+Click**（macOS 上为 **Cmd+Click**）任意 TOML 键或 section，可直接跳转到对应的 Rust 结构体字段。

<img src="docs/images/toml-navigation.gif" alt="TOML 到 Rust 导航" width="700"/>

### 验证

实时检查：
- 未知的 section
- 未知的键
- 类型不匹配（例如，期望整数却提供了字符串）
- 无效的枚举值

<img src="docs/images/toml-validation.png" alt="TOML 验证" width="700"/>

常见问题提供快速修复：
- 添加/移除引号
- 将值包装为数组
- 转换为内联表

<img src="docs/images/toml-quickfix.png" alt="TOML 快速修复" width="700"/>

### 文档

将鼠标悬停在任意 TOML 键或 section 上（**Ctrl+Q** / **F1**）可查看：
- 字段类型
- 默认值
- Rust 源码中的文档注释
- 可用的枚举值

<img src="docs/images/toml-documentation.png" alt="TOML 文档" width="700"/>

---

## 环境变量支持

在 TOML 配置文件中为 `${VAR}` 和 `${VAR:default}` 环境变量引用提供完整的 IDE 支持。

<img src="docs/images/env-var-completion.png" alt="环境变量补全" width="700"/>

### 自动补全

在 TOML 值中输入 `${` 即可获得来自多个来源的补全建议（按优先级排序）：
1. **`.env` 文件** — 在 crate 根目录和工作区根目录的 `.env` 中定义的变量
2. **已使用的变量** — 在其他 TOML 配置文件中已引用的变量
3. **spring-rs 已知变量** — `SPRING_ENV`、`DATABASE_URL`、`REDIS_URL`、`HOST`、`PORT`、`LOG_LEVEL`、`RUST_LOG`、`MAIL_*`、`STREAM_URI`、`OTEL_*` 等
4. **系统环境变量** — 所有系统环境变量

### 验证

<img src="docs/images/env-var-validation.png" alt="环境变量验证" width="700"/>

- 未定义的变量（不在 `.env` 或系统环境中）显示警告
- 提供了默认值时不警告（`${VAR:fallback}`）
- 格式错误（空名称、无效字符）显示错误

### 文档

<img src="docs/images/env-var-documentation.png" alt="环境变量文档" width="700"/>

鼠标悬停在 `${VAR}` 上（**Ctrl+Q**）可查看：
- 变量名
- 当前值（来自系统环境或 `.env` 文件）
- 默认值（如果指定）
- 来源（`.env` 文件路径或"系统环境"）

### 导航

**Ctrl+Click** `${VAR_NAME}` 可跳转到 `.env` 文件中的变量定义。

<img src="docs/images/env-var-navigation.gif" alt="环境变量导航" width="700"/>

---

## 统一工具窗口

spring-rs 工具窗口（右侧边栏）提供项目中所有 spring-rs 元素的统一视图。

<img src="docs/images/tool-window.png" alt="统一工具窗口" width="700"/>

### 项目类型

通过 **Type** 下拉菜单切换不同的项目类型：

| 类型             | 说明                                                          |
|-----------------|---------------------------------------------------------------|
| Endpoint        | HTTP 路由（`#[get]`、`#[post]`、`Router::new().route()`）       |
| Job             | 定时任务（`#[cron]`、`#[fix_delay]`、`#[fix_rate]`）            |
| Stream Listener | 消息流处理器（`#[stream_listener("topic")]`）                    |
| Component       | 服务（`#[derive(Service)]`）及其注入点                           |
| Configuration   | 配置结构体，显示键值树（Rust 默认值 + TOML 覆盖值）               |
| Plugin          | 注册的插件（`App::new().add_plugin(XxxPlugin)`）                 |

### 特性

- **树状视图** — 按 crate > 模块 > 文件 组织
- **模块过滤** — 仅显示特定 crate 的元素
- **搜索** — 按名称、路径或表达式过滤
- **双击** — 直接导航到源代码
- **右键菜单** — 复制路径、跳转配置等

---

## 边栏图标标记

在 Rust 函数和结构体上提供丰富的边栏图标，一眼即可了解 spring-rs 注解信息。

### 路由标记

<img src="docs/images/route-line-markers.png" alt="路由行标记" width="700"/>

处理函数上的彩色 HTTP 方法标签。支持属性宏（`#[get("/path")]`）和 Router 构建器（`Router::new().route()`）。

<img src="docs/images/router-builder.png" alt="Router 构建器支持" width="700"/>

### 定时任务标记

<img src="docs/images/job-line-markers.png" alt="定时任务行标记" width="700"/>

定时任务函数上的时钟图标。悬停查看调度表达式：
- `#[cron("1/10 * * * * *")]` — Cron 表达式
- `#[fix_delay(10000)]` — 固定延迟（毫秒）
- `#[fix_rate(5000)]` — 固定频率（毫秒）
- `#[one_shot(3000)]` — 一次性执行（延迟后）

### 流监听器标记

<img src="docs/images/stream-listener-markers.png" alt="流监听器标记" width="700"/>

`#[stream_listener]` 函数上的监听器图标。悬停查看主题、消费模式和组 ID。

### 缓存标记

<img src="docs/images/cache-markers.png" alt="缓存标记" width="700"/>

`#[cache("key_pattern")]` 函数上的缓存图标。悬停查看键模式、过期时间和条件。

### sa-token 安全标记

<img src="docs/images/sa-token-markers.png" alt="sa-token 标记" width="700"/>

认证/授权注解上的安全图标：
- `#[sa_check_login]` — 登录检查
- `#[sa_check_role("admin")]` — 角色检查
- `#[sa_check_permission("user:delete")]` — 权限检查
- `#[sa_check_roles_and(...)]` / `#[sa_check_roles_or(...)]` — 多角色检查
- `#[sa_check_permissions_and(...)]` / `#[sa_check_permissions_or(...)]` — 多权限检查

### Socket.IO 标记

<img src="docs/images/socketio-markers.png" alt="Socket.IO 标记" width="700"/>

Socket.IO 事件处理器上的 Web 图标：
- `#[on_connection]` — 连接处理器
- `#[on_disconnect]` — 断开连接处理器
- `#[subscribe_message("event")]` — 消息订阅
- `#[on_fallback]` — 回退处理器

### 中间件标记

<img src="docs/images/middleware-markers.png" alt="中间件标记" width="700"/>

`#[middlewares(mw1, mw2, ...)]` 模块属性上的中间件图标。悬停查看中间件列表。

### 服务和注入标记

<img src="docs/images/service-markers.png" alt="服务标记" width="700"/>

边栏图标指示 spring-rs 服务及其注入点。

<img src="docs/images/inject-markers.png" alt="注入标记" width="700"/>

`#[inject]` 字段的可视化指示器，显示依赖关系。点击图标可在服务和注入位置之间导航。

---

## 路由冲突检测

插件检测同一 crate 内所有文件中的重复路由路径。

<img src="docs/images/route-conflict.png" alt="路由冲突检测" width="700"/>

- 冲突以 WARNING 级别标注
- 点击 QuickFix 可直接跳转到冲突的处理函数
- 检测遵循 crate 边界 — 不同 crate 中的路由不会冲突

---

## `#[auto_config]` 补全

在 `#[auto_config(...)]` 宏内自动补全配置器类型。

<img src="docs/images/auto-config-completion.png" alt="auto_config 补全" width="700"/>

支持的配置器：
- `WebConfigurator` — 注册 HTTP 路由处理器（来自 `spring-web`）
- `JobConfigurator` — 注册定时任务（来自 `spring-job`）

---

## 依赖注入验证

实时验证 `#[inject(component)]` 字段。

<img src="docs/images/di-validation.png" alt="依赖注入验证" width="700"/>

- 如果注入的组件类型未在项目中注册为 `#[derive(Service)]`，则显示警告
- 内置框架组件白名单：`DbConn`、`Redis`、`Postgres`、`Mailer`、`Op`、`Producer`、`Operator`

---

## 新建项目向导

通过引导式向导创建 spring-rs 新项目。

<img src="docs/images/wizard-plugin-selection.png" alt="插件选择" width="700"/>

### 插件选择

从 13 个 spring-rs 插件中选择，以 2 列网格展示：

- **Web** — `spring-web`（基于 Axum 的 HTTP 服务器）
- **gRPC** — `spring-grpc`（基于 Tonic 的 gRPC 服务器）
- **PostgreSQL** — `spring-postgres`（原生 PostgreSQL）
- **SQLx** — `spring-sqlx`（异步 SQL 查询）
- **Sea-ORM** — `spring-sea-orm`（SeaORM 集成）
- **Stream** — `spring-stream`（消息流）
- **Mail** — `spring-mail`（基于 Lettre 的邮件发送）
- **Redis** — `spring-redis`（Redis 客户端）
- **OpenDAL** — `spring-opendal`（统一存储访问）
- **Job** — `spring-job`（基于 Tokio-cron 的定时任务）
- **Apalis** — `spring-apalis`（后台任务处理）
- **sa-token** — `spring-sa-token`（会话/权限认证）
- **OpenTelemetry** — `spring-opentelemetry`（链路追踪/指标）

### 额外依赖

<img src="docs/images/wizard-extra-deps.png" alt="额外依赖" width="700"/>

从 crates.io 搜索并添加自定义 crate 依赖：

- 左侧面板展示搜索结果，支持滚动到底部自动分页加载
- 右侧面板展示已添加的依赖
- 生成 `Cargo.toml` 时自动与插件依赖进行去重

### 示例代码生成

可选择为每个选中的插件生成可编译的示例代码，基于官方 spring-rs 示例。

---

## Sea-ORM 代码生成

从数据库表生成完整的 CRUD 代码层。

<img src="docs/images/codegen-dialog.png" alt="代码生成对话框" width="700"/>

### 使用方法

1. 在 IDE 中打开 **Database** 工具窗口
2. 选择一个或多个表
3. 右键 > **Generate Sea-ORM Code**
4. 配置输出路径、表名/列名前缀去除、选择生成层
5. 点击 **预览** 查看生成代码，或点击 **生成** 直接写入文件

### 生成层

| 层       | 说明                                                                          |
|---------|-------------------------------------------------------------------------------|
| Entity  | 带有 `DeriveEntityModel` 的实体结构体，包含列类型、关联关系和 `prelude.rs` 导出     |
| DTO     | `CreateDto` + `UpdateDto` + `QueryDto`，使用 `DeriveIntoActiveModel` 和 `IntoCondition` 过滤器构建器 |
| VO      | 用于 API 响应的视图对象                                                          |
| Service | 带有 `#[derive(Service)]` 的 CRUD 服务，支持分页和条件查询                          |
| Route   | Axum 路由处理函数，RESTful 风格 CRUD 端点                                         |

### 多表模式

选择多张表时，对话框左侧显示表列表。点击任意表可独立配置：

- **实体名称** — 每张表自定义实体结构体名称
- **层开关** — 每张表独立启用/禁用 Entity、DTO、VO、Service、Route
- **输出目录** — 每张表可设置不同的输出文件夹
- **Derive 宏** — 每张表独立配置 Entity/DTO/VO 的 derive 宏
- **路由前缀** — 每张表自定义路由前缀

所有配置跨会话持久化保存。

### 列自定义

点击 **定制列** 按钮打开列编辑器：

- **包含/排除** — 通过复选框控制列的生成；排除的列在生成时跳过
- **覆盖 Rust 类型** — 通过可编辑下拉框更改生成的 Rust 类型（`String`、`i32`、`i64`、`DateTime`、`Uuid`、`Json`、`Decimal` 等）
- **编辑注释** — 覆盖列注释，出现在生成的文档注释中
- **虚拟列** — 添加不在数据库中的列（以绿色显示）。适用于计算字段或模板驱动的自定义字段
- **Ext 属性** — 为任意列附加键值元数据，在模板中通过 `$column.ext.key` 访问

### 外键定义

点击 **定义外键** 按钮定义表之间的逻辑外键关系：

- **双向视图** — 显示当前表涉及的所有外键（作为源表和目标表两个方向）
- **四列布局** — 源表 / 源列 / 目标表 / 目标列
- **动态下拉** — 列下拉框根据选择的表自动填充该表的列
- **限定范围** — 仅显示当前生成会话中选中的表
- **自动反向关系** — 代码生成时，`BelongsTo` 外键自动在目标实体上生成 `HasMany` / `HasOne` 反向关系

### 实时预览

点击 **预览** 在写入磁盘前查看所有生成的文件。

<img src="docs/images/codegen-preview.png" alt="代码生成预览" width="700"/>

- 左侧面板：文件树，显示所有将要生成的文件
- 右侧面板：语法高亮的代码预览
- 支持生成前重命名文件

### 特性

- **前缀去除** — 去除表名/列名前缀（如 `sys_user` → `User`），输入后立即生效
- **Schema 支持** — PostgreSQL 非 public schema 生成正确的模块路径和导入
- **智能 `mod.rs` 合并** — 仅追加新的 `mod` 声明，永不删除已有模块
- **`prelude.rs` 生成** — 自动生成实体 re-export，方便导入使用
- **文件冲突解决** — 逐文件选择：**跳过**、**覆盖** 或 **备份后覆盖**。`mod.rs` 和 `prelude.rs` 始终增量合并
- **rustfmt 集成** — 可选对所有生成文件运行 `rustfmt`（在设置中配置）
- **多数据库方言** — PostgreSQL、MySQL、SQLite，各有对应的类型映射
- **`$tool` / `$callback`** — 模板工具函数和输出控制：
  - `$tool.firstUpperCase(str)` / `$tool.firstLowerCase(str)` — 大小写转换
  - `$tool.newHashSet()` — 创建 HashSet，用于模板中去重
  - `$callback.setFileName(name)` — 覆盖输出文件名
  - `$callback.setSavePath(path)` — 覆盖输出目录

### 设置

在 **Settings** > **spring-rs** > **Code Generation** 中配置代码生成。

<img src="docs/images/codegen-settings.png" alt="代码生成设置" width="700"/>

#### 通用设置

| 选项 | 说明 |
|------|------|
| 数据库类型 | 选择 MySQL、PostgreSQL 或 SQLite |
| 自动检测前缀 | 自动检测并去除表名前缀 |
| 表名 / 列名前缀 | 手动指定要去除的前缀 |
| 路由前缀 | 生成处理函数的默认路由路径前缀 |
| 生成 serde | 在 Entity 上添加 `Serialize` / `Deserialize` derive |
| 生成 ActiveModel From | 在 DTO 上添加 `DeriveIntoActiveModel` |
| 生成文档注释 | 在注释中包含表/列信息 |
| 生成 QueryDto | 生成 `QueryDto` 及 `IntoCondition` 过滤器构建器 |
| 自动插入 mod | 自动在 `mod.rs` 中添加模块声明 |
| 运行 rustfmt | 生成后用 `rustfmt` 格式化文件 |
| 冲突策略 | 默认文件冲突解决方式：跳过 / 覆盖 / 备份 |

#### 模板

- **模板分组** — 新建、复制、删除、通过 JSON 导入/导出模板分组。当前活跃分组的模板用于代码生成
- **模板编辑器** — 带语法高亮的 Velocity 模板编辑器。可用变量：`$table`、`$columns`、`$tool`、`$callback`、`$author`、`$date` 及所有全局变量
- **全局变量** — 定义键值对（每行一个），在所有模板中通过 `$key` 访问

#### 类型映射

- **映射分组** — 克隆内置分组或按方言创建自定义分组
- **正则支持** — 列类型模式支持正则表达式（如 `varchar(\(\d+\))?` 匹配 `varchar`、`varchar(255)`）
- **导入/导出** — 以 JSON 文件共享类型映射
- **重置为默认值** — 恢复方言默认映射

---

## 运行配置

### spring-rs 运行配置

带有 spring-rs 品牌标识的专用运行配置类型。

<img src="docs/images/run-configuration.png" alt="运行配置" width="700"/>

### 边栏运行图标

`main()` 函数上的自定义 spring-rs 图标，用于快速启动。

<img src="docs/images/main-run-icon.png" alt="Main 运行图标" width="700"/>

右键点击边栏图标可：
- 运行应用
- 调试应用
- 编辑运行配置

---

## JSON 转 Rust 结构体

将 JSON 转换为带有正确 serde 属性的 Rust 结构体定义。

### 使用方法

1. **在编辑器中右键** > **JSON to Rust Struct**，或
2. **Generate 菜单**（**Alt+Insert** / **Cmd+N**）> **JSON to Rust Struct**

<img src="docs/images/json-to-struct-menu.png" alt="JSON 转结构体菜单" width="700"/>

### 转换对话框

粘贴 JSON 并配置选项：
- 结构体名称
- Derive 宏（Serialize、Deserialize、Debug、Clone）
- 字段可见性（pub）
- serde rename 属性

<img src="docs/images/json-to-struct-dialog.png" alt="JSON 转结构体对话框" width="700"/>

### 功能特点

- 嵌套对象转换为嵌套结构体
- 数组转换为 `Vec<T>`
- null 值转换为 `Option<T>`
- camelCase 自动转换为 snake_case 并添加 serde rename
- 混合类型转换为 `serde_json::Value`

---

## 自定义文件图标

### 配置文件图标

spring-rs 配置文件（`app.toml`、`app-*.toml`）显示自定义叶子图标。

<img src="docs/images/config-file-icon.png" alt="配置文件图标" width="400"/>

---

## 配置

插件通过检查 `Cargo.toml` 中的 spring 相关依赖自动检测 spring-rs 项目：

```toml
[dependencies]
spring = "0.4"
spring-web = "0.4"
spring-sqlx = "0.4"
```

无需额外配置。

---

## 从源码构建

```bash
# 克隆仓库
git clone https://github.com/spring-rs/spring-rs-plugin.git
cd spring-rs-plugin

# 构建插件
./gradlew buildPlugin

# 运行带有插件的 IDE（用于测试）
./gradlew runIde

# 运行测试
./gradlew test
```

### 多平台构建

为不同 IDE 版本构建：

```bash
# RustRover 2025.1+（默认）
./gradlew -PplatformVersion=251 buildPlugin

# IntelliJ IDEA 2024.1
./gradlew -PplatformVersion=241 buildPlugin

# IntelliJ IDEA 2023.3
./gradlew -PplatformVersion=233 buildPlugin
```

构建的插件 ZIP 文件位于 `build/distributions/`。

---

## 贡献

欢迎贡献！请随时提交 Pull Request。

1. Fork 仓库
2. 创建功能分支（`git checkout -b feature/amazing-feature`）
3. 提交更改（`git commit -m 'Add some amazing feature'`）
4. 推送到分支（`git push origin feature/amazing-feature`）
5. 创建 Pull Request

---

## 相关链接

- [spring-rs 框架](https://github.com/spring-rs/spring-rs)
- [spring-rs 文档](https://spring-rs.github.io/)
- [JetBrains 插件仓库](https://plugins.jetbrains.com/)

---

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

---

## 赞助

如果这个插件对你有帮助，欢迎请我喝杯咖啡：

<div align="center">
  <table>
    <tr>
      <td align="center"><b>微信</b></td>
      <td align="center"><b>支付宝</b></td>
    </tr>
    <tr>
      <td><img src="docs/images/wechat.jpeg" alt="微信" width="200"/></td>
      <td><img src="docs/images/alipay.jpeg" alt="支付宝" width="200"/></td>
    </tr>
  </table>
</div>
