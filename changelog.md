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
  - 三处独立配置：开关、字号、字重、字体颜色实时跟随图标（喇叭图标蓝色 / 太阳图标金色，关闭后使用默认字体颜色）
- 作用域：`com.android.systemui`（系统界面）、`com.miui.home`（系统桌面占位 `HomeLoad`）；不再申请 `system`（系统本体）作用域

### 移除

- 脚手架示例（`DemoHook` / 示例标签页 / 示例配置项）及示例作用域 `com.android.settings`
