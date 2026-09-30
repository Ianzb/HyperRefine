# 更新日志

## 1.2.1

> 发布于 2026-09-30

### 修复

- **调整音量卡顿**（控制中心音量 / 侧边音量）：柔光玻璃挂在 `VolumePanelViewController.updateVolumeColumnSliderH` 这一高频回调上，每次都对全部音量列逐次反射重套模糊玻璃（`setMiViewBlurModeCompat` + `setMiBackgroundStyle`）并重走视图树，音量按键 / 拖动时重复执行数十次导致明显卡顿。改为以「面板重新展示 / 初始化」代次 + 展开态组成的状态签名缓存每个视图最近一次的套用结果，仅在签名变化（重新展示、展开态切换）时重套，其余调用直接跳过；并缓存视图 id 资源名，消除高频遍历中 `Resources.getResourceEntryName` 的开销。控制中心二级音量面板与侧边音量共用该类（`VolumePanelDelegate` 内直接实例化 `VolumePanelViewController`），同时受益

## 1.2.0

> 发布于 2026-09-30

### 新增

- **控制中心「柔光玻璃」**（适配 HyperOS 4，目标 `com.android.systemui` 控制中心插件）：把控制中心二级面板的按钮与卡片接入系统同款柔光玻璃，统一由**一个总开关**（默认关闭）控制，入口：功能页「控制中心 → 柔光玻璃」。覆盖：
    - **亮度二级**：大亮度条轨道 + 三个圆形按钮（开启态=系统白色遮罩，关闭态=默认玻璃；切换 / 深浅色变化后自动重套）
    - **WLAN / 移动数据 / 蓝牙详情**：顶部已连接卡片（激活玻璃，白色+描边）与下方列表组（系统 blend 玻璃，按组内位置共享一张圆角卡片），以及「更多设置」按钮
    - **控制中心音量 / 侧边音量**：音量条、静音 / 勿扰圆按钮（去深色底、保持圆形）、定时静音 / 勿扰滑块
    - **播放器**：设备卡片
    - 说明：全部反射调用系统自身接口（`MaterialBackgroundExt` / `MiBackgroundStyle` / `MiBlurCompat` / `QSTileItemIconView` 等），不复制任何系统代码；仅在总开关开启时挂载 Hook，避免空转

## 1.1.0

> 发布于 2026-09-29

### 新增

- **横屏融合设备中心右置**（适配 HyperOS 4，仅手机；目标 `com.android.systemui` + 控制中心插件类）：手机横屏时，控制中心按 `MainPanelContent.getRightOrLeft()` 把组件分入左右两列，融合设备中心入口（`DeviceCenterEntryController`）默认恒为左列；开启后覆盖其 `getRightOrLeft()` 返回 `true`，把融合设备中心移到右列。竖向布局不查询该方法，天然只在横屏生效。入口：功能页「控制中心 → 融合设备中心 → 横屏融合设备中心右置」
- **统一的「设备独占」API**：`OptionSpec.deviceScope: Set<DeviceType>?` + `rememberOptionEnabled(spec)`（依赖项 ∧ 设备白名单）。仅某类设备可用的功能在其它设备形态上**禁用灰显、不隐藏**；读取 `ConfigState`，切换「设置 → 当前设备类型」后实时刷新；所有 Hook 卡片统一改用 `rememberOptionEnabled` 作为 `enabled`
- **「设备互联」页面**：功能页新增二级页，集中三项目标 `com.milink.service` / `com.xiaomi.mirror` 的功能（新增精准作用域与 `MiLinkLoad` / `XiaomiMirrorLoad`）：
    - **解锁跨设备通知流转**（平板）：`com.milink.service:ui` 本机设备发现纠正 + `com.milink.crossdeviceservice` 通知点击复用原生 `PIN_APP` 流转并吞掉重复被动串流命令
    - **允许平板竖屏流转应用**（平板）：纠正 reason-9 首包横竖尺寸倒置，并放开目标 sink Activity 的方向策略
    - **MiLink Multi-Channel**：AndroidPad 共享通道上限 1 → 2，并仅在原生返回 `HostNotBound(215)` 时补做官方 Host 绑定
- **Hook 生效状态（广播回报）**：`BaseLoad` 完成注册后，目标进程合并已安装配置键**定向广播**回报；App 侧经发送者 UID / 包名 / 版本校验后按版本 + 开机号作用域持久化，`OptionSpec.showStatus` 在副标题显示「已生效 / 未生效」。已适配全部 Hook 功能（百分比显示、融合设备中心、快充通知、设备互联）；替代此前因 hook 侧只读而失效的状态提示

### 变更

