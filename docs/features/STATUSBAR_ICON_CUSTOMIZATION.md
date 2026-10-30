# 状态栏图标自定义

入口：**功能 → 系统界面 → 状态栏 → 状态栏图标**。目标进程 `com.android.systemui`，首次启用需要重启系统界面；之后的显示条件、几何、时钟格式、字号与右侧排序通过跨进程偏好即时刷新，排序不再要求重启。

## 1. 需求理解

1. 主页面按原生宿主分左右两组：上组为左侧运营商/时间/平板日期/通知图标，下组为右侧 `statusIcons`。组内从上到下对应状态栏从左到右。
2. 每行显示与 SystemUI 同风格的预览、组件名、分类与当前显示条件摘要；点击进入二级页。
3. 二级页只有一个互斥的**显示条件**下拉列表：跟随系统 / 始终显示 / 始终隐藏 / 仅控制中心显示 / 仅控制中心隐藏 / 组件状态条件；左侧图标没有独立控制中心宿主，因此只保留前五项中的状态栏语义；隐私指示在控制中心有独立宿主，和右侧图标一样支持全部五种模式。
4. 闹钟这类带阈值的条件，选中后直接在下方出现小时滑条，不再堆叠 30/60 分钟选项。
5. 通知图标没有可判定的系统逻辑，只保留显示/隐藏、最多图标数与溢出圆点数量。
6. 组件集合只保留 HyperCeiler 或 HyperHelper 出现、且本机能安全枚举的图标；`demo_*`、`handle*`、没有原生 slot 的 data saver / speakerphone / call record，以及本机实际不存在的 `volume` 不伪造。

## 2. 组件能力矩阵

图例：**显示条件** = 一个互斥下拉列表；**布局** = 上下偏移 / 左右间距 / 大小缩放 / 不透明度；**额外** = 组件专属参数。

