# 二次开发指南

本文档说明以本模板为基础开发自己的 LSPosed 模块时，**需要修改的全部内容**：应用标识、图标、链接、模块元数据、Hook 代码、配置项与页面等。

> 约定：本文中的路径均相对于项目根目录。改完建议全局搜索 `cn.ianzb.miuixguitemplate`、`MiuixGuiTemplate`、`miuix_template` 确保没有遗漏。

---

## 0. 必改清单（Checklist）

| 类别 | 位置 | 说明                                                  |
|---|---|-----------------------------------------------------|
| 项目名 | `settings.gradle.kts` → `rootProject.name` | 工程名                                                 |
| 包名 / 应用 ID | `app/build.gradle.kts` → `namespace`、`applicationId` | 应用标识                                                |
| 包名（hook） | `hook/build.gradle.kts` → `namespace` | 与主包保持一致的前缀                                          |
| 源码包路径 | `app/src/main/java/...`、`hook/src/main/java/...` | 目录与 `package` 声明同步修改                                |
| 版本号 | `app/build.gradle.kts` → `versionCode`、`versionName` |                                                     |
| 应用名 | `res/values/strings.xml`、`res/values-en/strings.xml` → `app_name` |                                                     |
| 应用图标 | `res/drawable/ic_launcher_*.xml`、`res/mipmap-anydpi*/ic_launcher*.xml`、`colors.xml` → `ic_launcher_background` |                                                     |
| 关于页链接 | `strings.xml` → `about_source_code_summary`、`about_telegram_summary` | 展示与跳转均使用该值；`about_telegram` 的文案**须明确写「Telegram 群组」**（不要只写「反馈渠道 / 反馈方式」，否则用户不知道这是 TG 群组） |
| 版权 | `strings.xml` → `copyright` |                                                     |
| **Based on 标注** | `strings.xml` → `about_based_on` | 应用内「关于」页保留 `Based on MiuixGuiTemplate <版本号>` 并更新为所依据的脚手架版本（README 无需标注） |
| 参考与致谢 | 应用内 `LicensePage.kt` → `licenses_section_refs` | 保留对参考项目的致谢（可增不可删），文档不展开具体借鉴说明 |
| 开源协议 | 根目录 `LICENSE`、`README.md`「许可证」、`strings.xml` → `license_lgpl*` | **须保持 LGPL-3.0（或更弱兼容的 GPL-3.0）**，见第 11 节            |
| 许可证列表 | `ui/screen/about/LicensePage.kt` → `licenseSections` | 增删依赖库                                               |
| 模块元数据 | `hook/src/main/resources/META-INF/xposed/{module.prop,scope.list,java_init.list}` |                                                     |
| Hook 目标 | `hook/.../base/HookEntryRegistry.kt`、`BaseLoad` 子类 |                                                     |
| 配置项 / 页面 | `ui/screen/features/FeaturesPage.kt`（功能页）、`OptionRegistry` |                                                     |
| 导出文件名 | `ui/screen/settings/SettingsPage.kt` → `exportLauncher.launch(...)` |                                                     |
| 主题 / 颜色 | `res/values/themes.xml`、`res/values/colors.xml` |                                                     |
| README / 更新日志 | `README.md`、`changelog.md` |                                                     |

---

## 1. 应用标识（包名 / 应用名 / 版本）

### 1.1 Gradle

`settings.gradle.kts`：

```kotlin
rootProject.name = "YourModuleName"
```

`app/build.gradle.kts`：

```kotlin
android {
    namespace = "com.yourname.yourmodule"
    defaultConfig {
        applicationId = "com.yourname.yourmodule"
        versionCode = 1
        versionName = "1.0"
    }
}
```

`hook/build.gradle.kts`：

```kotlin
android {
    namespace = "com.yourname.yourmodule.hook"
}
```

### 1.2 源码包重命名

1. 将 `app/src/main/java/cn/ianzb/miuixguitemplate/` 重命名为 `com/yourname/yourmodule/`；
2. 将 `hook/src/main/java/cn/ianzb/miuixguitemplate/hook/` 重命名为 `com/yourname/yourmodule/hook/`；
3. 全局替换 `cn.ianzb.miuixguitemplate` → `com.yourname.yourmodule`（IDE 的 Rename Package 或全量搜索替换）。

> `AndroidManifest.xml` 中的 Activity 使用相对类名（如 `.TemplateApp`、`.MainActivity`），包名变更后无需手动改。

### 1.3 应用名

`app/src/main/res/values/strings.xml` 与 `values-en/strings.xml`：

```xml
<string name="app_name">YourModuleName</string>
```