- **移除 Hook 状态提示（标题染色）功能**：libxposed 远程文件为「App 写、Hook 读」，Hook 侧 `XposedInterface.openRemoteFile` 在 Vector 等新框架上明确为**只读**，原 `HookStatusWriter` 从 Hook 侧写入必然失败，状态始终为空、标题从不染色，故整体移除：删除 `HookStatusWriter` / `HookStatusReader` / `HookStatus` 枚举，移除 `OptionSpec.hookId` / `statusId` / `demoStatus`、`rememberHookStatus` / `HookStatusTitleColor`、各卡片 `titleColor` 染色、`hook_status_*` 字符串及 `BaseLoad` / `NativeHookHelper` / `XposedEntry` 中的状态写入调用
- 移除状态说明相关文档（`docs/API.md`、`docs/NATIVE_HOOK.md`、`docs/WORKFLOW.md`、`README.md` 同步），并新增「设备独占」API 文档
- 基于脚手架更新至 **MiuixGuiTemplate 0.5.0**（`about_based_on` 同步），并同步其「Hook 生效状态」机制
- 移植 [HyperConnectToolkit](https://github.com/silverpoetry/HyperConnectToolkit)（Apache-2.0）的设备互联三项功能，来源与许可已在应用内「第三方许可」页登记

### 修复

- **安全模式（崩溃循环保护）在 libxposed 上失效**：远程偏好对 hooked app 为**只读**，原 `SafeModeManager` 从 hook 侧写入必然抛 `UnsupportedOperationException`（被吞后静默失效，且每个进程刷一条 ERROR）。改为：hook 侧仅**只读** `safe_mode_<pkg>`；App 侧根据「目标进程成功装载 hook」的回报记录启动、窗口内重复启动累计为疑似崩溃，达到阈值（普通 3 / 关键 2）后把 `safe_mode_<pkg>` 回写远程偏好；hook 下次启动读到即跳过全部 hook。手动开关与重置同样回写

## 1.0.2

> 发布于 2026-09-26

### 新增

- **隐藏快充加速通知**（适配 HyperOS 4，目标 `com.miui.securitycenter`，功能页「安全服务 → 快充加速通知」二级页）：隐藏 90W 及以上快充机型在快充加速时由安全服务在「省电与电池重要通知」（`com.miui.powercenter.high`）类别下发布的通知。**「进入提醒」与「退出提醒」各自独立开关与状态**；手机与平板功能一致、仅混淆类名不同，统一用 DexKit 按字符串特征定位，不写死类名
- **重启成功提示**：`QuickActionDialog` 批量重启完成后弹出 `quick_action_restart_success`（「重启成功」），缺少 Root 时仍提示 `scope_restart_need_root`

### 变更

- 功能页新增「安全服务」分组，其功能收进二级页 `SecurityCenterActivity`（入口卡片 + 功能页搜索直达）
- 基于脚手架更新至 **MiuixGuiTemplate 0.4.1**（`about_based_on` 同步）
- 「顶栏重启应用」成功提示规范纳入脚手架，并随 **MiuixGuiTemplate 0.4.1** 发布（`docs/API.md` 5.6、`docs/CUSTOMIZE.md` 7.6）

## 1.0.1

> 发布于 2026-09-25

### 新增

- **融合设备中心隐藏省略号**（适配 HyperOS 4，目标 `com.android.systemui` + 控制中心插件类）：隐藏融合设备中心末尾的三个点（`DeviceCenterCardController` 追加的 `DeviceItem.DetailItem`）卡片，设备刚好占满一行时不再换行

### 变更

- 功能页路径重构：系统界面分组改为「控制中心」「侧边音量条」两个入口；控制中心二级页包含亮度条 / 音量条入口（名称去掉「控制中心」前缀，避免与入口重名）以及「融合设备中心」分组（内含隐藏省略号开关）；侧边音量条入口直达其配置页

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
- **应用内检查更新**：新增 `UpdateChecker`（请求 GitHub Releases API，比较语义化版本）与 `UpdateDialog`；设置页「更新」分区支持启动时自动检查与手动检查，发现新版本弹窗展示当前 / 最新版本与 Release 更新说明，确认后跳转 GitHub Release 下载页；替换原有的占位 Toast，`AndroidManifest` 增加 `INTERNET` 权限
- 全局灰色百分比调亮（`#959595` → `#BFBFBF`）
- 作用域：`com.android.systemui`（系统界面）、`com.miui.home`（系统桌面占位 `HomeLoad`）；不再申请 `system`（系统本体）作用域

### 变更

- **精简包体**：移除体积巨大的 `material-icons-extended`（约 83MB 的类），改用 `material-icons-core`，并在 `ui/icons/StatusIcons.kt` 内联原本使用的 3 个 Rounded 图标（外观不变）；Release APK 约 50MB → 约 32MB。（曾尝试 Release 启用 R8，但会破坏 libxposed hook 加载，故未启用）
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
- Telegram 发布支持话题：`release.yml` 新增可选 Secret `MESSAGE_THREAD_ID`，用于把 APK 发到多话题群（Forum）的指定话题（不填则发默认 / General 话题）
- 按入口卡片文案规范去掉二级菜单入口卡片的小标题（含「外观」入口），描述性内容仅保留在二级页面内；相应删除 `feature_appearance_summary`
- 移除自定义的 SystemUI 热重载机制（`PluginLoader` 宿主恢复 / bootstrap）：插件 ClassLoader 仅在插件加载时捕获一次，**开启开关后需重启系统界面生效**
- 全局移除热重载功能：删除设置页「全局热重载」、作用域页与重启应用入口中的热重载，以及 `XposedServiceManager.hotReload`/`runningTargets`、`NativeHookHelper.reset`、`PackageTarget.restored`、`XposedEntry` 的 `onHotReloading`/`onHotReloaded` 与 `module.prop` 的 `autoHotReload`；仅保留「重启」（含 SystemUI 重启优化）

### 移除

- 脚手架示例（`DemoHook` / 示例标签页 / 示例配置项）及示例作用域 `com.android.settings`
