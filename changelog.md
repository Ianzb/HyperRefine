# 更新日志

## 下个版本

### 新增

- **天气高级外观解锁**（目标 `com.miui.weather2`，原生 Hook）：新版天气为 Flutter + Rust 应用，Android 侧无 dex，逻辑在 Dart AOT（`libapp.so`）与渲染引擎 `libhyper_opengl.so`（MajesticGL）。开启后：
    - **雨雪特效**：引擎 `MajesticGLRenderer::onDrawFrame` 的雨雪物理开关为 `isHighDeviceLevelOS1() && !isPadDevice()`，平板会直接跳过；通过 hook `DeviceUtils::isPadDevice` / `isInPadMode` 置否，放行平板的雨雪粒子物理。
    - **平板降级修复**：新增 AOT 机器码补丁引擎（`dl_iterate_phdr` 定位目标 `.so` → 指令签名扫描 → `mprotect` 改写 → 刷新 ICache），对天气业务库 `libweather_app.so` 的 `effective_tablet` 判定打补丁，移除 `PassBlurWindow`（渐进模糊）在平板上的降级分支。
    - 入口：功能页「实验性功能」→「天气高级外观」（新增「实验性功能」二级页）。

### 优化

- **安装包体积大幅精简**：Release 开启 R8 代码与资源压缩、Debug/Release 的 dex 采用传统压缩打包；测试包由 43.7 MB 降至约 16.7 MB，正式包由 31.9 MB 降至约 2.5 MB。压缩时为 hook 侧保留了必要的反混淆规则，功能不受影响。

## 1.6.0

> 发布于 2026-10-04

### 新增

- **多应用音量 · 自动显示面板**：新增「自动显示面板」开关（默认关闭）。开启后打开侧边音量条时会自动展开多应用音量面板；入口按钮的点击开关逻辑不变。
- **妙享桌面增强**（设备互联 · 目标 `com.xiaomi.mirror`，移植自「妙享桌面增强」酷安 @ayyya）：
    - **自由浮窗**：接管投屏窗口，可拖动、四角缩放，顶部拖动条**双击最小化**，下拉通知栏时可收起为可拖动气泡，点击气泡恢复，并记住位置与尺寸（仅平板 / 折叠屏接收端）。
    - **浮窗外观**：顶部拖动条改为白色；「浮窗圆角」（0–60 dp，默认 9 与原生一致）统一作用于实际画面（`SinkView.setRoundCorner`）、`SinkWindow` / `container`、各层遮罩（`surfaceview_mask` / `MirrorMaskView` / 加载层）与系统阴影框（`SurfaceControl#setMiShadow`），并在布局后多次重刷以防被原生覆盖。
    - **隐藏左侧竖条**：隐藏浮窗左侧的侧边关闭条（`pole_view_root`）。
    - **下拉通知栏时最小化**：检测到通知中心或控制中心展开时收起投屏浮窗。
    - **投屏刷新率请求**：向发送端 / 接收端协商并请求指定刷新率（60 / 90 / 120 Hz），覆盖解码 Surface、编码器 `MediaFormat`、窗口 `preferredRefreshRate` 与原生帧率协商项。
- **顶栏渐变**（移植自 HyperBackground，MIT）：
    - 把 MIUIX 应用顶栏的原生遮罩替换为「顶部模糊、向下渐变透明」的模糊层（默认关闭），跟随原生遮罩动画淡入淡出；提供模糊强度与不透明度调节。
    - 参考 HyperBackground 作用域，覆盖设置 / 短信 / 联系人 / 电话 / 时钟 / 文件管理 / 下载 / 主题 / 更新 / 笔记 / 计算器 / 账号 / 安全服务等 20+ 系统应用（仅对确实存在 MIUIX 顶栏类的进程安装 hook）。
    - 关闭与顶栏模糊重复的自带遮罩：MIUIX `NestedHeaderLayout` 的滚动渐变层，以及笔记「筛选 / 分类标签」栏（`OverlayMaskFrameLayout`）的叠加遮罩。

### 修复

- **隐藏省略号后设备未平均排列**：官方卡片用 `FlexboxLayoutManager`，`justifyContent` 按 `deviceItems.size()`（含末尾省略号）决定；省略号被置 `GONE` 后仍会被 flexbox 当作一个占位项，导致平均排列偏移。现直接把末尾省略号从设备列表中移除并通知适配器，再按真实设备数重设 `justifyContent`：4 个及以下（填满一行以内，含 3 个）平均排列，超过一行靠左，flexbox 仅按真实设备数排布。