---

## 2. 应用图标

模板的启动图标为**自适应图标**，需替换以下资源：

| 文件 | 说明 |
|---|---|
| `res/drawable/ic_launcher_background.xml` | 图标背景层 |
| `res/drawable/ic_launcher_foreground.xml` | 图标前景层 |
| `res/mipmap-anydpi/ic_launcher.xml`、`ic_launcher_round.xml` | 自适应图标描述 |
| `res/mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` | API 26+ 自适应图标描述 |
| `res/values/colors.xml` → `ic_launcher_background` | 背景色 |

**关于页图标无需单独维护**：`AboutPage.kt` 在运行时通过 `packageManager.getApplicationIcon(...)` 读取启动图标，会自动与桌面图标保持同步。

> 若使用 Android Studio 的 Image Asset 工具生成图标，会覆盖上述文件，无需手工编辑。

---

## 3. 关于页与链接

关于页（`ui/screen/about/AboutPage.kt`）的所有链接现在**均取自字符串资源**，只需修改字符串即可同时更新「显示文本」与「点击跳转」：

`res/values/strings.xml`：

```xml
<string name="about_source_code">项目地址</string>
<string name="about_source_code_summary">https://github.com/Ianzb/HyperRefine</string>
<string name="about_telegram">Telegram 群组</string>
<string name="about_telegram_summary">https://t.me/HyperRefine</string>
<string name="copyright">© 2026 Your Name.</string>
```

`res/values-en/strings.xml` 同步修改英文文案。

> **`about_telegram` 的显示文案须写成「Telegram 群组」（英文 `Telegram Group`）**，不要只写「反馈渠道 / 反馈方式」，否则用户看不出这是 Telegram 群组。`about_telegram_summary` 填群组链接（`https://t.me/...`）。

关于页的「GNU LGPL v3.0」跳转链接为协议官方文本地址；「参考项目」条目跳转第三方许可证页，具体参考与致谢清单在该页维护。关于页 Logo 下方的 `about_based_on` 即 **Based on 标注**，须保留并随脚手架版本更新（见第 11 节）。

---

## 4. 第三方许可证列表

`ui/screen/about/LicensePage.kt` 顶部的 `licenseSections` 定义了分组与库列表：

```kotlin
private val licenseSections: List<LicenseSection> = listOf(
    LicenseSection(
        titleRes = R.string.licenses_section_core,
        libraries = listOf(
            LibraryInfo("Kotlin", "2.4.20", "Apache-2.0", "https://kotlinlang.org/"),
        ),
    ),
    // ...
)
```

- 增删依赖库时同步维护此列表；
- 分组标题在 `strings.xml` 的 `licenses_section_*` 中定义；
- 库名 / 版本 / 许可证 / 官网为字面量（专有名词无需本地化）。

---

## 5. 模块元数据（LSPosed）

目录：`hook/src/main/resources/META-INF/xposed/`

### 5.1 `java_init.list`

```
com.yourname.yourmodule.hook.xposed.XposedEntry
```

> 仅在入口类所在包变更时修改（默认随包名替换自动更新）。

### 5.2 `module.prop`

```
minApiVersion=102
targetApiVersion=102
staticScope=false
```

> `minApiVersion` / `targetApiVersion` 按需调整；`staticScope=false` 表示支持运行时动态申请作用域（配合 `XposedService.requestScope`）。

### 5.3 `scope.list`

每行一个目标包名，例如：

```
system
com.yourname.targetapp
```

> 该文件声明模块默认作用域，也是「申请作用域」的默认目标来源之一。新增 Hook 目标时务必在此加入对应包名。
>
> 主页「作用域」二级页会读取作用域应用的图标 / 名称 / 版本，依赖 `AndroidManifest.xml` 中的 `android.permission.QUERY_ALL_PACKAGES` 权限（模板已声明）；如移除该权限，未对应用可见的包将只能显示包名。

---

## 6. Hook 代码

### 6.1 注册目标包

`hook/.../base/HookEntryRegistry.kt`：

```kotlin
private val loads: List<BaseLoad> = listOf(
    MyLoad(),   // 你的 Load
)
```

### 6.2 编写 Load 与 Hook

```kotlin
class MyLoad : BaseLoad() {
    override val targetPackages = listOf("com.yourname.targetapp")
    override fun onPackageLoaded(target: PackageTarget) {
        initHook(MyHook(), HookPrefs.getBoolean(MyHook.KEY, false))
    }
}

class MyHook : BaseHook() {
    override val key = KEY
    override fun init() {
        val clazz = Reflect.findClass("com.yourname.targetapp.Foo", target.classLoader)
        HookHelper.findAndHookMethodAfter(clazz, "bar") { /* ... */ }
    }
    companion object { const val KEY = "targetapp_bar" }
}
```

