package cn.ianzb.hyperrefine.ui.screen.settings

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.LocaleHelper
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.AppSectionCard
import cn.ianzb.hyperrefine.hook.dexkit.DexKitCacheManager
import cn.ianzb.hyperrefine.hook.device.DeviceContext
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.prefs.ConfigBackup
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.ui.screen.subpage.AppSubPage
import cn.ianzb.hyperrefine.ui.screen.subpage.rememberOpenAppSubPage
import cn.ianzb.hyperrefine.BottomBarStyle
import cn.ianzb.hyperrefine.RailStyle
import cn.ianzb.hyperrefine.UpdateChecker
import cn.ianzb.hyperrefine.ui.component.AppLazyPage
import cn.ianzb.hyperrefine.ui.component.CurrentChangelogDialog
import cn.ianzb.hyperrefine.ui.component.AppCardRow
import cn.ianzb.hyperrefine.ui.component.appGroupedCardItems
import cn.ianzb.hyperrefine.ui.util.LocalPreferenceBackdrop
import cn.ianzb.hyperrefine.xposed.RootHelper
import cn.ianzb.hyperrefine.xposed.SafeModeReader
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import cn.ianzb.hyperrefine.ui.component.AppArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun SettingsPageView(
    currentMode: ColorSchemeMode,
    onModeChange: (ColorSchemeMode) -> Unit,
    bottomBarStyle: BottomBarStyle,
    onBottomBarStyleChange: (BottomBarStyle) -> Unit,
    railStyle: RailStyle,
    onRailStyleChange: (RailStyle) -> Unit,
    isBlurEnabled: Boolean,
    onBlurEnabledChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    checkUpdateOnLaunch: Boolean,
    onCheckUpdateOnLaunchChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    isCheckingUpdate: Boolean,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val openSafeMode = rememberOpenAppSubPage(AppSubPage.SafeMode)
    val resources = LocalResources.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val title = stringResource(R.string.tab_settings)
    val safeModePackages = SafeModeReader.safeModePackages

    LaunchedEffect(Unit) { SafeModeReader.refresh() }

    val appVersion = remember(context) { UpdateChecker.currentVersion(context) }
    var showChangelog by rememberSaveable { mutableStateOf(false) }

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
                val result = ConfigBackup.importJson(json)
                if (result.success) {
                    val message = if (result.changedCount == 0) {
                        resources.getString(R.string.import_success_unchanged)
                    } else {
                        resources.getString(R.string.import_success, result.changedCount)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    activity?.recreate()
                } else {
                    Toast.makeText(context, resources.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, resources.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    AppLazyPage(
        title = title,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        scrollBehavior = scrollBehavior,
    ) {
        appGroupedCardItems(
            title = R.string.settings_module,
            AppCardRow(R.string.settings_device_type) {
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
                GlassDropdownPreference(
                    title = stringResource(R.string.settings_device_type),
                    items = deviceOptions,
                    selectedIndex = deviceIndex,
                    onSelectedIndexChange = {
                        ConfigState.set(DeviceContext.KEY_DEVICE_TYPE, deviceValues[it])
                    },
                    backdrop = LocalPreferenceBackdrop.current,
                    renderInRootScaffold = true,
                )
            },
            if (safeModePackages.isNotEmpty()) AppCardRow(R.string.safe_mode_active_title) {
                BasicComponent(title = stringResource(R.string.safe_mode_active_title))
            } else null,
            AppCardRow(R.string.safe_mode_manage) {
                AppArrowPreference(
                    title = stringResource(R.string.safe_mode_manage),
                    onClick = openSafeMode,
                )
            },
            AppCardRow(R.string.module_scope_request) {
                AppArrowPreference(
                    title = stringResource(R.string.module_scope_request),
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
            },
            AppCardRow(R.string.module_clear_dexkit) {
                AppArrowPreference(
                    title = stringResource(R.string.module_clear_dexkit),
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
            },
        )

        appGroupedCardItems(
            title = R.string.settings_interface,
            AppCardRow(R.string.theme_mode) {
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

                GlassDropdownPreference(
                    title = stringResource(R.string.theme_mode),
                    items = modes,
                    selectedIndex = currentIndex,
                    onSelectedIndexChange = { onModeChange(modesEnum[it]) },
                    backdrop = LocalPreferenceBackdrop.current,
                    renderInRootScaffold = true,
                )

                // 底栏样式与侧栏样式各自独立：平板可以「左侧面板侧栏 + iOS 底栏」这类组合，
                // 窄屏只用到底栏样式（侧栏不生效）。
            },
            AppCardRow(R.string.bottom_bar_style) {
                val bottomBarStyles = listOf(
                    stringResource(R.string.bottom_bar_style_hyperos),
                    stringResource(R.string.bottom_bar_style_ios)
                )
                val bottomBarStyleValues = BottomBarStyle.entries

                GlassDropdownPreference(
                    title = stringResource(R.string.bottom_bar_style),
                    summary = stringResource(R.string.bottom_bar_style_summary),
                    items = bottomBarStyles,
                    selectedIndex = bottomBarStyleValues.indexOf(bottomBarStyle)
                        .takeIf { it >= 0 } ?: 0,
                    onSelectedIndexChange = {
                        onBottomBarStyleChange(bottomBarStyleValues[it])
                    },
                    backdrop = LocalPreferenceBackdrop.current,
                    renderInRootScaffold = true,
                )
            },
            AppCardRow(R.string.rail_style) {
                val railStyles = listOf(
                    stringResource(R.string.rail_style_none),
                    stringResource(R.string.rail_style_top),
                    stringResource(R.string.rail_style_left)
                )
                val railStyleValues = RailStyle.entries

                GlassDropdownPreference(
                    title = stringResource(R.string.rail_style),
                    summary = stringResource(R.string.rail_style_summary),
                    items = railStyles,
                    selectedIndex = railStyleValues.indexOf(railStyle)
                        .takeIf { it >= 0 } ?: 0,
                    onSelectedIndexChange = {
                        onRailStyleChange(railStyleValues[it])
                    },
                    backdrop = LocalPreferenceBackdrop.current,
                    renderInRootScaffold = true,
                )
            },
            AppCardRow(R.string.blur_enabled) {
                SwitchPreference(
                    title = stringResource(R.string.blur_enabled),
                    summary = stringResource(R.string.blur_enabled_summary),
                    checked = isBlurEnabled,
                    onCheckedChange = onBlurEnabledChange
                )
            },
            AppCardRow(R.string.hide_launcher_icon) {
                SwitchPreference(
                    title = stringResource(R.string.hide_launcher_icon),
                    summary = stringResource(R.string.hide_launcher_icon_summary),
                    checked = hideLauncherIcon,
                    onCheckedChange = onHideLauncherIconChange
                )
            },
        )

        appGroupedCardItems(
            title = R.string.settings_language,
            AppCardRow(R.string.settings_language) {
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

                GlassDropdownPreference(
                    title = stringResource(R.string.settings_language),
                    items = languageNames,
                    selectedIndex = langCurrentIndex,
                    onSelectedIndexChange = {
                        LocaleHelper.setLanguage(context, languageValues[it])
                        activity?.recreate()
                    },
                    backdrop = LocalPreferenceBackdrop.current,
                    renderInRootScaffold = true,
                )
            },
        )

        appGroupedCardItems(
            title = R.string.settings_update,
            AppCardRow(R.string.check_update_on_launch) {
                SwitchPreference(
                    title = stringResource(R.string.check_update_on_launch),
                    summary = stringResource(R.string.check_update_on_launch_summary),
                    checked = checkUpdateOnLaunch,
                    onCheckedChange = onCheckUpdateOnLaunchChange,
                )
            },
            AppCardRow(R.string.check_update) {
                AppArrowPreference(
                    title = stringResource(R.string.check_update),
                    summary = if (isCheckingUpdate) {
                        stringResource(R.string.check_update_checking)
                    } else {
                        stringResource(R.string.check_update_summary, appVersion)
                    },
                    onClick = onCheckUpdate,
                )
            },
            AppCardRow(R.string.current_changelog) {
                AppArrowPreference(
                    title = stringResource(R.string.current_changelog),
                    onClick = { showChangelog = true },
                )
                if (showChangelog) {
                    val notes = remember(context, appVersion) {
                        try {
                            UpdateChecker.currentChangelog(context, appVersion)
                        } catch (_: java.io.IOException) {
                            ""
                        }
                    }
                    CurrentChangelogDialog(appVersion, notes) { showChangelog = false }
                }
            },
        )

        appGroupedCardItems(
            title = R.string.settings_data,
            AppCardRow(R.string.export_settings) {
                AppArrowPreference(
                    title = stringResource(R.string.export_settings),
                    summary = stringResource(R.string.export_settings_summary),
                    onClick = {
                        exportLauncher.launch("MiuixGuiTemplate_settings.json")
                    }
                )
            },
            AppCardRow(R.string.import_settings) {
                AppArrowPreference(
                    title = stringResource(R.string.import_settings),
                    summary = stringResource(R.string.import_settings_summary),
                    onClick = { importLauncher.launch("application/json") }
                )
            },
        )

        item(key = "copyright") {
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

/** 设备类型对应的显示文案资源。 */
private fun deviceTypeLabelRes(type: DeviceType): Int = when (type) {
    DeviceType.PHONE -> R.string.device_type_phone
    DeviceType.PAD -> R.string.device_type_pad
    DeviceType.FOLD -> R.string.device_type_fold
}