| 组件 | 宿主 / slot | 显示条件 | 布局 | 额外与原生边界 |
|---|---|---|---|---|
| 时间 | `clock` / `MiuiClock` | 5 种基础项 | 偏移/间距/大小缩放/透明度/对齐/字重/字号 | 状态栏、通知中心大/小标题、控制中心、横屏五种格式；每种格式先由开关决定是否自定义，开启后默认填入当前系统格式，横屏默认在日期与星期之间保留空格；1s ticker；宽度开关 + 20..240dp 滑条（默认 60dp） |
| 平板日期 | `pad_clock` / `MiuiClock`（仅平板显示入口） | 5 种基础项 | 偏移/间距/大小缩放/透明度/字重/字号 | 作为左侧独立图标行显示/隐藏；格式先由开关决定是否自定义，默认填入系统日期/星期格式 |
| 运营商 | `operator_name` | 5 种基础项 | 偏移/间距/大小缩放/透明度/字重/字号 | 仅宿主已创建该 View 时生效 |
| 隐私指示 | `privacy_area` / `MiuiPrivacyContainerView` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 状态栏是 `privacy_area`，控制中心是独立的 `MiuiPrivacyContainerView` / `MiuiFakePrivacyContainerView`；运行时按 View 所在宿主分别套用五种模式，并在原生 `onPromptInfoChanged` / `setPrivacyIcons` / `onAttachedToWindow` 后重刷。预览为自绘相机+麦克风组合，不使用带底板的通用通知图标 |
| 通知图标 | `notification_icon_area` / `NotificationIconContainer` | 5 种基础项，无条件项 | 偏移/间距/大小缩放/透明度 | 位于左侧宿主；最多图标数 1..16；可开启“按隐藏图标数量显示圆点”，用原生溢出点的位置/颜色/动画显示 1..3 个点；排序/去重/优先级/点击由原生管理 |
| 网速 | `network_speed` (`NetworkSpeedView`) | 5 种 + 有/无流量 | 偏移/间距/大小缩放/透明度/对齐/字重/字号 | 字号开关 + 滑条与时间同位置；单位开关、单位换算、慢速隐藏阈值、1..10s 更新间隔；支持系统双行、仅数字、单行（数字与单位同一行，字号约为普通数值的两倍）、上下行双排；上下行合并仍由原生绘制 |
| Wi‑Fi | `wifi` | 5 种 + 连接/断开、开启/关闭 | 偏移/间距/大小缩放/透明度 | 信号格、上下行、网络类型角标由原生绘制 |
| 移动网络 | `mobile` | 5 种 + 连接/断开、移动数据开/关 | 偏移/间距/大小缩放/透明度 | 双卡/堆叠信号沿用现有双排功能；双排垂直偏移统一使用本页布局项 |
| 蓝牙 | `bluetooth` | 5 种 + 连接/断开、开启/关闭 | 偏移/间距/大小缩放/透明度 | 电量、编解码角标由原生绘制 |
| 闹钟 | `alarm_clock` | 5 种 + 闹钟前显示、无闹钟 | 偏移/间距/大小缩放/透明度 | 阈值由同页 0..72 小时滑条控制；下一个闹钟文本由原生绘制 |
| 电池 | `battery` / `battery_indicator` | 5 种 + 充电/未充电 | 偏移/间距/大小缩放/透明度 | 电量百分比、充电图形由原生绘制；预览按当前电量自绘比例电池；不提供无效的字重/字号项 |
| 定位 | `location` / `gps` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 不单独再列一个 `gps` 行，避免重复 |
| 耳机 | `headset` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | — |
| VPN | `vpn` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 默认跟随系统：仅在 VPN 已连接且系统创建 View 时出现；`always` 只让已存在的 View 保持可见 |
| 勿扰 | `zen` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 预览使用 HyperHelper 同源的 MIUI `ic_stat_sys_zen` |
| 静音 | `mute` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 预览使用 MIUI 音量图标 |
| 飞行模式 | `airplane` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | — |
| 热点 | `hotspot` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | — |
| NFC | `nfc` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | — |
| HD 通话 | `hd` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 预览使用 MIUI `ic_stat_sys_speech_hd` |
| 麦克风 | `micphone` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | HyperCeiler 对应“麦克风” |
| 隐身模式 | `stealth` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | HyperCeiler 对应隐藏项 |
| 蓝牙设备电量 / 音箱组合 / 立体声 / 音箱屏幕 / 音箱 / 无线耳机 | `bluetooth_handsfree_battery` / `sound_box_group` / `stereo` / `sound_box_screen` / `sound_box` / `wireless_headset` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 与 HyperHelper 设备分组对齐 |
| 分布式计算 / 相机 / 智能眼镜 / 车机 / 电视 / 电脑 / 平板 / 手机 / 无 SIM | `dist_compute` / `camera` / `glasses` / `car` / `tv` / `pc` / `pad` / `phone` / `no_sim` | 5 种基础项 | 偏移/间距/大小缩放/透明度 | 与 HyperHelper 设备/状态分组对齐 |

条件只决定“是否显示该图标”；系统根本没有创建 View 的图标不会被伪造，“始终显示”同样受这个限制。

右侧 slot 的显示切换会记录原生 `ignoredSlots` 归属和 `mBlocked` 原值：只有原生 `isIconVisible()` 为真时才解除 ignored/blocked 并强制可见；原生没有图标内容时保留 ignored，避免出现空白占位。切回 `system` 时恢复原生 ignored 归属和 blocked 状态，不再把模块的临时可见状态留在宿主里。

特殊宿主（时钟、平板日期、运营商、通知区、隐私指示）只有在有实际文本/子 View 时才强制显示；电池始终强制。隐私指示额外按可见子 View 判断，避免原生先收起容器、再更新子 View 时 `always` 被容器自身的 `GONE` 挡住。这样空的通知区、未触发的隐私指示也不会被 `always` 制造成空槽。

## 3. 配置模型