### 6.3 移除示例 Hook

删除 `hook/.../demo/DemoHook.kt`，并从 `HookEntryRegistry` 中移除 `DemoLoad()`。

### 6.4 配置分组名（保持两端一致）

- `hook/.../prefs/HookPrefs.kt` → `GROUP = "miuix_template_remote"`
- `app/.../prefs/PrefsStore.kt` → `REMOTE_GROUP = "miuix_template_remote"`

> 两个常量**必须一致**，否则 Hook 进程读不到 App 写入的配置。

### 6.5 DexKit 缓存目录（可选）

`hook/.../dexkit/DexKitCacheManager.kt` → `CACHE_DIR = "miuix_template"`。

> 该目录用于清空缓存时的路径拼接，改名后 App 端 `RootHelper.deleteDexKitCache(scope, DexKitCacheManager.CACHE_DIR)` 会自动使用新值。
> 清空缓存需要 Root，但**无需在文案中说明**——主页「模块状态」卡片已标注是否具备 Root。

### 6.6 兜底 / 安全模式（可选调整）

`hook/.../safemode/SafeModeManager.kt`：

| 常量 | 默认 | 说明 |
|---|---|---|
| `CRASH_WINDOW_MS` | `60_000` | 该窗口内再次启动视为一次崩溃 |
| `SURVIVE_MS` | `15_000` | 存活该时长后重置计数 |
| `DEFAULT_THRESHOLD` | `3` | 普通应用崩溃阈值 |
| `CRITICAL_THRESHOLD` | `2` | 关键应用崩溃阈值 |
| `CRITICAL_PACKAGES` | system / systemui / settings / home / securitycenter | 崩溃可能导致无法开机的关键应用 |

> `SafeModeManager.GROUP` 必须与 App 侧 `xposed/SafeModeReader.kt` 的 `GROUP` 一致；作用域页会展示并允许重置安全模式。

---

## 7. 配置项与页面

### 7.1 声明配置项

在 `OptionRegistry` 注册（示例见 `ui/screen/features/FeaturesPage.kt` 的 `featureSpecs()`）：

```kotlin
OptionSpec(
    key = "targetapp_bar",
    type = OptionType.SWITCH,
    titleRes = R.string.targetapp_bar,
    summaryRes = R.string.targetapp_bar_summary,
    defaultBoolean = false,
    targetPackages = listOf("com.yourname.targetapp"),
)
```

### 7.2 构建页面

使用通用页面组件（详见 [API 文档](API.md) 第 5 节）。**小标题用单语言**（只传 `titleRes`，不要传 `titleEn`）：

```kotlin
HookOptionsPage(
    title = stringResource(R.string.tab_features),
    sections = listOf(
        HookSection(R.string.section_switch, listOf(spec)),
    ),
    isBlurEnabled = isBlurEnabled,
    extraBottomPadding = extraBottomPadding,
)
```

子页面内的功能通过 `HookSubPage` 并入父页搜索（子页面自身无需再放搜索栏）；多级页面可继续嵌套 `subPages`，搜索可达并直接打开对应层级：

```kotlin
HookOptionsPage(
    title = stringResource(R.string.tab_features),
    sections = sections,
    subPages = listOf(
        HookSubPage(
            titleRes = R.string.my_subpage,
            specs = listOf(subSpec),
            onOpen = { context.startActivity(Intent(context, MySubPageActivity::class.java)) },
            // 更深一层子页面，递归并入搜索，命中直接打开该页面
            subPages = listOf(
                HookSubPage(
                    titleRes = R.string.my_deep_subpage,
                    specs = listOf(deepSpec),
                    onOpen = { context.startActivity(Intent(context, MyDeepSubPageActivity::class.java)) },
                ),
            ),
        ),
    ),
)
```

**入口卡片文案**：作为二级菜单入口的卡片（跳转二级页 / 弹出对话框）尽量不加小标题（`summaryRes`），大标题（`titleRes`）用总结性名词短语而非描述性句子，描述性说明放到二级页面内。详见 [接口文档](API.md) 5.6。

### 7.3 从功能页开始开发

模板的「功能」页 `ui/screen/features/FeaturesPage.kt`（子页 `FeatureSubPageActivity.kt`、配置项 `featureSpecs()`）已按真实模块的形态组织：**页面名以功能命名、小标题用单语言**。二次开发时：