## 1.5.0

> 发布于 2026-10-03

### 新增

- **圆角调整 · 组件细化**：
    - **`一级磁贴` 拆分为 `横向磁贴（WLAN / 数据）` 与 `小磁贴`（下方 1×1 组件）**，二者分别独立设置（各自的端点、背景重建、`getCornerRadius` 端点与收回动画假卡片均按类型处理）。
    - **重新加入 `控制中心二级音量条`**：按控制中心二级音量面板（`VolumePanelViewController.isControlCenterPanel`）归属，覆盖静态布局与面板展开动画。
- **圆角调整总开关关闭时，仍可单独启用各组件**：单项自定义优先生效（与总开关无关）；未开启自定义的组件保持系统默认、不被改动。挂载条件改为「总开关开启 或 任一单项自定义开启」。
- **多应用音量面板**：入口改为由系统界面自行渲染面板（不再使用「音质音效」自带面板），采用官方 **原生竖向列**（反射 `com.android.systemui.miui.volume.VolumeColumn`）：
    - 面板背景复用官方展开态材质（`OfficialExpandedMaterial`：玻璃 / 模糊 / 静态）；音量条 **填充与尺寸完全使用官方音量条配置**；「柔光玻璃」开启时清除音量条各层深色兜底背景、并把滑条进度层置透明（与侧边音量列处理一致），使音量条透出面板材质、不再深色压暗；应用图标叠在竖条底部，百分比样式与上下位置复用「侧边音量条百分比」。
    - 面板显示在侧边音量条 **左侧**（不遮挡、不隐藏侧边条）；原地透明度淡入淡出；再次点击入口关闭；关闭侧边音量条时联动关闭、不残留；去除音量条窗口压暗。
    - 接入本模块配置：面板圆角（`APP_VOLUME_PANEL`）、音量条圆角（`APP_VOLUME_BAR`）、高度。
    - 数值经广播转发到「音质音效」进程，用 `AudioManager.setPlayerVolume` 生效并写回其镜像；活跃应用取 `getActivePlaybackConfigurations()`，当前值读「音质音效」只读 Provider、回退隐藏 API `getPlayerVolume(String)`。
- **融合设备中心 · 缩小设备点击范围**（适配 HyperOS 4）：`DeviceItemViewHolder` 的根视图 `CustomRootView` 在 `wrap_content` 下被官方强制为整格宽度（可用宽度 1/4），而可见按钮只是其中居中的方形；官方把点击监听挂在整格根视图上，点击按钮四周的空白也会打开设备。现把设备点击判定收缩到可见的方形按钮，根视图的点击改为触发列表点击（打开融合设备中心）；根视图保持可点击以让官方按压动画正常收放（否则只收到按下事件会卡在缩小状态）。
- **融合设备中心 · 流转卡片玻璃**（适配 HyperOS 4，目标 `com.milink.service`）：点击融合设备中心里的设备后弹出的流转卡片（窗口 `MLCard`）在「设备不可用」时使用 `window_card_pin_device_offline` 布局（根 `ml_pin_offline_root`，纯色底 `circulate_card_bg`），该内容视图自身没有玻璃层、只剩下窗口模糊。开启后调用应用自身的 `MaterialUtils.a(view)` 为其套上系统材质玻璃（`setMiGlass` / 经典 blend），与可用设备的卡片保持一致。入口：功能页「外观 → 融合设备中心 → 流转卡片玻璃」。

### 变更

- **多应用音量 · 高度自动**：新增「高度自动」开关（默认开启，与「高度」互斥）；开启后自动把面板内的音量条与侧边音量条的竖直中心对齐，无需手动调「高度」；关闭后才使用「高度」百分比。
- **多应用音量 · 隐藏面板背景**：新增开关（默认关闭），开启后不套面板的玻璃 / 背景，只保留音量条。
- **多应用音量圆角默认值**：未自定义（总开关关闭）时默认音量条 20dp / 面板 30dp（1.3.0 的默认值）；单项自定义 **开启后**默认 35dp。
- **`亮度二级条` 默认不再开启自定义**（开启自定义后的默认值仍为 40dp）。
- **文案与顺序统一**：
    - `音量列` → `音量条`、`滑块` → `条`（`一级亮度 / 音量条`、`二级亮度条`、`静音 / 勿扰定时条`）。
    - 一级 / 二级字样统一前置：`二级亮度条`、`控制中心二级音量条`、`侧边一级音量条`、`侧边二级音量条`、`二级亮度背景`、`二级播放器背景`、`二级 WLAN / 数据背景` 等。
    - 组件顺序统一为：横向磁贴 → 播放器卡片 → 一级亮度 / 音量条 → 二级亮度条 → 控制中心二级音量条 → 侧边一级音量条 → 侧边二级音量条 → 融合设备中心 → 小磁贴 → 静音 / 勿扰按钮 → 静音 / 勿扰定时条 → 多应用音量内部音量条。