全部使用扁平偏好，不引入 JSON；App 侧 `ConfigState` 可响应式读取，Hook 侧用 `HookPrefs` 读取同一字符串键。

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `statusbar_icon_custom` | Boolean | false | 总开关；关闭后运行时恢复原生 ignoredSlots、显隐和几何 |
| `statusbar_icon_order` | String | `""` | 右侧可重排组件的逗号分隔 ID；空表示宿主原生顺序 |
| `statusbar_icon_<id>_display` | String | 迁移值 | 唯一显示条件：`system` / `always` / `hidden` / `cc_only_show` / `cc_only_hide` / `condition:<id>`；隐私指示也写 `cc_only_*`，左右宿主分别判断 |
| `statusbar_icon_<id>_mode` | String | `system` | 旧版字段，仅用于迁移到 `_display` |
| `statusbar_icon_<id>_condition` | String | `none` | 旧版字段，仅用于迁移 |
| `statusbar_icon_<id>_logic` | String | `default` | 旧版字段，仅用于迁移；UI 不再暴露 |
| `statusbar_icon_<id>_alarm_within` | Int | 24 | 闹钟前显示阈值，0..72 小时；仅闹钟组件 |
| `statusbar_icon_<id>_offset_y` | Float | 0 | dp，-24..24 |
| `statusbar_icon_<id>_gap_start` | Float | 0 | dp，-16..32；通过 padding 进入状态栏自绘布局 |
| `statusbar_icon_<id>_gap_end` | Float | 0 | 同上 |
| `statusbar_icon_<id>_scale` | Float | 1 | 0.3..3.0；仅自定义时覆盖原生动画缩放 |
| `statusbar_icon_<id>_alpha` | Float | 1 | 0..1；仅自定义时覆盖原生动画透明度 |
| `statusbar_icon_<id>_align` | String | `system` | 时间 / 网速对齐；按组件内可用宽度计算文字水平位移，其余组件不显示此项 |
| `statusbar_icon_<id>_font_weight_enabled` | Boolean | false | 是否覆盖字重；关闭时完全保持原生 |
| `statusbar_icon_<id>_font_weight` | Int | 推荐值 | 100..900，滑条步长 10；时钟/网速推荐 500，其余 400 |
| `statusbar_icon_<id>_font_size_enabled` | Boolean | false | 是否覆盖字号；关闭时完全保持原生 |
| `statusbar_icon_<id>_font_size` | Float | 推荐值 | 8..32sp；时间 14、平板日期 12、运营商 12；网速走独立 `_speed_font_size` |
| `statusbar_icon_clock_pattern_<scene>_custom` | Boolean | false | 是否启用该场景的自定义格式；关闭时完全跟随系统，不读取格式值 |
| `statusbar_icon_clock_pattern_status` | String | 系统时间格式 | 状态栏时间格式；自定义开关开启后生效 |
| `statusbar_icon_clock_pattern_notification_big` | String | 系统时间格式 | 通知中心大时钟格式 |
| `statusbar_icon_clock_pattern_notification_small` | String | 系统日期格式 | 通知中心小标题的日期/星期格式 |
| `statusbar_icon_clock_pattern_control_center` | String | 系统日期格式 | 控制中心的日期/星期格式 |
| `statusbar_icon_clock_pattern_horizontal` | String | 系统时间 + 日期/星期（中间带空格） | 横屏时钟格式；可改为任意 `SimpleDateFormat` |
| `statusbar_icon_clock_pattern_pad` | String | 系统日期格式 | 平板日期“月份 日期 星期”格式；仅平板入口 |
| `statusbar_icon_clock_refresh` | Boolean | false | 强制 1s ticker；格式含秒时也会自动开启 |
| `statusbar_icon_clock_am_pm` | Boolean | false | 把中文时段（上午/下午等）替换为 AM/PM；对系统与自定义格式都生效 |
| `statusbar_icon_clock_lock_width` | Boolean | false | 是否固定状态栏时钟宽度 |
| `statusbar_icon_clock_width` | Int | 60 | 固定宽度 dp，20..240；开关关闭时不写入布局 |
| `statusbar_icon_<id>_speed_unit_style` | String | `kb_s` | `kb_s` / `k` / `kb` / `kbit` / `kbit_s` / `none`；`none` 即不显示单位 |
| `statusbar_icon_<id>_speed_style` | String | `system` | `system` / `single_line` / `dual_line`；旧值 `number_only` 仍按仅数值兼容读取 |
| `statusbar_icon_<id>_speed_font_size` | Float | 0 | 0 = 自动；6..24sp，受 `_font_size_enabled` 开关控制；单行样式未手动设字号时使用普通数值字号约两倍 |
| `statusbar_icon_<id>_speed_slow_hide` | Boolean | false | 是否隐藏低于阈值的网速 |
| `statusbar_icon_<id>_speed_slow_threshold` | Int | 10 | 0..1024 KB/s；0 表示不隐藏 |
| `statusbar_icon_<id>_speed_interval` | Int | 1 | 1..10 秒；改写 `NetworkSpeedController` 的 200001 采样延时，不再只节流显示 |
| `statusbar_icon_<id>_max_notifications` | Int | 3 | 1..16，调用宿主 `setMaxIconsAmount` |
| `statusbar_icon_<id>_notification_multi_dot` | Boolean | false | 开启后按隐藏图标数绘制 1..3 个原生圆点；关闭保持系统固定三点 |

