package cn.ianzb.hyperrefine.ui.screen.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.LocaleHelper
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.dexkit.DexKitCacheManager
import cn.ianzb.hyperrefine.hook.device.DeviceContext
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.prefs.ConfigBackup
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.ui.screen.safemode.SafeModeActivity
import cn.ianzb.hyperrefine.ui.util.BlurredBar
import cn.ianzb.hyperrefine.ui.util.blurSource
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import cn.ianzb.hyperrefine.ui.util.rememberBlurState
import cn.ianzb.hyperrefine.xposed.HookStatusReader
import cn.ianzb.hyperrefine.xposed.RootHelper
import cn.ianzb.hyperrefine.xposed.SafeModeReader
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun SettingsPageView(
    currentMode: ColorSchemeMode,
    onModeChange: (ColorSchemeMode) -> Unit,
    isFloatingNavbar: Boolean,
    onFloatingNavbarChange: (Boolean) -> Unit,
    isLiquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    isBlurEnabled: Boolean,
    onBlurEnabledChange: (Boolean) -> Unit,
    checkUpdateOnLaunch: Boolean,
    onCheckUpdateOnLaunchChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    isCheckingUpdate: Boolean,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val title = stringResource(R.string.tab_settings)
    val safeModePackages = SafeModeReader.safeModePackages

    LaunchedEffect(Unit) { SafeModeReader.refresh() }

    val hazeState = rememberBlurState()
    val blurActive = isBlurEnabled && hazeState != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                val json = ConfigBackup.exportJson()
                context.contentResolver.openOutputStream(it)?.use { output ->
                    output.write(json.toByteArray())
                }
                Toast.makeText(context, resources.getString(R.string.export_success), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, resources.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val json = reader.readText()
                reader.close()
                inputStream?.close()
                ConfigBackup.importJson(json)
                Toast.makeText(context, resources.getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                activity?.recreate()
            } catch (_: Exception) {
                Toast.makeText(context, resources.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(hazeState, blurActive, scrollBehavior) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .blurSource(if (isBlurEnabled) hazeState else null)
                    .pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                    ),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + extraBottomPadding
                )
            ) {
                item {
                    Column {
                        SmallTitle(text = stringResource(R.string.settings_module))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            val autoDevice = remember { DeviceContext.detected.type }
                            val deviceOptions = listOf(
                                stringResource(
                                    R.string.device_type_default,
                                    stringResource(deviceTypeLabelRes(autoDevice)),
                                ),
                                stringResource(R.string.device_type_phone),
                                stringResource(R.string.device_type_pad),
                                stringResource(R.string.device_type_fold),
                            )
                            val deviceValues = listOf(
                                DeviceType.OVERRIDE_AUTO,
                                DeviceType.PHONE.key,
                                DeviceType.PAD.key,
                                DeviceType.FOLD.key,
                            )
                            val savedDevice = ConfigState.string(
                                DeviceContext.KEY_DEVICE_TYPE,
                                DeviceType.OVERRIDE_AUTO,
                            )
                            val deviceIndex = deviceValues.indexOf(savedDevice).takeIf { it >= 0 } ?: 0
                            WindowDropdownPreference(
                                title = stringResource(R.string.settings_device_type),
                                summary = deviceOptions[deviceIndex],
                                items = deviceOptions,
                                selectedIndex = deviceIndex,
                                onSelectedIndexChange = {
                                    ConfigState.set(DeviceContext.KEY_DEVICE_TYPE, deviceValues[it])
                                },
                                onExpandedChange = { },
                            )

                            if (safeModePackages.isNotEmpty()) {
                                BasicComponent(
                                    title = stringResource(R.string.safe_mode_active_title),
                                    summary = stringResource(
                                        R.string.safe_mode_active_summary,
                                        safeModePackages.size,
                                    ),
                                )
                            }
                            ArrowPreference(
                                title = stringResource(R.string.safe_mode_manage),
                                summary = if (safeModePackages.isEmpty()) {
                                    stringResource(R.string.safe_mode_manage_summary_none)
                                } else {
                                    stringResource(
                                        R.string.safe_mode_manage_summary_active,
                                        safeModePackages.size,
                                    )
                                },
                                onClick = {
                                    context.startActivity(Intent(context, SafeModeActivity::class.java))
                                },
                            )

                            ArrowPreference(
                                title = stringResource(R.string.module_scope_request),
                                summary = stringResource(R.string.module_scope_request_summary),
                                onClick = {
                                    val packages = OptionRegistry.all()
                                        .flatMap { it.targetPackages }
                                        .distinct()
                                    XposedServiceManager.ensureScope(packages) { ok, message ->
                                        val text = if (ok) {
                                            resources.getString(R.string.module_scope_request_success)
                                        } else {
                                            resources.getString(R.string.module_scope_request_failed, message ?: "")
                                        }
                                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.module_hot_reload),
                                summary = stringResource(R.string.module_hot_reload_summary),
                                onClick = {
                                    val packages = OptionRegistry.all()
                                        .flatMap { it.targetPackages }
                                        .distinct()
                                    XposedServiceManager.hotReload(packages) { result ->
                                        HookStatusReader.refresh()
                                        val text = if (result == "no running target") {
                                            resources.getString(R.string.module_hot_reload_no_target)
                                        } else {
                                            resources.getString(R.string.module_hot_reload_result, result)
                                        }
                                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.module_clear_dexkit),
                                summary = stringResource(R.string.module_clear_dexkit_summary),
                                onClick = {
                                    val targets = XposedServiceManager.scope
                                    scope.launch {
                                        val success = withContext(Dispatchers.IO) {
                                            RootHelper.deleteDexKitCache(targets, DexKitCacheManager.CACHE_DIR)
                                        }
                                        val text = if (success) {
                                            resources.getString(R.string.module_clear_dexkit_success)
                                        } else {
                                            resources.getString(R.string.module_clear_dexkit_failed)
                                        }
                                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }

                        SmallTitle(text = stringResource(R.string.settings_interface))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                val modes = listOf(
                                    stringResource(R.string.theme_system),
                                    stringResource(R.string.theme_light),
                                    stringResource(R.string.theme_dark),
                                    stringResource(R.string.theme_monet_system),
                                    stringResource(R.string.theme_monet_light),
                                    stringResource(R.string.theme_monet_dark)
                                )
                                val modesEnum = listOf(
                                    ColorSchemeMode.System,
                                    ColorSchemeMode.Light,
                                    ColorSchemeMode.Dark,
                                    ColorSchemeMode.MonetSystem,
                                    ColorSchemeMode.MonetLight,
                                    ColorSchemeMode.MonetDark
                                )
                                val currentIndex = modesEnum.indexOf(currentMode).takeIf { it >= 0 } ?: 0

                                WindowDropdownPreference(
                                    title = stringResource(R.string.theme_mode),
                                    summary = modes[currentIndex],
                                    items = modes,
                                    selectedIndex = currentIndex,
                                    onSelectedIndexChange = { onModeChange(modesEnum[it]) },
                                    onExpandedChange = { }
                                )

                                SwitchPreference(
                                    title = stringResource(R.string.floating_navbar),
                                    summary = stringResource(R.string.floating_navbar_summary),
                                    checked = isFloatingNavbar,
                                    onCheckedChange = onFloatingNavbarChange
                                )

                                AnimatedVisibility(
                                    visible = isFloatingNavbar,
                                    enter = expandVertically(),
                                    exit = shrinkVertically(),
                                ) {
                                    SwitchPreference(
                                        title = stringResource(R.string.liquid_glass),
                                        summary = stringResource(R.string.liquid_glass_summary),
                                        checked = isLiquidGlass,
                                        onCheckedChange = onLiquidGlassChange
                                    )
                                }

                                SwitchPreference(
                                    title = stringResource(R.string.blur_enabled),
                                    summary = stringResource(R.string.blur_enabled_summary),
                                    checked = isBlurEnabled,
                                    onCheckedChange = onBlurEnabledChange
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.settings_language))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            val languageNames = listOf(
                                stringResource(R.string.language_default),
                                stringResource(R.string.language_zh_cn),
                                stringResource(R.string.language_en)
                            )
                            val languageValues = listOf(
                                LocaleHelper.Language.SYSTEM,
                                LocaleHelper.Language.ZH_CN,
                                LocaleHelper.Language.EN
                            )
                            val savedLanguage = LocaleHelper.getSavedLanguage(context)
                            val langCurrentIndex = languageValues.indexOf(savedLanguage)
                                .takeIf { it >= 0 } ?: 0

                            WindowDropdownPreference(
                                title = stringResource(R.string.settings_language),
                                summary = languageNames[langCurrentIndex],
                                items = languageNames,
                                selectedIndex = langCurrentIndex,
                                onSelectedIndexChange = {
                                    LocaleHelper.setLanguage(context, languageValues[it])
                                    activity?.recreate()
                                },
                                onExpandedChange = { }
                            )
                        }

                        SmallTitle(text = stringResource(R.string.settings_update))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                SwitchPreference(
                                    title = stringResource(R.string.check_update_on_launch),
                                    summary = stringResource(R.string.check_update_on_launch_summary),
                                    checked = checkUpdateOnLaunch,
                                    onCheckedChange = onCheckUpdateOnLaunchChange,
                                )

                                ArrowPreference(
                                    title = stringResource(R.string.check_update),
                                    summary = if (isCheckingUpdate) {
                                        stringResource(R.string.check_update_checking)
                                    } else {
                                        stringResource(R.string.check_update_summary, appVersion)
                                    },
                                    onClick = onCheckUpdate,
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.settings_data))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                ArrowPreference(
                                    title = stringResource(R.string.export_settings),
                                    summary = stringResource(R.string.export_settings_summary),
                                    onClick = {
                                        exportLauncher.launch("HyperRefine_settings.json")
                                    }
                                )

                                ArrowPreference(
                                    title = stringResource(R.string.import_settings),
                                    summary = stringResource(R.string.import_settings_summary),
                                    onClick = { importLauncher.launch("application/json") }
                                )
                            }
                        }

                        MiuixText(
                            text = stringResource(R.string.copyright),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/** 设备类型对应的显示文案资源。 */
private fun deviceTypeLabelRes(type: DeviceType): Int = when (type) {
    DeviceType.PHONE -> R.string.device_type_phone
    DeviceType.PAD -> R.string.device_type_pad
    DeviceType.FOLD -> R.string.device_type_fold
}