- **百分比「显示位置」**：修正为 100% 紧贴上边缘、0% 紧贴下边缘（此前两端会因文本自身布局内边距留出边距）；默认值由 100 调整为 90；存量用户仍为旧默认值 100 的项自动迁移为 90。
- **功能一级页「重启应用」**：重启范围加入「音质音效」（`com.miui.misound`）。
- **隐藏省略号后补齐第 8 个设备**：官方数据侧 (`DeviceCenterController`) 默认最多只向卡片推送 7 个设备（原为给省略号留第 8 格）；开启「隐藏省略号」后现放宽到 8 个，两行排满时不再空一格。
- **开源协议变更为 AGPL-3.0**：由 LGPL-3.0 调整为 AGPL-3.0，以兼容 GPL-3.0 参考项目（AGPL-3.0 §13）；应用内「第三方许可证与致谢」新增 SoundMan（GPL-3.0，系统界面多应用音量面板参考）。

### 修复

- **控制中心一级亮度 / 音量条白色填充圆角**：恢复官方微小顶角、底角贴合轨道；此前因官方在构造函数中重设 outline provider，遮罩使用了模块修改前的原生半径导致顶角/底角错误，现固定遮罩半径并在构造后重套。
- **侧边音量范围**：此前 `侧边一级音量条` 会同时改到二级与其它面板，现按音量列真实 `isExpanded()` 分别生效（一级=收起、二级=展开）。
- **控制中心二级音量面板范围**：新增独立开关后不再误用侧边设置。
- **长按打开侧边二级面板**：已展开时长按会再次触发开关式回调导致收起，现仅在未展开时触发展开。
- **多应用音量面板「避让」消失动画**：侧边音量二级面板展开时，此前会立即释放音量条（瞬间消失）再淡出背景。现改为保留音量条并整体「平移 + 淡化」避让消失（朝远离侧边音量条一侧滑出，250ms，参照入口按钮），结束后再释放资源；普通关闭（点击入口 / 空白）不受影响，仍为原地淡出。
- **多应用音量入口出现动画**：追加的入口按钮此前与「勿扰」按钮延迟相同（官方 `createRingerButtonArgs` 只区分 index 0 与其余），现对 index≥2 继续递增延迟，保持「自上而下递增」的出现节奏；同时多应用音量条到顶 / 到底的边缘动画不再连带入口（勿扰）按钮一起平移缩放。
- **控制中心音量百分比展开动画**：打开二级音量菜单时，百分比由一级 `top_text` 变形成二级音量列的 `superVolume`，而系统动画只按 `superVolume` 的布局坐标插值（不含我们给 `top_text` 加的 `translationY`），导致动画中竖直高度跳变。现改为在二级音量面板的逐帧回调（`VolumePanelAnimator.frameCallback`）里，对二级列 `superVolume` 套用与侧边音量条百分比相同的「父容器高度 - 文本高度」算法重算位置，动画中连续跟随配置高度。
- **多应用音量「高度自动」误跟随边界动画**：此前每帧用屏幕坐标对齐面板与侧边音量条，音量条拉到端点时的边界弹性动画（整列平移 / 缩放）会带着面板一起移动。现改用布局坐标（逐级累加 `getTop()`，不含平移 / 缩放）计算，忽略滑条 / 音量列自身的边界动画，只跟随面板整体的真实位移。

## 1.4.0

> 发布于 2026-10-02

### 新增

