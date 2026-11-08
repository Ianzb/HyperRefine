# 模块开发工作流

> **文档定位**
> - 负责：真机 adb 授权、逆向定位流程、公开仓库隐私、第三方许可证与复用红线。
> - 不负责：Hook 代码怎么写（见 [`API.md`](API.md)）、仓库文件地图（见 [`STRUCTURE.md`](STRUCTURE.md)）、二次开发标识清单（见 [`CUSTOMIZE.md`](CUSTOMIZE.md)）。
> - 前置：只处理“如何安全地拿到定位信息 / 如何合规复用”；接口与接线回到 [`API.md`](API.md) §7。

本文档规范使用本模板（配合 AI 编码助手 / opencode）开发 LSPosed 模块时的标准流程，重点覆盖两类高频请求：

1. **指定应用的 Hook 实现** —— 把某款应用（尤其是小米 / HyperOS 应用）的某个功能改为指定效果；
2. **参考其他模块项目复刻功能** —— 把其他 Xposed / LSPosed 模块的某个功能迁移到本项目。

两类请求都必须遵守下面的**设备授权原则**与**开源合规要求**。

---

## 0. 基本原则（必读）

- **改机需授权**：任何会接触用户真实设备的操作（尤其是 `adb`）都必须先取得用户明确同意，逐次授权，绝不擅自连接或扫描用户设备。
- **最小权限**：默认只读；只执行定位 Hook 所必需的命令，不做与需求无关的探索。
- **隐私优先**：不抓取、不保存、不上传用户个人数据；只保留包名、类名、方法签名等定位信息。公开仓库的提交红线（序列号、用户名、绝对路径等）见 [第 6 节](#6-隐私保护公开仓库)。
- **协议优先**：复用第三方代码前先看许可证；无许可证 / 非开源的一律只借鉴思路、不复用代码，并补致谢。
- **可追溯**：每一次引入的第三方内容都记录来源 / 许可 / 改动，落到第三方许可证与致谢页面。

---

## 1. 场景 A：实现指定应用的 Hook

### 1.1 用户应提供的信息

尽量由用户提供，缺失部分再由助手通过真机扫描 / 静态分析补齐：

| 信息 | 说明 |
|---|---|
| 目标应用 | 应用名 + 包名（如 `com.miui.xxx`） |
| 目标功能 | 要修改的具体功能入口 |
| 期望效果 | Hook 之后的行为（返回值 / 拦截 / 注入等） |
| Hook 功能描述 | 已知的类名 / 方法名 / 字段，或大致逻辑 |
| 修改位置 | 对应到本模板的落点（`BaseHook` / `BaseLoad` / `OptionSpec` 等） |

> 用户暂时给不出类 / 方法时，可只描述现象与期望，再由助手分析定位；此时才需要进入 1.2 的真机扫描流程。

### 1.2 申请设备扫描权限

- 需要真机分析时，助手**必须主动向用户申请**，说明目的与将要执行的确切命令；用户也可主动授权。
- **未获授权前不得执行任何 `adb` 命令**，不得连接、枚举或扫描用户的小米设备。
- 授权按批次进行：每次执行前列出命令清单，用户明确同意（如回复「允许」）后才可执行。
- 用户可随时撤回授权，助手应立即停止后续 `adb` 操作。

### 1.3 adb 安全白名单

> 以下命令仅为**候选**，仍需逐条获得用户同意；白名单之外的命令默认禁止。

**允许（只读 / 诊断）**

- `adb devices`
- `adb shell getprop`
- `adb shell pm list packages [filter]`
- `adb shell pm path <pkg>`
- `adb shell dumpsys package <pkg>`
- `adb shell ps -A`（可配合过滤目标包）
- `adb shell dumpsys activity ...`、`adb shell dumpsys window ...` 等只读子系统
- `adb logcat -d`（仅本机日志，按需按标签 / PID 过滤）
- `adb pull <目标应用 APK 路径> <本地目录>`（仅目标应用安装包，用于静态分析；需单独说明并授权）
- `adb shell uiautomator dump`（读取当前界面结构；涉及界面内容，需单独说明）

**禁止（默认绝对不允许）**

- 修改设备 / 数据：`pm uninstall|disable|enable|clear|install`、`settings put`、`content insert|update|delete`、`rm`、`mv`、`dd`、`svc`、`wm`、`reboot` 等
- 抓取隐私：`adb backup`、`adb pull /sdcard` 或任何用户目录、`screencap`、`screenrecord`，以及读取账号 / 短信 / 联系人 / 相册等
- 提权 / 远程：`adb root`、`adb remount`、`adb tcpip`、`adb connect`（未知设备）、`adb shell su` 等
- 批量 / 注入：`monkey`、`input` 注入、循环刷屏等
- 任何未在本次授权清单中的命令

### 1.4 定位 Hook 目标

1. 明确功能入口与调用链；优先静态分析（反编译 / DexKit），尽量减少真机操作。
2. 用 `dumpsys package` / `pm path` 确认版本与安装路径，必要时经授权 `adb pull` 目标 APK。
3. 通过 `logcat` / `uiautomator dump` 验证触发路径（需授权）。
4. 产出类名、方法签名、字段、混淆映射等定位信息，并据此编写 Hook；逆向产物与报告按 [1.5](#15-逆向产物与报告存放) 统一落盘到 `reverse_report/`。

### 1.5 逆向产物与报告存放

所有逆向分析的中间产物与分析报告统一放在仓库根的 `reverse_report/`，**按目标应用（包名）分类**。该目录已在仓库 `.gitignore` 中忽略，**永不入库、不产生 git 痕迹**，以避免第三方应用代码 / 资源的版权与合规风险（不要 `git add -f` 强制提交）。

- **目录约定**：`reverse_report/<包名>/`；通用 / 跨应用内容放 `reverse_report/_common/`。新增目标应用时新建 `<包名>/` 并登记到 `reverse_report/README.md`。
- **默认落盘**（均不得提交）：`adb pull` 的 APK、反编译输出（jadx / apktool / smali）、DexKit 定位结果与 dex 转储、类名 / 方法签名 / 字段与混淆映射、Frida / hook 脚本、关键 `logcat` / `uiautomator dump` 抓取、分析笔记与最终报告。
- **命名建议**：`<日期>-<主题>.<扩展名>`，如 `20260930-volume-method.txt`。
- **内容边界**：只保留定位信息（包名、类名、方法签名、字段、必要反编译片段），不保存用户个人数据，不把第三方专有代码 / 资源复制进项目源码或可提交文件。

### 1.6 实现与验证

按 [二次开发指南](CUSTOMIZE.md) 第 6 节落地：

1. 在 `HookEntryRegistry` 登记 `BaseLoad`；
2. 新建 `BaseHook` 实现 `init()`，必要时用 DexKit 定位成员；
3. 声明 `OptionSpec` 并接入**功能页**（`ui/screen/features/FeaturesPage.kt`），配置键两端一致；
4. 功能位于子页面时，继承 `BaseSubPageActivity` 并在 `AndroidManifest.xml` 注册，再把子页配置项通过 `HookSubPage` 传入父页 `HookOptionsPage(subPages = ...)`，使其可被功能页搜索直达；
5. 更新 `META-INF/xposed/scope.list`；
6. 构建并在 LSPosed 中验证功能生效（启用开关并重启目标应用）。

### 1.7 页面组织规范

- **页面名用功能**：模板的示例页已按真实形态命名为「功能」页（`ui/screen/features/FeaturesPage.kt`，子页 `FeatureSubPageActivity.kt`）。新增页面沿用功能命名，不要以组件类型命名。
- **小标题单语言**：功能页分区小标题只传 `HookSection.titleRes`，**不要传 `titleEn`**。`titleEn`（拼成 `中文（English）`）仅用于模板示例展示 API 英文组件名，英文名以[接口文档](API.md) 5.1 为准。
- **入口卡片少用小标题、大标题要总结性**：作为二级菜单入口的卡片（跳转二级页 / 弹出对话框）应尽量**不加小标题**（`summaryRes`，即副标题）；卡片**大标题**（`titleRes`）用总结性名词短语（如「高级选项」「重启应用列表」），**不要**写成描述性句子；描述性说明放到二级页面内。详见[接口文档](API.md) 5.6。
- **搜索入口覆盖子页面**：功能若位于子页面，子页面自身不放搜索栏；在父页 `HookOptionsPage(subPages = listOf(HookSubPage(titleRes, specs, onOpen)))` 中登记，即可在功能页搜索中直达该子页面。

### 1.8 视觉 / 输入法类 Hook 的定位顺序

材质、圆角、模糊、动画这类“看起来是画出来的”功能，先按下面的顺序找**状态**，不要一上来找绘制 API：

1. **找总开关**：设置页里对应的 preference key（如 `hyper_material_enabled`），确认默认值和写入点。
2. **找包名/设备谓词**：官方通常用允许表、黑名单、系统属性或 `Settings.Secure` 决定是否启用（如 `f10213x` / `f10214y` / `persist.sys.advanced_visual_release`）。先确认当前应用是被哪个谓词挡住的。
3. **找重算入口**：状态在 `onStartInput` / `onStartInputView` / `onWindowShown` / 配置变更时重算；只 hook 一次 `onCreate` 会漏掉切换输入框、切换应用和深色模式。
4. **找官方绘制层**：确认官方已经创建了材质 `View` / `OutlineProvider` / `Window` 参数后，只改谓词或状态，让官方渲染器继续工作。
5. **最后才考虑自绘**：只有官方完全没有入口时才自绘，并单独评估 Window 层级、Insets、输入法焦点和窗口动画；禁止用 `InputMonitor` / 注入触摸补手势。

记录定位信息时同时写下：应用版本、系统版本、类名、方法签名、字段名、谓词含义、验证日志标签。版本升级后先复核谓词是否还在，不要假设混淆名稳定。

---

## 2. 场景 B：参考 / 复刻其他模块项目

当用户提供其他模块的仓库地址时，**先做许可证审查，再决定复用方式**。

### 2.1 先看许可证

- 查看仓库根目录的 `LICENSE` / `COPYING` / `NOTICE`，以及源文件头的 SPDX 标识。
- 明确：许可证类型、是否允许闭源 / 是否传染、是否要求署名。

### 2.2 兼容性判断（本项目为 AGPL-3.0）

| 来源许可证 | 复用代码 | 处理方式 |
|---|---|---|
| Apache-2.0 / MIT / BSD | 可以 | 保留原始版权与许可声明，登记到致谢页 |
| LGPL-3.0 | 可以 | 保留声明，衍生作品保持 LGPL / GPL |
| GPL-3.0 | 可以 | AGPL-3.0 §13 与 GPL-3.0 兼容，组合后整体以 AGPL-3.0 分发，保留声明 |
| AGPL-3.0 | 可以 | 本项目同为 AGPL-3.0，保留声明即可 |
| 无许可证 / 非开源 | 不可以 | 只借鉴思路，不复用任何代码 / 资源，补致谢 |

> 无论哪种情况，都不得复制小米 / HyperOS 等专有应用的代码或资源。

### 2.3 无许可证 / 非开源项目

- **不得复制**其代码、资源、字符串、图标等。
- 可以独立实现相同思路，并在**第三方许可证与致谢页面加入致谢**。
- 在 `LicensePage.kt` 的参考分组登记：`LibraryInfo(name, version, "非开源，仅思路参考", website)`，同时更新 `README.md` 与 `docs/CUSTOMIZE.md`。
- 注明「仅设计 / 思路参考，未复用其代码」，避免被误解为开源依赖。

### 2.4 复用来源文件的规范

- 保留原始文件头（版权 / SPDX / 许可）。
- 标注本项目的修改点（文件头或相邻注释）。
- 在 `licenseSections` 对应分组更新条目，`README.md` 与 `docs/CUSTOMIZE.md` 同步。

---

## 3. 第三方许可证与致谢维护

统一落点：

| 落点 | 文件 |
|---|---|
| 应用内许可证页 | `app/.../ui/screen/about/LicensePage.kt` → `licenseSections` |
| 页面分组 / 文案 | `res/values/strings.xml`、`values-en/strings.xml` → `licenses_section_*`、`licenses_header` |
| README | `README.md`「参考与致谢」 |
| 二次开发指南 | `docs/CUSTOMIZE.md` 第 11 节 |
| 更新日志 | `changelog.md` |

新增开源依赖放入对应技术分组；无许可证 / 非开源参考放入「参考项目」分组并标注许可状态。

### 非开源参考的登记

对未声明开源许可证的项目：

- **不得复制**其代码、资源或文案；
- 不得将其列入「开源项目 / 第三方许可证」的许可清单；
- 如需致谢，在应用内「参考项目」分组列出并标注「非开源，仅思路参考」。

文档中不展开对具体模块的借鉴说明，具体清单以应用内致谢页为准。

---

## 4. 构建与日志（避免命令卡死）

> **警告**：不要用 PowerShell 管道截断长驻命令（Gradle / `adb logcat` 等）的输出，例如
> `.\gradlew.bat ... 2>&1 | Select-Object -Last 80`。`Select-Object -Last` 会先缓冲整条输出流，
> 而 Gradle 守护进程会一直保持 stdout 打开，导致命令长时间无输出、**看似卡死**（实际在等待流结束）。

**推荐做法（二选一）**

1. 直接运行，让终端 / 工具自行捕获与截断输出：
   ```
   .\gradlew.bat :hook:compileDebugKotlin :app:assembleDebug --console=plain
   ```
2. 需要落盘后再看尾部时，重定向到**临时目录**日志（勿提交），再读取尾部：
   ```
   .\gradlew.bat :hook:compileDebugKotlin :app:assembleDebug --console=plain *> "$env:TEMP\build.log"
   Get-Content "$env:TEMP\build.log" -Tail 80
   ```

**其他约束**

- `adb logcat` 用 `-d`（一次性 dump）或按标签过滤，避免 `Select-Object` 管道；需要持续观察时输出到文件再 `-Tail`。
- 不要把构建日志、反编译产物写入会被提交的路径；逆向产物统一放 `reverse_report/`（已被 `.gitignore` 忽略，见 1.5）。

---

## 5. 英文文案与专有名词（`values-en`）

界面英文文案集中在 `app/src/main/res/values-en/strings.xml`，与 `values/strings.xml` 同名键一一对应（主文件当前各 463 条；实验性功能的独立资源文件同样成对维护，如 `installer_experiment.xml` 各 34 条）。**专有名词一律使用官方英文名：不直译、不臆造**，中文侧继续按小米中文名书写；同一条文案里只允许一种写法。本节规则由 PR #40（HyperOS 4 英文界面文案修订）归纳。

### 5.1 官方英文名的取值来源（按顺序）

1. **设备自带资源最权威**：已知包名的英文值可直接从 `reverse_report/<包名>/*-resources.txt` 查（`resource 0x7f12xxxx string/xxx` 下面第一行 `()` 即默认英文值）。已核对过的例子：

   | 资源 | 英文值 | 对应中文 |
   |---|---|---|
   | `string/mi_smart_hub_entry_title` | `Xiaomi Smart Hub` | 融合设备中心 |
   | `string/state_screen_synergy` | `Using Home screen+` | 妙享桌面 |
   | `string/quick_settings_mishare_advert_all_close` | `Xiaomi Share is off` | 小米互传 |
   | 应用流转状态文案 | `Using app handoff` | 应用流转 |

2. **英文界面**：把系统语言切成 English，进对应页面截图核对（系统组件与小米应用的英文名都在这里）。
3. **官方资料**：小米官网 / 发布会英文页（用于系统尚未带英文资源的新功能）。
4. 查不到官方名时，**宁可用描述性英文**说明这个开关做什么，也不要新造一个专有名词。

### 5.2 已确认的写法对照

| 采用（官方英文） | 不要写成 | 说明 |
|---|---|---|
| `HyperOS`（`HyperOS 4`） | `Hyper OS` | 系统名 |
| `HyperRefine` | `Hyper Refine` | 本模块名，`app_name` 不翻译 |
| `Xiaomi Smart Hub` | `Fusion Device Center` | 融合设备中心 |
| `Home screen+` | `Mi Share Desktop` | 妙享桌面（跨设备桌面） |
| `app handoff` | `cross-device streaming` | 应用流转 |
| `Xiaomi Share` | `Mi Share` | 小米互传（旧名不再使用） |
| `Mi Browser` | `Xiaomi Browser` | 小米浏览器（官方英文名仍是 `Mi Browser`） |
| `Xiaomi Hyper XiaoAi` | `Xiao Ai` / `XiaoAI` | 超级小爱 |
| `App vault` | `Assistant` | 负一屏 |
| `Control center` | `control panel` | 控制中心 |
| `Charging boost` | `fast charge` | 快充加速；通知文案 `charging boost start / end reminders` |
| `Passkeys` / `Passwords` | 用 `Passkey` 当分区名 | 通行密钥；功能名 `Passkeys`，设置入口分区名 `Passwords` |
| `AOD` / `always-on display` | `AOD screen` | 息屏显示；标题里用 `AOD`，正文可用全称 |
| `notification shade` | `notification panel` | 下拉后的通知栏 |
| `split screen`（`side-by-side` / `top-and-bottom`） | `split window left/right` | 分屏与方向 |
| `Wi-Fi` / `WLAN` | `Wifi` / `wifi` | 无线网络；`WLAN / mobile data` 指 WLAN 与移动数据 |
| `Root` | `root`（指权限时） | 权限名，如 `Root access not granted.` |
| `System UI` | `SystemUI` / `System Ui` | 系统界面；界面文案统一写 `System UI`，进程与包名在代码里按 `com.android.systemui` 书写 |
| `DexKit` / `LSPosed` / `Miuix` / `libxposed` | 大小写随意写 | 框架与依赖名按官方大小写 |

### 5.3 写法规则

- **说明文字写成完整句子**：句首大写、句末句号、补足冠词。例如 `Root not granted, restart and other features unavailable` → `Root access not granted. App restart and other root-dependent features are unavailable.`；`failed, check permission and scope` → `Failed to clear the cache. Check permissions and scope.`
- **不要用 `app(s)` / `time(s)` / `%d apps authorized` 这类缩写或直译计数**，改成「标签: 数值」：`Apps in scope: %d`、`Crashes: %d`、`Apps with hooks disabled: %d · tap to manage`。
- **标题用名词短语，说明放副标题**：`titleRes` 写名词短语（`App appearance`），不要写成句子；描述性内容进 `summaryRes`。
- **开关文案描述「解除什么限制」，不用 `Allow …`**：`Allow lock screen notifications` → `Remove lock screen notification restrictions`；`Allow editing protected notifications` → `Allow disabling protected notifications`。
- **直译不通就写行为**：`Stepless split ratio` → `Freely adjust split-screen ratios`；`Hide overflow` → `Hide the ellipsis`；`Larger is stronger` → `Higher values increase the blur`。
- **同一概念固定用同一术语**：`appearance`（不用 `beautify`）、`split screen`、`notification shade`、`Control center`、`app handoff`。
- **占位符**：一条字符串里出现多个 `%` 时必须显式声明 `formatted="false"`（或改用 `%1$s` 位置参数），否则构建会报 `Multiple substitutions specified in non-positional format`。
- **转义**：`&` 写成 `&amp;`，`'` 写成 `\'`。

### 5.4 流程与自检

1. 改动中文键时**同时**改 `values-en` 里的同名键（键名与数量一一对应）；
2. 出现新专有名词时，先按 5.1 查到官方英文名、补进 5.2 表格，再写进文案；
3. 提交前构建一次，确认不再出现 `Multiple substitutions specified in non-positional format` 等资源告警；
4. 纯英文文案修订（如 PR #40）可以不单独写 `changelog.md`；若同时改了中文文案或功能行为，按常规写日志。

---

## 6. 隐私保护（公开仓库）

本仓库是公开仓库：**任何被 git 跟踪的内容都会进入提交历史**，推送后再删除也仍可从历史中检出。设备与个人信息必须在提交前清理，不能依赖事后修改。

### 6.1 禁止写入的内容

- 设备序列号（`adb devices` 输出的 serial）及其他设备唯一标识；
- 用户名、用户目录、绝对路径（如 `C:/Users/<user>`、`/home/<user>`、`D:/...`）；
- 手机号、邮箱、账号、IP、MAC 地址、Wi-Fi 名称等可关联到个人的信息；
- 通知内容、联系人、剪贴板、相册等抓取到的个人数据；
- 未脱敏的截图与完整 logcat。

> 技术说明可以保留设备型号与实测参数（如屏幕圆角、dpi），但不要与序列号等唯一标识同时出现；示例统一使用占位符：`<serial>`、`<user>`、`%LOCALAPPDATA%`、`~/...`。

### 6.2 本机配置的存放

- 设备序列号、SDK 路径等本机差异只放在**已被 git 忽略**的文件（如 `local.properties`）或环境变量中；
- 文档与示例命令统一使用占位符，例如 `adb -s <serial> install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`；
- 新增本地配置文件前，先确认 `.gitignore` 已覆盖，再写入内容。

### 6.3 日志、截图与逆向产物

- 抓取前说明用途并取得同意，只取定位问题所需的最小范围；
- 原始日志与逆向产物只放 `reverse_report/<包名>/`（已在 `.gitignore` 中忽略），永不提交、不 `git add -f`；
- 贴到 Issue / PR 前先脱敏：序列号、账号、通知内容、路径、IP 一律替换为占位符。

### 6.4 提交前自检

```powershell
# 在本地把占位符替换为实际值后执行；替换后的命令不要提交
git grep -n -i -E -e "<serial>" -e "<user>" -e "[A-Za-z]:\\Users\\" -e "[A-Za-z]:\\[A-Za-z0-9_.]"
```

- `git diff` 逐项确认文档、示例、脚本与测试数据没有带入本机路径或个人信息；
- 公开仓库中一旦误提交：立即删除，并评估是否需要改写历史；改写历史（force-push）会影响所有协作者与克隆，必须先取得同意。

---

## 7. 检查清单

- [ ] 涉及真机的 `adb` 操作均已获得用户明确授权，且在白名单内。
- [ ] 未执行任何未授权 / 修改设备 / 读取隐私的命令。
- [ ] 公开文件中没有设备序列号、用户名、用户目录、绝对路径、账号等本机信息；示例均使用占位符。
- [ ] 日志 / 截图已脱敏；逆向产物与报告只存放在 `reverse_report/`（`.gitignore` 已忽略，未被提交）。
- [ ] 复用的第三方代码已核对许可证并保留原始声明。
- [ ] 无许可证 / 非开源参考已致谢，且未复制其代码。
- [ ] `LicensePage.kt`、`README.md`、`docs/CUSTOMIZE.md`、`changelog.md` 已同步。
- [ ] 新增 / 修改的文案已在 `values-en` 补同名键，专有名词按 [第 5 节](#5-英文文案与专有名词values-en) 的对照表取官方英文名。
- [ ] 构建日志没有 `Multiple substitutions specified in non-positional format` 一类的资源告警。
- [ ] 构建通过并在 LSPosed 中验证 Hook 生效。