- 把 `FeaturesPage.kt` 的示例分区替换为你的真实功能，示例配置项 `featureSpecs()` 一并替换；
- 新增子页面时继承 `BaseSubPageActivity` 并在 `AndroidManifest.xml` 注册；把子页配置项通过 `HookSubPage` 传入父页 `subPages`，即可被搜索直达；多级页面继续在 `HookSubPage.subPages` 中嵌套登记（见 7.2）；
- Tab 标签已为「功能」，无需再改；若删除该页，请同步更新 `MainActivity.kt` 的标签页列表与 `TemplateApp.onCreate()` 的注册。

### 7.4 导出文件名

`ui/screen/settings/SettingsPage.kt`：

```kotlin
exportLauncher.launch("YourModuleName_settings.json")
```

### 7.5 包名列表与重启应用

声明 `OptionType.PACKAGE_LIST` 选项后，用户在二级页面输入包名，页面右上角即出现「重启应用」按钮，点击后勾选应用并批量重启：

```kotlin
OptionSpec(
    key = "quick_action_packages",
    type = OptionType.PACKAGE_LIST,
    titleRes = R.string.quick_action_packages,
    summaryRes = R.string.quick_action_packages_summary,
    defaultString = "",
)
```

也可直接注入自定义应用，无需用户输入：

```kotlin
HookOptionsPage(
    title = stringResource(R.string.tab_features),
    sections = sections,
    customActionPackages = listOf("com.a", "com.b"),
)
```

> 重启需要 Root（主页「模块状态」已标注）。
> `com.android.systemui` 走 `AppRestarter.restartSystemUi()`（结束进程后由系统自动拉起），**不触发系统重启**；仅 `system` / `android` / `system_server` 才执行 `reboot`。

### 7.6 显隐动画与顶栏重启应用

- **显隐动画**：所有组件出现 / 隐藏统一使用 Miuix 标准弹簧 `MiuixExpandSpec`（`ui/util/MiuixAnimations.kt`），即 `expandVertically(animationSpec = MiuixExpandSpec)` / `shrinkVertically(animationSpec = MiuixExpandSpec)`，禁止使用默认或自定义时长。详见 [接口文档](API.md) 5.6。
- **顶栏重启应用**：批量「重启」入口统一为 `Refresh`（重启）图标 → `QuickActionDialog`。`HookOptionsPage` 自动生成；二级页面通过 `BaseSubPageActivity.topBarActions` 注入 `QuickActionsAction(packages)`，不要自行实现按钮样式。对话框固定标题「重启应用」且**无小标题**，应用列表用 `Card` 圆角容器，每行 `CheckboxPreference`（`checkboxLocation = End`，默认全选），底部左「全选 / 全不选」（全选时显示「全不选」）+ 右「重启」（无勾选禁用），重启完成后弹出成功提示（`quick_action_restart_success`）。完整规范见 [接口文档](API.md) 5.6。

---

## 8. 主题与颜色

| 文件 | 说明 |
|---|---|
| `res/values/themes.xml` | 应用主题（`Theme.MiuixGuiTemplate`），并设置启动窗口背景 |
| `res/values-night/themes.xml` | 深色模式主题（深色窗口背景），避免启动白屏闪烁 |
| `res/values/colors.xml` → `window_background` | 浅色启动窗口背景（默认 `#FFF7F7F7`） |
| `res/values-night/colors.xml` → `window_background` | 深色启动窗口背景（默认 `#FF000000`，与 Miuix 深色 surface 一致） |
| `res/values/colors.xml` | 基础颜色与 `ic_launcher_background` |
| `ui/theme/Theme.kt` | `AppTheme`（Miuix 主题、深浅色、系统栏图标），一般无需修改 |

> 若要改主题名，请同步替换 `AndroidManifest.xml` 与 `themes.xml` 中的 `Theme.MiuixGuiTemplate`。
>
> `window_background` 必须与页面实际背景（Miuix 主题的 `surface`：浅色 `0xFFF7F7F7`、深色 `0xFF000000`）一致，否则冷启动瞬间会闪现底色。`MainActivity` 还会按应用内主题模式（含手动强制深/浅色）动态覆盖窗口背景，覆盖逻辑见 `ui/util/WindowBackground.kt`。

---

## 9. 其他