`<id>` 为固定组件 ID：`clock, pad_clock, operator, privacy, notifications, network_speed, wifi, mobile, bluetooth, alarm, battery, location, headset, vpn, zen, mute, airplane, hotspot, nfc, hd, micphone, stealth, bluetooth_handsfree_battery, sound_box_group, stereo, sound_box_screen, sound_box, wireless_headset, dist_compute, camera, glasses, car, tv, pc, pad, phone, no_sim`，共 37 个。`gps` 与 `location` 合并；`second_space` 没有可靠入口，未列入；`ethernet`、`cast`、`ime`、`managed_profile`、`missed_call`、`mikey`、`privacy_mode`、`tty` 不在 HyperCeiler/HyperHelper 的已识别集合内；`volume` 本机实际没有对应 View，已移除。

## 4. UI 页面结构

### 主页面 `NetworkIconsActivity`

1. 顶部：总开关卡片；首次启用需要重启系统界面的说明在副标题中。页面不显示搜索栏，避免与拖拽排序手势冲突。
2. **状态栏左侧**：按 `phone_status_bar_left_container` 的真实顺序列出运营商、时间、平板日期（仅平板）、通知图标；左侧宿主固定，不提供跨宿主拖拽。
3. **状态栏右侧**：`network_speed`、`mute`、`headset`、`nfc`、`micphone`、`stealth`、`location`、`alarm_clock`、`vpn`、`bluetooth`、蓝牙设备电量、热点、音箱/立体声/无线耳机、勿扰、分布式计算、相机、眼镜、车机/电视/电脑/平板/手机、HD、飞行模式、移动网络、无 SIM、Wi‑Fi；之后是电池，最右侧是 `privacy_area`。
4. 只有 `statusIcons` 的 slot 带排序手柄；拖动即时交换并写入 `statusbar_icon_order`，Hook 侧按真实 slot 重排 `mViewOnlySlots`、`mSlots` 后通过官方 `removeIconGroup` / `addIconGroup` 重建图标组，无需重启。拖动期间禁用 `LazyColumn` 滚动。
5. 右侧卡片底部“恢复默认顺序”写入空值，运行时回到已捕获的宿主原生顺序。
6. 全部行使用 Miuix `BasicComponent` + `HookSectionCard`：图标、标题、分类/状态摘要、箭头；预览使用 HyperHelper 同源的 MIUI `ic_stat_sys_*` 矢量，不再读取 SystemUI 的 `stat_sys_*`。

### 二级页