- **控制中心「圆角调整」**（适配 HyperOS 4，目标 `com.android.systemui` 控制中心插件与 `com.miui.misound`）：统一调整控制中心一级磁贴、播放器、一级滑块、亮度二级滑块、控制中心 / 侧边音量列、静音 / 勿扰按钮、定时滑块、融合设备中心入口、多应用音量面板等 **组件**与 **模糊背景**的圆角。
    - **统一 + 单项自定义**：一个总开关；「组件圆角」（默认 35dp）与「背景圆角」（默认 40dp）统一控制，各子项可单独开启自定义值。
    - **组件 / 背景分离**：仅模糊区域背景走「背景圆角」，其余组件走「组件圆角」；控制中心音量与侧边音量、以及各自的二级背景均可 **分别设置**。
    - 亮度二级滑块默认开启自定义为 40dp。
    - 材质 / 玻璃模式下同步玻璃 SDF 轮廓（含各二级面板动画、亮度二级展开动画、侧边音量展开背景），避免描边错位。
    - 入口：功能页「系统界面 → 外观 → 美化 → 圆角调整」。
- **百分比数值「高度」**：三处百分比（控制中心亮度条 / 音量条 / 侧边音量条）各自独立设置上下高度， **数值越大越靠上**（0% 最低、100% 最高、默认 100%）；多应用音量面板内的百分比跟随侧边音量设置。
- **多应用音量「隐藏背景模糊边框」**（默认关闭）：隐藏面板的背景模糊边框，仅保留音量条。
- **依赖前置规范**：需要前置选项解锁的功能统一为 **始终显示 + 禁用灰显**，不再隐藏。已写入 `docs/API.md` 5.7、`docs/CUSTOMIZE.md` 7.1 / 7.6，并同步脚手架 **MiuixGuiTemplate**（提交 `a70c845`）。

### 变更

- **系统界面功能页重排**：「系统界面」改为单一入口「外观」；「外观」下分「美化」（柔光玻璃、圆角调整）与「组件」（融合设备中心、亮度条百分比、音量条百分比、侧边音量条百分比、多应用音量）。移除原「控制中心」页与「显示」页。
- **多应用音量独立成页**（「外观 → 组件 → 多应用音量」）：面板圆角改由「圆角调整」统一控制，删除本功能自带的圆角设置。

### 修复

- **亮度二级展开动画玻璃描边错位**：动画中亮度条玻璃 SDF 仍按原始半径渲染导致描边错位，改为动画每帧同步玻璃轮廓。
- **侧边音量二级背景圆角**：修正展开背景圆角来源（`MiuiVolumeDialogRes.getBgRadius`，此前错误回落原生值）。
- **百分比高度首次打开不生效**：视图尚未完成布局时逐帧重试，首次打开即套用，无需先改动数值。

## 1.3.0

> 发布于 2026-10-01

### 新增

- **多应用音量**（目标 `com.android.systemui` 与 `com.miui.misound`；适配 HyperOS 4）：在侧边音量条底部（勿扰按钮之后）加入一个与官方静音 / 勿扰按钮 **完全同款**的玻璃入口按钮，点击打开系统原生「多应用音量」二级面板。
    - **入口复用官方实现**：官方 `miui_ringer_mode_layout` 布局 + `MiuiRingerModeLayout.RingerButtonHelper`（背景 / 模糊 / 图标 / 展开尺寸），并接入官方 `VolumeShowHideAnimator` 的按钮动画数组，因此拥有与原生按钮 **一致的自上而下递增延迟出现动画**、以及「到达最大 / 最小继续调整」时的 **边界平移 + 缩放动画**，并与两个按钮 **等距**。
    - **面板复用 MiSound 原生页面与组件**：每个正在播放的应用一根竖向音量条 + 百分比；无正在播放应用时在模糊区域内部居中显示占位文本；以 **原地渐显 / 渐隐**出现与消失。
    - **位置 / 外观**：默认 **靠右**（右边距 100dp），可切换为居中；支持 **高度百分比**（`0%` 最低、`100%` 最高、默认 `50%` 居中，整数、带 `%` 单位）。
    - **入口显隐**：常显，或仅在检测到媒体播放时显示；可隐藏系统左侧的蓝色悬浮球。
    - **实时性**：面板打开期间自动刷新正在播放的应用列表，并在系统媒体音量变化时实时刷新主媒体列。
    - 配置项位于「功能 → 侧边音量条 → 多应用音量」。
- **控制中心「柔光玻璃」新增「第三方主题下强制启用材质」开关**（`cc_glass_theme_material`，默认关闭）：使用第三方主题时仍按默认主题启用系统高级材质 / 柔光玻璃，保证一级磁贴与各二级面板的玻璃效果；入口：功能页「控制中心 → 柔光玻璃」。

### 变更

