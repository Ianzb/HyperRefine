# HyperRefine 开发索引

HyperRefine 是基于 MiuixGuiTemplate 的 LSPosed 模块（libxposed API 102 + Miuix Compose，AGPL-3.0），面向 HyperOS 4，minSdk 35，仅构建 arm64-v8a。

**这是唯一开发入口，不是完整手册。** 先按任务查表，只打开对应文档的指定章节；不要从 `docs/` 目录从头翻。

## 任务路由

| 我要做什么 | 只读 | 需要时再读 | 不需要读 |
|---|---|---|---|
| 第一次接触仓库 / 找某个文件 | [`docs/STRUCTURE.md`](docs/STRUCTURE.md) §1–4 | [`docs/API.md`](docs/API.md) §8 | `CUSTOMIZE` / `NATIVE_HOOK` |
| 新增或修改 JavaHook | [`docs/STRUCTURE.md`](docs/STRUCTURE.md) §4–5.1 + [`docs/API.md`](docs/API.md) §1、§7 | 对应 `docs/features/` 功能档案 | `CUSTOMIZE` / `NATIVE_HOOK` |
| 新增配置项 / 改功能页 UI | [`docs/STRUCTURE.md`](docs/STRUCTURE.md) §5.2–5.3 + [`docs/API.md`](docs/API.md) §3、§5 | `API.md` §5.7（可见性谓词） | `WORKFLOW` |
| 新增 / 调整二级页 | [`docs/STRUCTURE.md`](docs/STRUCTURE.md) §5.3 + [`docs/API.md`](docs/API.md) §6 | 父页 `HookSubPage` 登记处 | `NATIVE_HOOK` |
| DexKit / 混淆成员定位 | [`docs/API.md`](docs/API.md) §2 | `STRUCTURE.md` §7.2 搜索对照 | `CUSTOMIZE` |
| Rust 原生 Hook | [`docs/NATIVE_HOOK.md`](docs/NATIVE_HOOK.md) | `API.md` §1（共享 Hook 封装） | 通用接入章节 |
| 包名 / 图标 / 链接 / 模块元数据 | [`docs/CUSTOMIZE.md`](docs/CUSTOMIZE.md) §0–5 | `WORKFLOW.md` §6（公开仓库隐私） | `API.md` 全文 |
| 真机 adb / 逆向产物 / 第三方复用 | [`docs/WORKFLOW.md`](docs/WORKFLOW.md) | `STRUCTURE.md` §1.2（上下文边界） | `CUSTOMIZE` |
| 某个具体功能的实现或验证 | [`docs/features/README.md`](docs/features/README.md) 选一篇 | 对应代码文件 | 其他功能档案 |
| 版本记录 / 发布 / CI / 签名 | [`README.md`](README.md)「构建」「发布与 CI」+ [`changelog.md`](changelog.md) | — | `API.md` / `WORKFLOW` |

`docs/features/` 与 `docs/experimental/` 是功能档案，不是通用规范；只在修改该功能或核对行为时打开。实验性说明直接读 `docs/experimental/**/EXPERIMENT.md`。

## 文档职责

| 文档 | 只负责 | 明确不负责 |
|---|---|---|
| `AGENTS.md` | 任务路由、阅读路径、硬约束摘要 | 接口签名、完整教程、合规细则 |
| [`docs/STRUCTURE.md`](docs/STRUCTURE.md) | 仓库边界、目录地图、文件级改动路线、上下文纪律、验证清单 | 字段级接口说明、adb 授权流程 |
| [`docs/API.md`](docs/API.md) | 接口契约、签名、配置系统、组件行为、端到端接入 | 仓库导览、发布流程、设备合规 |
| [`docs/WORKFLOW.md`](docs/WORKFLOW.md) | 真机 adb 授权、逆向产物、公开仓库隐私、第三方许可证 | Hook 怎么写、UI 怎么接 |
| [`docs/CUSTOMIZE.md`](docs/CUSTOMIZE.md) | 二次开发的标识 / 图标 / 链接 / 元数据清单 | 通用 Hook 教程、配置组件 API |
| [`docs/NATIVE_HOOK.md`](docs/NATIVE_HOOK.md) | Rust NativeHook 的契约、构建、加载与排查 | JavaHook 接入、UI、合规全文 |
| [`docs/features/`](docs/features/README.md) | 单个功能的行为、实现位置、验证记录 | 跨功能规范、发布说明 |
| [`README.md`](README.md) | 安装、构建、发布、CI、签名、第三方库 | 开发教程、接口契约 |
| [`changelog.md`](changelog.md) | 各版本用户可见变更 | 设计说明、迁移指南 |

## 推荐阅读路径

1. **第一次改一个普通功能**：本页 → `STRUCTURE.md` §1–4 定位文件 → `API.md` §7.1 照链路接线 → 对应 `docs/features/` 档案。
2. **只改标题 / 默认值 / 显隐**：`STRUCTURE.md` §5.2 → `API.md` §3、§5；不要重读 Hook 章节。
3. **复刻其他模块 / 实现指定应用**：`WORKFLOW.md` §1 → `API.md` §2、§7；需要原生时再开 `NATIVE_HOOK.md`。
4. **发版**：`README.md`「构建」→ `changelog.md` 未发布段 → `README.md`「发布与 CI」。

## 硬约束（细节见 STRUCTURE / API / WORKFLOW）

- **配置键两端一致**：`OptionSpec.key` 必须等于对应 `BaseHook.key`；`HookPrefs.GROUP == PrefsStore.REMOTE_GROUP == "hyperrefine_remote"`，`prefs_key_` 前缀由代码处理，不手写。
- **作用域三处同步**：`scope.list` + `BaseLoad.targetPackages`；原生库名写入 `native_init.list`。
- **可见性**：依赖 / 设备不满足时灰显，不隐藏；显隐动画统一用 `MiuixExpandSpec`。
- **视觉类 Hook**：优先改官方状态谓词与渲染入口，禁止自建悬浮窗 / `RenderEffect` / 输入注入；解析失败必须 fail-closed。
- **合规**：任何 adb 操作逐次取得用户同意；逆向产物永不提交；第三方代码先查许可证并保留声明。
- **构建**：JDK 25；命令与产物见 [`README.md`](README.md)「构建」；只做文档任务时不构建。

## 上下文纪律

1. 不读整个仓库，不扫 `build/`、本地抓包和逆向目录。
2. 先用 `STRUCTURE.md` 的目录地图定位文件，再只读目标文件附近。
3. 改动前先确认它属于哪条链路：配置键 → Hook → Load → 注册 → 作用域 → UI → 文案 → changelog。
4. 只做文档任务时不构建；改代码后按 README「构建」验证。