1. 顶部组件摘要使用标准 `BasicComponent`，24dp 预览 + 组件名 + 分类/状态摘要。
2. 唯一显示入口：**“显示条件”** 下拉菜单；所有条件在同一互斥列表中，使用 `OverlayDropdownPreference`，不再创建独立 Window。
3. **布局**：上下偏移、左右间距、大小缩放、不透明度；含文字组件有“修改字重”开关 + 100..900（步长 10）滑条；紧接着是“修改字号”开关 + 滑条，时间与网速位置一致；时间/网速有对齐。
4. 组件专属：
   - 时间：状态栏、通知中心大/小标题、控制中心、横屏五个格式；每个格式先显示“自定义格式”开关，开启后才启用下方格式卡片；卡片默认值就是当前系统格式，不再显示“留空跟随系统”的副标题；12 小时制的“上午/下午”等中文时段可单独开关替换为 AM/PM，对系统与自定义格式都生效；1s 刷新；锁定时钟宽度开关 + 20..240dp 滑条（默认 60dp）；右上角官方格式说明入口。格式输入使用 `WindowDialog` 弹窗。
   - 平板日期：作为独立左侧图标行；同样先开关后格式卡片，默认填入系统日期/星期格式。
   - 横屏默认格式由系统时间格式 + `getBestDateTimePattern("EMMMd")` 组成，并在日期与星期之间补一个空格；通知中心小标题、控制中心、平板日期的默认值读取同一日期/星期语法。
   - 闹钟：显示条件选“闹钟前显示”后，同页 0..72 小时滑条控制阈值。
   - 网速：指示器样式（系统双行 / 单行 / 上下行双排）、单位样式（含“不显示单位”）、慢速隐藏与 0..1024KB/s 阈值、1..10s 更新间隔、对齐；字号开关与滑条在“布局”区，与时间字号同位置；单行样式默认使用普通数值字号的约两倍，显式设置字号时以手动值为准。
   - 通知：最多图标数；“按隐藏图标数量显示圆点”开关。
   - 隐私指示：状态栏 `privacy_area` 与控制中心 `MiuiPrivacyContainerView` / `MiuiFakePrivacyContainerView` 都支持跟随系统/始终显示/始终隐藏/仅控制中心显示/仅控制中心隐藏；两套宿主分别判断，不再只保留前三项。
   - Wi‑Fi：Wi‑Fi 活动隐藏。
   - 移动网络：网络类型隐藏、双排信号与双排缩放/间距；双排垂直偏移不再单独重复，统一使用本页“上下偏移”。
5. 组件不支持的条件不进入列表；滑块由主开关控制启用/禁用时始终显示并置灰。
6. 分组标题与卡片间距统一使用 `SmallTitle` + `Card` 的 12dp 水平 / 12dp 底部规范。

## 5. 实现步骤与落点

1. **注册键与组件模型**：`StatusBarIconKeys`、`StatusBarIconRegistry`、`StatusBarIconConfig` 定义 37 个组件、统一显示条件与组件条件集合；旧 mode/condition/logic 只读迁移。
2. **运行时宿主**：`StatusBarIconHook` 挂在 `MiuiPhoneStatusBarView.onFinishInflate/onAttachedToWindow/updateCutoutLocation/setNotificationIconAreaInnner`、`StatusBarIconControllerImpl.addIconGroup`、`IconManager.onSetIcon/onRemoveIcon`、`DarkIconManager.onIconAdded`、`NetworkSpeedView.setNetworkSpeed/updateNetworkSpeed$1`、`StatusBarIconView.onDraw`、`MiuiBatteryMeterView.onFinishInflate`、`Clock.getSmallTime`、`MiuiClock.updateTime`，以及可选 hook `Handler.sendEmptyMessageDelayed` 调整网速采样间隔。
3. **排序**：首次 `addIconGroup` 前按 `mViewOnlySlots.mName` 重排，并把 `mSlots` 一起重排；`StatusBarIconControllerImpl` 构造器也会捕获 controller，兼容模块晚于图标组加载的情况。运行时在配置变化后 120ms 合并一次，先校验两个 slot 列表，再对每个 `IconManager` 走官方 `removeIconGroup` + `addIconGroup` 重建子 View；不直接搬动子 View，避免 `onViewRemoved` 退场动画、`layoutStates` 错位和索引不同步。组件 ID 会先映射为真实 slot（如 `alarm` → `alarm_clock`）。
4. **显示**：系统 slot 通过 `MiuiStatusIconContainer.setIgnoredSlots` + `requestLayout` + 子 View 显隐控制；`always`/`cc_only_show`/`cc_only_hide` 额外强制 `VISIBLE` 并解除 `blocked`。控制中心过渡使用的 `ControlCenterFakeStatusIcons` 是独立临时组件，沿用 `RIGHT_BLOCK_LIST`；蓝牙设备电量在该 `IconManager` 的 block list 里，组件内不会创建对应 View，只改 `ignoredSlots` 或 View 可见性无效。模块在 `IconManager.setBlockList` 下发时过滤“始终显示”的 slot，配置变化时重新下发；切回系统模式时恢复原生 block list。新补进临时组的图标会调用控制中心头部的明暗色刷新，避免保留 drawable 默认白色。时钟、平板日期、电池、通知、隐私、运营商是独立宿主，直接控制各自 View/容器；隐私指示的控制中心宿主不在状态栏 root 下，运行时从每个 `iconGroups` 的 `rootView` 额外发现 `MiuiPrivacyContainerView` / `MiuiFakePrivacyContainerView`，并在原生更新后重新套用。
   状态栏与「控制中心顶部假图标组」是两个独立 `MiuiStatusIconContainer`，仅改状态栏 ignoredSlots 不会影响控制中心；隐藏/仅控制中心模式同时同步 `StatusBarIconControllerImpl.mIconHideList` 并 `refreshIconGroups`。由于 `ModernStatusBarWifiView` 等新图标把 `setBlocked` 实现为空方法，还会遍历控制器注册的每个 `IconManager.mGroup`（含控制中心 fake 组）直接设置显隐；退出时恢复。