- **控制中心音量百分比**：深浅色切换后重新套用百分比样式（系统在配置变更时会用 `setTextAppearance` 重置 `top_text` 的字号 / 字重 / 颜色）。
- **柔光玻璃 · 详情面板**：WLAN / 移动数据 / 蓝牙详情的顶部「已连接设备卡片」不再套用柔光玻璃，保持系统默认背景（此前会误变成白色玻璃 + 描边）。
- **安全模式加入白名单**：自动安全模式仅对 **系统界面 / 桌面 / 系统进程**（`com.android.systemui` / `com.miui.home` / `android` / `system`）生效，其余进程不再计数与触发（它们本就会自行重启）。该改动已同步至脚手架 **MiuixGuiTemplate 0.5.2**。
- 参考项目登记由 `AppVolumeBarHook` 改为 [HyperVolume](https://github.com/Mo-SeTian/HyperVolume)。
- 文档：强调滑块 `sliderValueLabelRes`（数值 **左侧**标签）与 `sliderUnitRes`（数值 **右侧**单位）的写法差异——不设置 `sliderValueLabelRes` 时标题会显示在滑动组件外面（已同步 template 文档）。

## 1.2.1

> 发布于 2026-09-30

### 修复

- **调整音量卡顿**（控制中心音量 / 侧边音量）：柔光玻璃挂在 `VolumePanelViewController.updateVolumeColumnSliderH` 这一高频回调上，每次都对全部音量列逐次反射重套模糊玻璃（`setMiViewBlurModeCompat` + `setMiBackgroundStyle`）并重走视图树，音量按键 / 拖动时重复执行数十次导致明显卡顿。改为以「面板重新展示 / 初始化」代次 + 展开态组成的状态签名缓存每个视图最近一次的套用结果，仅在签名变化（重新展示、展开态切换）时重套，其余调用直接跳过；并缓存视图 id 资源名，消除高频遍历中 `Resources.getResourceEntryName` 的开销。控制中心二级音量面板与侧边音量共用该类（`VolumePanelDelegate` 内直接实例化 `VolumePanelViewController`），同时受益

## 1.2.0

> 发布于 2026-09-30

### 新增

- **控制中心「柔光玻璃」**（适配 HyperOS 4，目标 `com.android.systemui` 控制中心插件）：把控制中心二级面板的按钮与卡片接入系统同款柔光玻璃，统一由 **一个总开关**（默认关闭）控制，入口：功能页「控制中心 → 柔光玻璃」。覆盖：
    - **亮度二级**：大亮度条轨道 + 三个圆形按钮（开启态=系统白色遮罩，关闭态=默认玻璃；切换 / 深浅色变化后自动重套）
    - **WLAN / 移动数据 / 蓝牙详情**：顶部已连接卡片（激活玻璃，白色+描边）与下方列表组（系统 blend 玻璃，按组内位置共享一张圆角卡片），以及「更多设置」按钮
    - **控制中心音量 / 侧边音量**：音量条、静音 / 勿扰圆按钮（去深色底、保持圆形）、定时静音 / 勿扰滑块
    - **播放器**：设备卡片
    - 说明：全部反射调用系统自身接口（`MaterialBackgroundExt` / `MiBackgroundStyle` / `MiBlurCompat` / `QSTileItemIconView` 等），不复制任何系统代码；仅在总开关开启时挂载 Hook，避免空转

## 1.1.0

> 发布于 2026-09-29

### 新增

- **横屏融合设备中心右置**（适配 HyperOS 4，仅手机；目标 `com.android.systemui` + 控制中心插件类）：手机横屏时，控制中心按 `MainPanelContent.getRightOrLeft()` 把组件分入左右两列，融合设备中心入口（`DeviceCenterEntryController`）默认恒为左列；开启后覆盖其 `getRightOrLeft()` 返回 `true`，把融合设备中心移到右列。竖向布局不查询该方法，天然只在横屏生效。入口：功能页「控制中心 → 融合设备中心 → 横屏融合设备中心右置」
- **统一的「设备独占」API**：`OptionSpec.deviceScope: Set<DeviceType>?` + `rememberOptionEnabled(spec)`（依赖项 ∧ 设备白名单）。仅某类设备可用的功能在其它设备形态上 **禁用灰显、不隐藏**；读取 `ConfigState`，切换「设置 → 当前设备类型」后实时刷新；所有 Hook 卡片统一改用 `rememberOptionEnabled` 作为 `enabled`
- **「设备互联」页面**：功能页新增二级页，集中三项目标 `com.milink.service` / `com.xiaomi.mirror` 的功能（新增精准作用域与 `MiLinkLoad` / `XiaomiMirrorLoad`）：
    - **解锁跨设备通知流转**（平板）：`com.milink.service:ui` 本机设备发现纠正 + `com.milink.crossdeviceservice` 通知点击复用原生 `PIN_APP` 流转并吞掉重复被动串流命令
    - **允许平板竖屏流转应用**（平板）：纠正 reason-9 首包横竖尺寸倒置，并放开目标 sink Activity 的方向策略
    - **MiLink Multi-Channel**：AndroidPad 共享通道上限 1 → 2，并仅在原生返回 `HostNotBound(215)` 时补做官方 Host 绑定
- **Hook 生效状态（广播回报）**：`BaseLoad` 完成注册后，目标进程合并已安装配置键 **定向广播**回报；App 侧经发送者 UID / 包名 / 版本校验后按版本 + 开机号作用域持久化，`OptionSpec.showStatus` 在副标题显示「已生效 / 未生效」。已适配全部 Hook 功能（百分比显示、融合设备中心、快充通知、设备互联）；替代此前因 hook 侧只读而失效的状态提示

### 变更

- **移除 Hook 状态提示（标题染色）功能**：libxposed 远程文件为「App 写、Hook 读」，Hook 侧 `XposedInterface.openRemoteFile` 在 Vector 等新框架上明确为 **只读**，原 `HookStatusWriter` 从 Hook 侧写入必然失败，状态始终为空、标题从不染色，故整体移除：删除 `HookStatusWriter` / `HookStatusReader` / `HookStatus` 枚举，移除 `OptionSpec.hookId` / `statusId` / `demoStatus`、`rememberHookStatus` / `HookStatusTitleColor`、各卡片 `titleColor` 染色、`hook_status_*` 字符串及 `BaseLoad` / `NativeHookHelper` / `XposedEntry` 中的状态写入调用
- 移除状态说明相关文档（`docs/API.md`、`docs/NATIVE_HOOK.md`、`docs/WORKFLOW.md`、`README.md` 同步），并新增「设备独占」API 文档
- 基于脚手架更新至 **MiuixGuiTemplate 0.5.0**（`about_based_on` 同步），并同步其「Hook 生效状态」机制
- 移植 [HyperConnectToolkit](https://github.com/silverpoetry/HyperConnectToolkit)（Apache-2.0）的设备互联三项功能，来源与许可已在应用内「第三方许可」页登记

### 修复

- **安全模式（崩溃循环保护）在 libxposed 上失效**：远程偏好对 hooked app 为 **只读**，原 `SafeModeManager` 从 hook 侧写入必然抛 `UnsupportedOperationException`（被吞后静默失效，且每个进程刷一条 ERROR）。改为：hook 侧仅 **只读** `safe_mode_<pkg>`；App 侧根据「目标进程成功装载 hook」的回报记录启动、窗口内重复启动累计为疑似崩溃，达到阈值（普通 3 / 关键 2）后把 `safe_mode_<pkg>` 回写远程偏好；hook 下次启动读到即跳过全部 hook。手动开关与重置同样回写

## 1.0.2

> 发布于 2026-09-26

### 新增

- **隐藏快充加速通知**（适配 HyperOS 4，目标 `com.miui.securitycenter`，功能页「安全服务 → 快充加速通知」二级页）：隐藏 90W 及以上快充机型在快充加速时由安全服务在「省电与电池重要通知」（`com.miui.powercenter.high`）类别下发布的通知。 **「进入提醒」与「退出提醒」各自独立开关与状态**；手机与平板功能一致、仅混淆类名不同，统一用 DexKit 按字符串特征定位，不写死类名
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
- 移除自定义的 SystemUI 热重载机制（`PluginLoader` 宿主恢复 / bootstrap）：插件 ClassLoader 仅在插件加载时捕获一次， **开启开关后需重启系统界面生效**
- 全局移除热重载功能：删除设置页「全局热重载」、作用域页与重启应用入口中的热重载，以及 `XposedServiceManager.hotReload`/`runningTargets`、`NativeHookHelper.reset`、`PackageTarget.restored`、`XposedEntry` 的 `onHotReloading`/`onHotReloaded` 与 `module.prop` 的 `autoHotReload`；仅保留「重启」（含 SystemUI 重启优化）

### 移除

- 脚手架示例（`DemoHook` / 示例标签页 / 示例配置项）及示例作用域 `com.android.settings`
