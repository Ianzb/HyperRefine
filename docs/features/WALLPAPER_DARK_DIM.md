# 壁纸 · 桌面与锁屏分别压暗

深色模式下自动压暗壁纸，桌面与锁屏各有一个 0–100% 的压暗程度。入口：系统界面 → 壁纸。

## 配置

| 键 | 含义 | 默认 |
| --- | --- | --- |
| `wallpaper_dark_dim` | 总开关 | `false` |
| `wallpaper_dark_dim_amount` | 桌面压暗程度 | `30` |
| `wallpaper_dark_dim_lock_amount` | 锁屏压暗程度 | `30` |

旧版本只有 `wallpaper_dark_dim_amount`。升级时 `PrefsMigrations.migrateWallpaperDimLockAmount()` 会把当前桌面值补写到锁屏键，保持升级前后的观感一致；用户之后可以单独调整。

## 为什么需要 system_server hook

`android.app.WallpaperManager#setWallpaperDimAmount(float)` 最终进入
`WallpaperManagerService#setWallpaperDimAmountForUid`，该方法只计算一个值，并同时写入桌面与锁屏两个
`WallpaperData.mWallpaperDimAmount`，因此公开接口无法表达两个值。

模块在系统把最终值下发给壁纸引擎的入口做参数替换：

- 类：`com.android.server.wallpaper.WallpaperManagerService$DisplayConnector`
- 方法：`applyDimming(float)`
- 字段：`mWhich`（`WallpaperManager.FLAG_SYSTEM` / `FLAG_LOCK`）

`WallpaperDimLevelHook` 在 `applyDimming` 执行前，根据 `mWhich` 把参数替换为桌面值或锁屏值。只在以下条件同时满足时改写：

1. 总开关开启；
2. 当前配置为深色模式；
3. 传入值仍等于桌面值（避免覆盖系统的临时压暗、AOD 或其它调用方）。

系统仍负责压暗动画、临时压暗与浅色模式恢复。hook 常驻安装，不随开关卸载；安装模块或首次启用后需重启一次 `system_server` 才能把 hook 注入，此后开关和滑条修改会在下一次系统下发压暗时生效，无需再次重启。

## 息屏玻璃

AOD 时钟、锁屏时钟和锁屏通知的玻璃效果由 MIUI 自己的模糊管线绘制，取色发生在系统压暗壁纸之前，因此只压暗壁纸时玻璃仍然偏亮。模块统一修改 `android.view.View` 的玻璃混色入口，而不是逐个改时钟 / 通知：

- `addMiBackgroundBlendColor(int, int)`：在原有混色之后追加一层黑色，alpha 来自锁屏压暗值；
- `setMiBackgroundBlendColors(ArrayList)`：MIUI 重建混色列表时，先读回现有颜色，去掉模块上次追加的项，再追加当前锁屏压暗值。

这样时钟、通知以及后续新增的息屏玻璃只要走同一套 `View` 混色接口，都会跟随锁屏压暗值。触发条件与壁纸压暗一致：总开关开启、系统处于深色模式、且当前已锁屏；解锁后追加项会被移除，避免影响桌面。入口在「系统界面 → 息屏」页：另有「息屏玻璃跟随压暗」开关（默认开启），关闭后只压暗壁纸，玻璃保持原亮度。模块只改混色结果，不替换 MIUI 的模糊半径、材质或取色源。

## 文件

- `hook/src/main/java/cn/ianzb/hyperrefine/hook/systemui/wallpaper/WallpaperDimKeys.kt`
- `hook/src/main/java/cn/ianzb/hyperrefine/hook/systemui/wallpaper/WallpaperDarkDimHook.kt`（系统界面侧写入桌面值、监听深浅色与滑条）
- `hook/src/main/java/cn/ianzb/hyperrefine/hook/systemui/wallpaper/WallpaperDimBlurDim.kt`（控制中心 / 负一屏模糊背景补压暗层）
- `hook/src/main/java/cn/ianzb/hyperrefine/hook/systemui/wallpaper/LockGlassDimHook.kt`（锁屏 / AOD 玻璃混色跟随压暗）
- `hook/src/main/java/cn/ianzb/hyperrefine/hook/systemserver/WallpaperDimLevelHook.kt`
- `app/src/main/java/cn/ianzb/hyperrefine/ui/screen/features/FeaturesPage.kt`

## 已知限制

- `DisplayConnector.applyDimming` 内部还会把 `nonTemporaryDimAmount`（第二个参数）传给壁纸引擎；模块只替换第一个可见压暗值。系统临时压暗较大时仍以临时值为准，这是有意的，避免破坏 AOD / 转场。
- 若厂商修改 `DisplayConnector` 的类名、方法名或 `mWhich` 字段，hook 会跳过并在 LSPosed 日志中记录，不会崩溃；此时锁屏会退回使用桌面值。
- 个别机型把桌面与锁屏合并为同一个壁纸（`mWhich == 3`），系统只有一个壁纸图层，无法拆成两个值；此时同样退回桌面值。
- 锁屏玻璃只覆盖走 MIUI `View` 混色接口的场景；如果某个玻璃效果改用 `RenderEffect`、独立 Surface 或其它厂商私有管线，本 hook 不会介入。
- 锁屏状态优先来自 `KeyguardStateControllerImpl.notifyKeyguardState`；若厂商删除该方法，则退化为 `KeyguardManager.isKeyguardLocked`，仍能压暗，但解锁后的移除可能延迟到下一次玻璃混色刷新。
