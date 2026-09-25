# 更新日志

## 1.0.0

> 发布于 2026-09-25

### 新增

- 基于 MiuixGuiTemplate 0.4.0 脚手架创建 **HyperRefine** 项目（`Based on MiuixGuiTemplate 0.4.0`）
- 包名 / 应用 ID 定为 `cn.ianzb.hyperrefine`（`:hook` 模块为 `cn.ianzb.hyperrefine.hook`），工程名 / 应用名定为 HyperRefine
- 配置分组、DexKit 缓存目录等标识改为 `hyperrefine_*`（`hyperrefine_remote`、`hyperrefine_safe_mode`、`hyperrefine_prefs`、DexKit 缓存目录 `hyperrefine`）
- 应用图标：白色背景 + HyperOS 组件蓝圆润切割宝石（含刻面与星光点缀）
- 功能页（替换脚手架示例页）：系统界面 → 外观 → 三个平级功能入口
- **音量条 / 亮度条百分比数值显示**（适配 HyperOS 4，目标 `com.android.systemui` + 控制中心插件类）：
  - 控制中心音量条百分比数值显示
  - 控制中心亮度条百分比数值显示（含二级亮度条）
  - 侧边音量条百分比数值显示（按音量键呼出，含展开态各栏）
  - 三处独立配置：开关、字号、字重（细体 / 常规 / 中等 / 半粗 / 粗体 / 特粗，默认粗体）、字体颜色跟随图标（低值灰色、高值彩色；关闭后固定灰色）
  - 侧边音量条额外配置：百分比位置（音量区域上方悬浮 / 音量条内部上方）、长按打开音量面板并隐藏三个点按钮（避免与百分比数值重叠，默认关闭、常显于页面最前）
- **多级页面搜索**：功能页搜索支持多级嵌套（`HookSubPage.subPages` 递归），「功能页 → 外观 → 各位置」深处的功能均可被搜索直达（摘要显示「外观 / 控制中心音量条」路径，命中直接打开对应页面）
- **CI / Release 工作流**：新增 `.github/workflows/ci.yml`（Debug 构建 + Artifact）与 `release.yml`（签名 Release + GitHub Release + 可选 Telegram）；`app/build.gradle.kts` 增加基于环境变量的 `signingConfigs.release` 与 arm64-v8a ABI 拆分；`release.keystore` 与 GitHub Secrets 配置见 [README · 发布与 CI](README.md#发布与-ci)
- 全局灰色百分比调亮（`#959595` → `#BFBFBF`）
- 作用域：`com.android.systemui`（系统界面）、`com.miui.home`（系统桌面占位 `HomeLoad`）；不再申请 `system`（系统本体）作用域

### 变更

- 同步脚手架最新提交（`MiuixGuiTemplate 0.4.0 @ 676f1fa`、`@ 1e4d741`、`@ 544a858`、`@ 7c4d665`、`@ 5b7c4cf`、`@ 4027e9c`、`@ 5f9ff8c`、`@ 6a1eead`）：
  - `HookOptionsPage` 新增 `subPages` / `HookSubPage`（子页面功能并入功能页搜索）与 `topBarActions` 顶栏扩展槽
  - 新增通用 `QuickActionsAction`；`SubPageScaffold` / `BaseSubPageActivity` 新增 `topBarActions`
  - 新增 `ui/util/MiuixAnimations.kt`（`MiuixExpandSpec`，组件显隐统一 Miuix 弹簧动画）
  - 新增 `AppRestarter.restartSystemUi()`；`restart()` 对 `com.android.systemui` 特判（结束进程由系统自动拉起）
  - 新增 `BaseHook.target` 注入（`init()` 内可用 `target.classLoader`）
  - 右上角统一为「重启应用」：`MiuixIcons.Refresh` 图标 → `QuickActionDialog`（标题「重启应用」且无小标题，应用列表用 `Card` 圆角容器 + `CheckboxPreference`（对勾在右、默认全选），底部「全选 / 全不选」+「重启」，无勾选时禁用）
  - 文档新增「入口卡片文案」规范（二级菜单入口尽量不加小标题、大标题用总结性名词短语）
  - 子页面搜索支持多级嵌套：`HookSubPage` 新增 `subPages`（递归），多级页面内功能可被父页搜索直达
- 功能页小标题改为单语言（不再传 `titleEn`）；搜索文案「搜索组件」→「搜索功能」
- 许可要求调整：衍生项目只需在应用内「关于」页保留 `Based on MiuixGuiTemplate <版本号>` 标注，不再要求在各自 `README.md` 中标注；同步更新 README 与二次开发指南
- Telegram 群组文案：关于页「反馈渠道」改为「Telegram 群组」（英文 `Telegram Group`），README 顶部链接同步改为「Telegram 群组」；二次开发指南强调不要只写「反馈渠道 / 反馈方式」，以免用户看不出是 TG 群组
- 按入口卡片文案规范去掉二级菜单入口卡片的小标题（含「外观」入口），描述性内容仅保留在二级页面内；相应删除 `feature_appearance_summary`
- 移除自定义的 SystemUI 热重载机制（`PluginLoader` 宿主恢复 / bootstrap）：插件 ClassLoader 仅在插件加载时捕获一次，**开启开关后需重启系统界面生效**
- 全局移除热重载功能：删除设置页「全局热重载」、作用域页与重启应用入口中的热重载，以及 `XposedServiceManager.hotReload`/`runningTargets`、`NativeHookHelper.reset`、`PackageTarget.restored`、`XposedEntry` 的 `onHotReloading`/`onHotReloaded` 与 `module.prop` 的 `autoHotReload`；仅保留「重启」（含 SystemUI 重启优化）

### 移除

- 脚手架示例（`DemoHook` / 示例标签页 / 示例配置项）及示例作用域 `com.android.settings`