5. **控制中心**：优先读 `NotificationIconContainerInject.controlCenterPanelExpanded`；为兼容只维护通知面板标记的 ROM，回退 `notificationPanelExpanded`，再回退 `animatorController.controlPanelExpand`。
6. **条件**：`StatusBarIconConditionEvaluator` 使用 `ConnectivityManager`、`BluetoothManager`、`AlarmManager`、电池 sticky intent、通知容器计数、网速字段；不可判定时不强行隐藏。
7. **几何**：偏移/大小缩放/透明度在 `OnPreDraw` 中只对用户自定义项复写；间距改为增加子 View 的最小宽度，并用 `scrollX` 移动绘制内容——`MiuiStatusIconContainer.onLayout` 按“子 View 宽度 + 左右 padding”推进，并自己重写每个子 View 的 `translationX`，padding / translation 都会与原生排布叠加；对齐按组件内可用宽度计算文字水平位移。明暗切换不再重设 `imageTintList`，避免覆盖第三方主题图标。
8. **时间/网速**：按 `clock` / `big_time` / `date_time` / `normal_control_center_date_view` / `horizontal_time` / `pad_clock` 分场景套用 `SimpleDateFormat`。每个场景先检查独立自定义开关，关闭时完全不干预宿主；开启后格式为空则回退到当前系统时间/日期语法，横屏在日期与星期之间补空格。含秒自动启动 1s ticker；`clock_lock_width` 写入 `clock_width` dp，关闭恢复原宽度；字重同时写入 `Typeface` 与可变字体 `wght` 轴（静态字体才回退为就近字重），字号缓存原始 px。网速支持系统双行、仅数字、单行、上下行双排；单行使用降采样值拼成“数字 单位”，默认字号为普通数值的两倍，显式字号优先；更新间隔通过 `Handler.sendEmptyMessageDelayed(200001)` 改写 `NetworkSpeedController` 的 4s 采样延时，失败时回退原生节奏。
9. **UI**：主页面与二级页只使用 Miuix `HookSectionCard` / `BasicComponent` / `WindowDropdownPreference` / `SliderPreference` / `WindowDialog`；不修改 `BaseSubPageActivity` / `SubPageScaffold` 的通用窗口策略，详情页外观与其它二级页一致。
10. **真实顺序**：左侧为 `phone_status_bar_left_container`（operator、clock、pad_clock、notification_icon_area）；右侧为 `statusIcons`，电池在其末尾，`privacy_area` 是根布局 END 对齐的覆盖层。`StatusBarIconRegistryTest` 固定关键顺序。

## 6. 设计参考

交互分类、预览资源与部分控件语义参考了以下项目：