| 项目 | 位置 | 说明 |
|---|---|---|
| 应用类 | `TemplateApp.kt` | 初始化配置、服务绑定；改包名后自动生效 |
| 检查更新 | `SettingsPage.kt` / `MainActivity.kt` | 当前为占位（点击仅提示），接入真实更新逻辑时替换 |
| 清空 DexKit 缓存 | `SettingsPage.kt` + `xposed/RootHelper.kt` | 需要 Root，删除作用域应用的 `cache/<CACHE_DIR>` |
| README | `README.md` | 项目简介、链接、构建说明 |
| 发布与 CI | `.github/workflows/` + `app/build.gradle.kts` | CI 构建 Debug 产物；Release 走签名发布（`release.keystore`、环境变量 / GitHub Secrets 配置见 [README · 发布与 CI](../README.md#发布与-ci)） |
| 更新日志 | `changelog.md` | 按版本记录变更 |
| 接口文档 | `docs/API.md` | 全部对外接口与布局规范 |
| 模块开发工作流 | `docs/WORKFLOW.md` | Hook 需求实现、真机 `adb` 扫描授权与第三方复用合规 |

---

## 10. 修改后自检

1. 全局搜索 `cn.ianzb.miuixguitemplate`、`MiuixGuiTemplate`、`miuix_template`、`your-name`、`your-channel`，确认无残留。
2. `HookPrefs.GROUP` 与 `PrefsStore.REMOTE_GROUP` 一致。
3. `scope.list` 已包含所有 `BaseLoad.targetPackages`。
4. `META-INF/xposed/java_init.list` 指向正确的入口类。
5. 运行 `./gradlew :hook:compileDebugKotlin :app:assembleDebug` 构建通过。
6. 安装到设备，确认模块在 LSPosed 中被识别、作用域正确、Hook 生效。
7. **完成第 11 节「开源协议与致谢」核对表**（协议、致谢、Based on 标注，一次核对到位）。

---

## 11. 开源协议与致谢（必读）

本模板同时包含 LGPL-3.0 与 Apache-2.0 许可的代码（自 [miuix](https://github.com/compose-miuix-ui/miuix) 等引入的文件保留其原始版权与 SPDX 声明），并引用了 LGPL/Apache 双许可的 [DexKit](https://github.com/LuckyPray/DexKit)。按 LGPL-3.0 的传染性要求，**本仓库及其衍生作品须以 LGPL-3.0（或 GPL-3.0）整体授权公开**。

### 11.1 参考与致谢

本模板的定位是**个人 Android 模块项目的脚手架模板**。具体参考与致谢清单统一维护在应用内「第三方许可证与致谢」页面（`LicensePage.kt` → `licenses_section_refs` 分组），衍生项目**可增不可删**。

> 文档不展开对具体模块的借鉴说明；复用任何第三方代码前须先核对许可证，无许可证 / 非开源项目只能借鉴思路并致谢，完整流程见 [模块开发工作流](WORKFLOW.md)。

落点：应用内「第三方许可证与致谢」页面（`LicensePage.kt` → `licenses_section_refs` 分组，关于页不再单列「参考项目」入口，统一由此页承载）。

### 11.2 Based on 约定

衍生项目**只需**在应用内「关于」页保留形如 `Based on MiuixGuiTemplate <版本号>` 的文本（示例：`Based on MiuixGuiTemplate 0.3.1`），并把版本号更新为**所依据的脚手架版本**；**无需**在各自的 `README.md` 中强调或标注。

| 落点 | 文件 |
|---|---|
| 应用内「关于」页 Logo 下方 | `strings.xml` → `about_based_on`（两套语言均改） |

用途：同步脚手架的修复与改进时，以该版本号判断差异范围。当前已知衍生项目：**HyperNavBar**（`Based on MiuixGuiTemplate 0.3.0`）。

### 11.3 一次性核对清单

| # | 事项 | 判定标准 |
|---|---|---|
| 1 | `LICENSE` 为 LGPL-3.0 全文 | 根目录存在且未删改条款 |
| 2 | README「许可证」章节声明 LGPL-3.0 并保留第三方许可说明 | 不得改回 Apache-2.0 |
| 3 | 关于页协议条目指向 LGPL-3.0 | `license_lgpl*` + `openUri` 指向 LGPL-3.0 文本 |
| 4 | 保留参考与致谢 | 应用内 `LicensePage.kt` `licenses_section_refs` 分组保留致谢；文档仅保留指向该页的说明 |
| 5 | 保留并更新 Based on 标注 | 应用内「关于」页（`about_based_on`），版本号 = 所依据脚手架版本；README 无需标注 |
| 6 | 第三方许可证页完整 | `licenseSections` 覆盖实际依赖 |
| 7 | 引入的第三方源文件保留原始版权 / SPDX 声明 | 如自 miuix 复制的 `Apache-2.0` 文件头 |
| 8 | 修改过的第三方源文件标注改动 | 文件头或相邻注释注明修改点 |
