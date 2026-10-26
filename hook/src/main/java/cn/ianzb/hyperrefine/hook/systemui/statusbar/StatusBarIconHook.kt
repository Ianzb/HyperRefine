package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 状态栏图标自定义入口。
 *
 * 设计边界：只重排/隐藏 SystemUI 已经创建的 native 图标，并复用官方的
 * ignoredSlots、Clock、NetworkSpeedView、NotificationIconContainer 状态；
 * 不创建悬浮窗，不伪造系统没有创建的图标。
 */
class StatusBarIconHook : BaseHook() {
    override val key = StatusBarIconKeys.ENABLED

    override fun init() {
        val loader = target.classLoader
        val guard = StatusBarGuard(tag)
        val viewClass = Reflect.findClass(
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
            loader,
        )
        val onFinish = StatusBarMembers.methods(viewClass, "onFinishInflate") {
            it.parameterCount == 0
        }
        val onAttached = StatusBarMembers.methods(viewClass, "onAttachedToWindow") {
            it.parameterCount == 0
        }
        val updateCutout = StatusBarMembers.methods(viewClass, "updateCutoutLocation") {
            it.parameterCount == 0
        }
        val setNotificationArea = StatusBarMembers.methods(viewClass, "setNotificationIconAreaInnner") {
            it.parameterCount == 1 && View::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        val controllerClass = Reflect.findClass(
            "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
            loader,
        )
        val addGroup = StatusBarMembers.methods(controllerClass, "addIconGroup") {
            it.parameterCount == 1
        }
        val iconManagerClass = Reflect.findClass(
            "com.android.systemui.statusbar.phone.ui.IconManager",
            loader,
        )
        val setBlockList = iconManagerClass.declaredMethods.firstOrNull {
            it.name == "setBlockList" &&
                it.parameterCount == 1 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.apply { isAccessible = true }
        val onSetIcon = StatusBarMembers.methods(iconManagerClass, "onSetIcon") {
            it.parameterCount == 2
        }
        val onRemoveIcon = StatusBarMembers.methods(iconManagerClass, "onRemoveIcon") {
            it.parameterCount == 1
        }
        val darkIconManagerClass = Reflect.findClass(
            "com.android.systemui.statusbar.phone.ui.DarkIconManager",
            loader,
        )
        val onIconAdded = StatusBarMembers.methods(darkIconManagerClass, "onIconAdded") {
            it.parameterCount == 4
        }
        val speedClass = Reflect.findClass(
            "com.android.systemui.statusbar.views.NetworkSpeedView",
            loader,
        )
        val setSpeed = StatusBarMembers.methods(speedClass, "setNetworkSpeed") {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        }
        val updateSpeed = runCatching {
            StatusBarMembers.methods(speedClass, "updateNetworkSpeed\$1") { it.parameterCount == 0 }
        }.getOrDefault(emptyList())
        val clockClass = Reflect.findClassIfExists(
            "com.android.systemui.statusbar.policy.Clock",
            loader,
        )
        val getSmallTime = clockClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "getSmallTime") { method ->
                    method.parameterCount == 0 &&
                        CharSequence::class.java.isAssignableFrom(method.returnType)
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val miuiClockClass = Reflect.findClassIfExists(
            "com.android.systemui.statusbar.views.MiuiClock",
            loader,
        )
        val miuiUpdateTime = miuiClockClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "updateTime") { method -> method.parameterCount == 0 }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val statusIconViewClass = Reflect.findClassIfExists(
            "com.android.systemui.statusbar.StatusBarIconView",
            loader,
        )
        val statusIconOnDraw = statusIconViewClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "onDraw") { method ->
                    method.parameterCount == 1 &&
                        method.parameterTypes[0] == android.graphics.Canvas::class.java
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val iconContainerClass = Reflect.findClassIfExists(
            "com.android.systemui.statusbar.views.MiuiStatusIconContainer",
            loader,
        )
        val onIconContainerLayout = iconContainerClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "onLayout") { method -> method.parameterCount == 5 }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val notificationClass = Reflect.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            loader,
        )
        val notificationMax = notificationClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "setMaxIconsAmount") { method ->
                    method.parameterCount == 1
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val notificationLayout = notificationClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "onLayout") { method ->
                    method.parameterCount == 5
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val notificationAdded = notificationClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "onViewAdded") { method ->
                    method.parameterCount == 1
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val notificationRemoved = notificationClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "onViewRemoved") { method ->
                    method.parameterCount == 1
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        // NotificationIconContainer 没有覆写 onAttachedToWindow；不能用 methods()
        // 沿父类查找，否则会把 View.onAttachedToWindow 全局 hook 到所有 View 上。
        val notificationAttached = notificationClass?.let { type ->
            runCatching {
                type.declaredMethods
                    .filter { it.name == "onAttachedToWindow" && it.parameterCount == 0 }
                    .onEach { it.isAccessible = true }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val batteryViewClass = Reflect.findClassIfExists(
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView",
            loader,
        )
        val batteryAttached = batteryViewClass?.let {
            runCatching {
                StatusBarMembers.methods(it, "onFinishInflate") { method ->
                    method.parameterCount == 0 &&
                        method.declaringClass.name.startsWith("com.android.systemui")
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val handlerClass = android.os.Handler::class.java
        val sendEmptyDelayed = runCatching {
            handlerClass.getDeclaredMethod(
                "sendEmptyMessageDelayed",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()

        val installs = mutableListOf<() -> io.github.libxposed.api.XposedInterface.HookHandle>()
        onFinish.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        (param.thisObject as? ViewGroup)?.let(StatusBarIconRuntime::attach)
                    }
                }
            }
        }
        onAttached.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        (param.thisObject as? ViewGroup)?.let {
                            StatusBarIconRuntime.attach(it)
                            StatusBarIconRuntime.refreshSoon(it)
                        }
                    }
                }
            }
        }
        updateCutout.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run { (param.thisObject as? ViewGroup)?.let(StatusBarIconRuntime::refreshSoon) }
                }
            }
        }
        setNotificationArea.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run { (param.thisObject as? ViewGroup)?.let(StatusBarIconRuntime::refreshSoon) }
                }
            }
        }
        addGroup.forEach { method ->
            installs += {
                StatusBarMembers.before(method) { param ->
                    guard.run { param.thisObject?.let(StatusBarIconRuntime::sortSlots) }
                }
            }
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        val manager = param.args.getOrNull(0) ?: return@run
                        val group = runCatching {
                            Reflect.getObjectField(manager, "mGroup") as? ViewGroup
                        }.getOrNull()
                        StatusBarIconRuntime.registerIconGroup(group)
                    }
                }
            }
        }
        // 模块可能在 SystemUI 已创建 controller/图标组之后才加载；构造器 hook 保证
        // controllerRef 一定能建立，并在首个 IconManager 添加前完成 slot 排序。
        controllerClass.declaredConstructors.forEach { constructor ->
            installs += {
                StatusBarMembers.after(constructor) { param ->
                    guard.run { param.thisObject?.let(StatusBarIconRuntime::sortSlots) }
                }
            }
        }
        if (setBlockList != null) {
            installs += {
                StatusBarMembers.before(setBlockList) { param ->
                    guard.run {
                        val manager = param.thisObject ?: return@run
                        val slots = param.args.getOrNull(0) as? List<*> ?: return@run
                        param.setArg(
                            0,
                            StatusBarIconRuntime.filterIconBlockList(manager, slots),
                        )
                    }
                }
            }
            installs += {
                StatusBarMembers.after(setBlockList) { param ->
                    guard.run {
                        param.thisObject?.let(StatusBarIconRuntime::refreshIconTint)
                    }
                }
            }
        }
        onSetIcon.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { _ ->
                    guard.run { StatusBarIconRuntime.refreshSoon() }
                }
            }
        }
        onRemoveIcon.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { _ ->
                    guard.run { StatusBarIconRuntime.refreshSoon() }
                }
            }
        }
        onIconAdded.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { _ ->
                    guard.run { StatusBarIconRuntime.refreshSoon() }
                }
            }
        }
        setSpeed.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        (param.thisObject as? View)?.let(StatusBarIconRuntime::applyNetworkSpeed)
                    }
                }
            }
        }
        updateSpeed.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        (param.thisObject as? View)?.let(StatusBarIconRuntime::applyNetworkSpeed)
                    }
                }
            }
        }
        getSmallTime.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        val view = param.thisObject as? View ?: return@run
                        if (view is android.widget.TextView) {
                            StatusBarIconRuntime.rememberClockText(view)
                        }
                        val text = StatusBarIconRuntime.clockText(view.context, view.id)
                        if (text != null) {
                            param.setResultValue(text)
                        } else {
                            param.setResultValue(
                                StatusBarIconRuntime.withClockAmPm(param.result as? CharSequence),
                            )
                        }
                        if (view is android.widget.TextView) {
                            StatusBarIconRuntime.registerClock(view)
                            StatusBarIconRuntime.applyClockWeight(view)
                            StatusBarIconRuntime.applyClockLockWidth(view)
                        }
                    }
                }
            }
        }
        miuiUpdateTime.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        val view = param.thisObject as? android.widget.TextView ?: return@run
                        StatusBarIconRuntime.registerClock(view)
                        StatusBarIconRuntime.rememberClockText(view)
                        StatusBarIconRuntime.applyClockWeight(view)
                        StatusBarIconRuntime.clockText(view.context, view.id)?.let { view.text = it }
                        StatusBarIconRuntime.applyClockAmPm(view)
                        StatusBarIconRuntime.applyClockLockWidth(view)
                    }
                }
            }
        }
        statusIconOnDraw.forEach { method ->
            installs += {
                StatusBarMembers.before(method) { param ->
                    guard.run {
                        (param.thisObject as? View)?.let(StatusBarIconRuntime::beforeNotificationDotDraw)
                    }
                }
            }
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        val view = param.thisObject as? View ?: return@run
                        StatusBarIconRuntime.afterNotificationDotDraw(
                            view,
                            param.args.getOrNull(0) as? android.graphics.Canvas,
                        )
                    }
                }
            }
        }
        batteryAttached.forEach { method ->
            installs += {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        StatusBarIconRuntime.registerBatteryView(param.thisObject as? View)
                        StatusBarIconRuntime.refreshSoon()
                    }
                }
            }
        }
        StatusBarMembers.install(installs)
        // 这些 hook 只优化间距动画与通知上限的即时性；部分 ROM 不支持 hook
        // onLayout/构造器时不能让它们把整套状态栏图标功能一起回滚。
        onIconContainerLayout.forEach { method ->
            runCatching {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        StatusBarIconRuntime.applyIconGapTranslations(
                            param.thisObject as? ViewGroup,
                        )
                    }
                }
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "icon layout after hook unavailable", it)
            }
            runCatching {
                StatusBarMembers.before(method) { param ->
                    guard.run {
                        StatusBarIconRuntime.prepareIconLayout(param.thisObject as? ViewGroup)
                    }
                }
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "icon layout before hook unavailable", it)
            }
        }
        notificationAttached.forEach { method ->
            runCatching {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        StatusBarIconRuntime.applyNotificationLimit(param.thisObject as? View)
                    }
                }
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "notification attach hook unavailable", it)
            }
        }
        notificationLayout.forEach { method ->
            runCatching {
                StatusBarMembers.before(method) { param ->
                    guard.run {
                        StatusBarIconRuntime.applyNotificationLimit(param.thisObject as? View)
                    }
                }
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "notification layout hook unavailable", it)
            }
        }
        notificationMax.forEach { method ->
            runCatching {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        StatusBarIconRuntime.applyNotificationLimit(param.thisObject as? View)
                    }
                }
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "notification max hook unavailable", it)
            }
        }
        (notificationAdded + notificationRemoved).forEach { method ->
            runCatching {
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        StatusBarIconRuntime.invalidateNotificationDots(
                            param.thisObject as? View,
                        )
                    }
                }
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "notification child hook unavailable", it)
            }
        }
        notificationClass?.declaredConstructors?.forEach { constructor ->
            runCatching {
                StatusBarMembers.after(constructor) { param ->
                    guard.run {
                        StatusBarIconRuntime.applyNotificationLimit(param.thisObject as? View)
                    }
                }
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "notification constructor hook unavailable", it)
            }
        }
        sendEmptyDelayed?.let { method ->
            runCatching {
                StatusBarMembers.before(method) { param ->
                    guard.run {
                        if (param.args.getOrNull(0) != 200001) return@run
                        val name = param.thisObject?.javaClass?.name ?: return@run
                        if (!name.contains("NetworkSpeedController")) return@run
                        val currentDelay = param.args.getOrNull(1) as? Long ?: 0L
                        if (currentDelay <= 0L) return@run
                        val seconds = HookPrefs.getIntFlexible(
                            StatusBarIconKeys.speedInterval(StatusBarIconRegistry.NETWORK_SPEED),
                            1,
                        ).coerceIn(1, 10)
                        param.setArg(1, seconds * 1000L)
                    }
                }
            }.onFailure {
                // 框架 Handler 在部分 ROM 上不可 hook；保留其余状态栏能力，间隔回退原生 4s。
                android.util.Log.w("HyperRefineStatusBar", "speed interval hook unavailable", it)
            }
        }
        HookPrefs.registerListener { StatusBarIconRuntime.onPreferencesChanged() }
    }
}