- [XiaomiHelper / HyperHelper](https://github.com/HowieHChen/XiaomiHelper)：状态栏图标预览资源（`ic_stat_sys_*`，GPL-3.0）、`BasicComponent` 图标行、独立排序手柄、首/中/末卡片形状；资源保留原始许可声明，应用内已登记致谢；逐文件说明见 [预览资源来源](THIRD_PARTY_ICONS.md)。
- [HyperCeiler](https://github.com/HyperCeiler/HyperCeiler)：按网络 / 蓝牙 / 隐私 / 状态 / 通知 / 电池 / 通话分类，以及“默认 / 全部显示 / 仅状态栏 / 仅控制中心 / 隐藏”的可见性语义；网速指示器与时钟的字号/字重/宽度/格式思路参考，独立实现。

本模块文档规范优先：分类只作为行摘要，不拆散左右位置与拖拽顺序；间距、卡片、标题和二级页模板仍使用本模块 `HookSectionCard` / `BasicComponent` / `SubPageScaffold`。

## 7. 明确的边界

1. **跨宿主拖动不实现**：左侧 `phone_status_bar_left_container` 与右侧 `MiuiStatusIconContainer` 是不同 ViewGroup；把单个 `StatusBarIconView` 搬过去会破坏 `StatusBarIconControllerImpl` 的 slot 索引、动画和 `onRemoveIcon(index)`。主页面只做左右组内排序，右侧不会伪称能拖到左侧。
2. **右侧 slot 顺序实时生效**：运行时按真实 slot 重排 `mViewOnlySlots`、`mSlots`，再用官方 `removeIconGroup` / `addIconGroup` 重建图标组；新增/删除图标时宿主仍按重排后的索引工作。该路径需真机验证，若某 ROM 的 `mGroup` 结构不同则失败关闭、保持原生顺序。
3. **不能凭空创建图标**：`always` 只能解除模块隐藏/原生 ignored/`blocked`，并在 View 已存在时保持 `VISIBLE`，不能合成宿主没有创建的 View。没有 SIM、没有连接、没有通知时，对应图标仍可能不存在；VPN 关闭时同样不会凭空出现。
4. **控制中心状态不是完整 shade API**：当前读取宿主注入的 `controlCenterPanelExpanded`，并兼容 `notificationPanelExpanded` 与旧的 `controlPanelExpand`；若特定 ROM 三个字段都不更新，`cc_only` 模式会退化为始终按状态栏显示。
5. **原生绘制的角标/文本不接管**：网络类型、上下行、信号格、蓝牙电量、闹钟下一次时间、通知排序/去重/优先级/点击行为、网速上下行拆分仍由 SystemUI 绘制；本页控制可见性、最大通知数、通知溢出圆点数量、文本单位、采样/刷新间隔、字号/字重和时钟格式。
6. **“始终显示”不是新建 View**：系统把某个 slot 标记为不可见时，`setBlocked(false)` + 移除 ignored + `VISIBLE` 可能仍不足以显示一个不存在的 View。
7. **左侧顺序不由模块控制**：左侧容器由宿主布局决定，主页面左侧卡片只做显隐/布局/格式，不提供拖拽；平板日期只在平板显示入口。
8. **ticker 的代价**：任一格式含秒或强制开启“每秒刷新”才运行 1s 主线程 ticker；关闭即移除。
9. **格式语义**：纯时间格式中的大写 `MM`/`SS` 会按 HyperHelper 习惯视作分/秒；含 `yyyy`/`dd`/`MMM` 的日期格式保持 `SimpleDateFormat` 原语义。
10. **字重/字号覆盖范围**：字重对时间、平板日期、运营商、网速等含文字组件开放，滑条 100..900、步长 10；字号对时间、平板日期、运营商开放，网速使用独立字号。电池百分比由 `MiuiBatteryMeterView` 自绘，不是 TextView，因此不提供无效的字重/字号项；通知应用图标、信号格、角标仍由 SystemUI 绘制。

## 8. 验证状态

- 已通过 `.\gradlew.bat :hook:testDebugUnitTest :app:assembleDebug` 构建，并通过 `StatusBarIconRegistryTest`（37 个组件 ID/slot 唯一、排序去重、枚举回退）。
- 图标详情页使用独立且中性的页面类，避免窗口被控制中心 PassBlur 按类名误判为系统状态栏；页面内容、配置读写和窗口结构均与其它二级页一致。
- 本轮新增的网速样式/字号/慢速隐藏/更新间隔、通知圆点数量、字重 10 档与对齐位移需要在真机重启系统界面后验证；模块只在目标进程生效，构建通过不等于 ROM 行为已确认。
- 静态核对基于当前设备 `OS4.0.5.0.XPMCNXM`、Android 17、SystemUI `17.03.260226.r`；不对外推到其他 ROM。
