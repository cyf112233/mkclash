package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.core.Mihomo
import mihomo.ui.GroupDivider
import mihomo.ui.MiuixPage
import mihomo.core.Stream
import mihomo.ui.Screen
import mihomo.ui.TopSpacer
import mihomo.ui.UiPrefs
import mihomo.ui.group
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Log levels accepted by mihomo's `PATCH /configs` `log-level` field. */
private val logLevels = listOf("trace", "debug", "info", "warning", "error", "silent")

/** Kernel build variants published by the mihomo release page. */
private val coreVariants = listOf("arm64-v8", "armv7", "amd64", "386")

private val themeModes = listOf("system", "light", "dark")

@Composable
fun SettingsScreen(bottomPadding: Dp, onNavigate: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLogLevel by remember { mutableStateOf(false) }
    var showRestart by remember { mutableStateOf(false) }

    val logLevelIndex = logLevels.indexOf(AppStore.logLevel).coerceAtLeast(0)
    val themeIndex = themeModes.indexOf(UiPrefs.themeMode).coerceAtLeast(0)
    val variantIndex = coreVariants.indexOf(UiPrefs.coreVariant).coerceAtLeast(0)

    MiuixPage(
        title = "设置",
        modifier = Modifier.imePadding(),
        largeTitle = true,
        bottomPadding = bottomPadding,
        snackbarHostState = snackbarHostState,
        overlays = {
            LogLevelDialog(
                show = showLogLevel,
                selectedIndex = logLevelIndex,
                onDismiss = { showLogLevel = false },
                onSelect = { index ->
                    showLogLevel = false
                    scope.launch {
                        AppStore.setLogLevel(logLevels[index])
                    }
                },
            )
            OverlayDialog(
                show = showRestart,
                title = "重启内核？",
                summary = "所有连接会被断开，重启期间流量不经过代理。",
                onDismissRequest = { showRestart = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showRestart = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "重启",
                        onClick = {
                            showRestart = false
                            scope.launch {
                                AppStore.coreAction("restart")
                                snackbarHostState.showSnackbar(AppStore.lastMessage.ifEmpty { "已发送重启请求" })
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    ) {
        item { TopSpacer() }

        // -- 内核 ------------------------------------------------------------
        group("内核") {
            BasicComponent(
                title = "运行状态",
                endActions = {
                    Text(
                        text = if (AppStore.apiOnline) "运行中" else "已停止",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "内核版本",
                endActions = {
                    Text(
                        text = AppStore.coreVersion.ifEmpty { "-" },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "API 地址",
                endActions = {
                    Text(
                        text = "${Mihomo.host}:${Mihomo.port}",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "后端",
                endActions = {
                    Text(
                        text = AppStore.backendLabel.ifEmpty { "-" },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            GroupDivider()
            BasicComponent(
                title = "实时通道",
                summary = "流量 / 连接 / 内存由内核主动推送",
                endActions = {
                    Text(
                        text = Stream.statusLabel,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
            GroupDivider()
            SwitchPreference(
                title = "TUN 模式",
                summary = "由内核接管全部流量，需要配置中已启用 tun",
                checked = AppStore.tunEnabled,
                enabled = AppStore.apiOnline,
                onCheckedChange = { scope.launch { AppStore.setTun(it) } },
            )
            GroupDivider()
            ArrowPreference(
                title = "日志级别",
                summary = "写入日志的详细程度，越高越啰嗦",
                startAction = { Icon(imageVector = MiuixIcons.Tune, contentDescription = null) },
                endActions = {
                    Text(
                        text = logLevelLabel(AppStore.logLevel),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                onClick = { showLogLevel = true },
            )
            GroupDivider()
            ArrowPreference(
                title = "重启内核",
                summary = "重新拉起 mihomo 进程，期间连接会断开",
                startAction = { Icon(imageVector = MiuixIcons.Refresh, contentDescription = null) },
                onClick = { showRestart = true },
            )
            GroupDivider()
            ArrowPreference(
                title = "更新 mihomo 内核",
                summary = "下载官方预编译内核并替换",
                startAction = { Icon(imageVector = MiuixIcons.Update, contentDescription = null) },
                onClick = { onNavigate(Screen.CoreUpdate) },
            )
        }

        // -- 开机自启 / 控制器 ------------------------------------------------
        group("开机自启") {
            SwitchPreference(
                title = "开机自动启动代理",
                summary = if (AppStore.autostart) {
                    "开机后自动拉起内核，并由看门狗守护（关闭后只手动启动）"
                } else {
                    "已关闭：开机不会启动内核，可在首页手动启动"
                },
                checked = AppStore.autostart,
                enabled = AppStore.ksuAvailable,
                onCheckedChange = { enabled ->
                    scope.launch {
                        AppStore.setAutostart(enabled)
                        snackbarHostState.showSnackbar(AppStore.lastMessage)
                    }
                },
            )
        }

        group("控制器") {
            ArrowPreference(
                title = "连接与端口",
                summary = "当前：${AppStore.connectionLabel()} · ${Mihomo.host}:${Mihomo.port}",
                startAction = { Icon(imageVector = MiuixIcons.Link, contentDescription = null) },
                endActions = {
                    Text(
                        text = if (AppStore.apiOnline) "已连接" else "未连接",
                        color = if (AppStore.apiOnline) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                    )
                },
                onClick = { onNavigate(Screen.Connection) },
            )
        }

        // -- 配置 ------------------------------------------------------------
        group("配置") {
            ArrowPreference(
                title = "编辑 config.yaml",
                summary = "校验通过后热重载",
                startAction = { Icon(imageVector = MiuixIcons.File, contentDescription = null) },
                onClick = { onNavigate(Screen.ConfigEditor) },
            )
            GroupDivider()
            ArrowPreference(
                title = "订阅管理",
                summary = "${AppStore.subscriptions.size} 个订阅 · 底部「订阅」页",
                startAction = { Icon(imageVector = MiuixIcons.Link, contentDescription = null) },
                onClick = { AppStore.activeTab = 2 },
            )
            GroupDivider()
            ArrowPreference(
                title = "规则列表",
                summary = "${AppStore.rules.size} 条规则",
                startAction = { Icon(imageVector = MiuixIcons.ListView, contentDescription = null) },
                onClick = { onNavigate(Screen.Rules) },
            )
            GroupDivider()
            ArrowPreference(
                title = "重载配置",
                summary = "读取磁盘上的 config.yaml",
                startAction = { Icon(imageVector = MiuixIcons.Refresh, contentDescription = null) },
                onClick = {
                    scope.launch {
                        AppStore.reloadConfig()
                        snackbarHostState.showSnackbar(AppStore.lastMessage.ifEmpty { "已重载" })
                    }
                },
            )
            GroupDivider()
            SwitchPreference(
                title = "允许局域网连接",
                summary = "同一局域网内的设备可经由本机代理",
                checked = AppStore.allowLan,
                enabled = AppStore.apiOnline,
                onCheckedChange = { scope.launch { AppStore.setAllowLan(it) } },
            )
        }

        // -- 界面 ------------------------------------------------------------
        group("界面") {
            OverlayDropdownPreference(
                title = "主题",
                summary = "WebUI 自身的明暗外观",
                items = listOf("跟随系统", "浅色", "深色"),
                selectedIndex = themeIndex,
                onSelectedIndexChange = { index -> UiPrefs.setTheme(themeModes[index]) },
            )
            GroupDivider()
            SliderPreference(
                title = "刷新间隔",
                summary = "秒",
                value = UiPrefs.pollSeconds.toFloat(),
                valueText = "${UiPrefs.pollSeconds} s",
                valueRange = 1f..30f,
                steps = 28,
                onValueChange = { UiPrefs.setPollSeconds(it.toInt()) },
            )
            GroupDivider()
            SwitchPreference(
                title = "日志自动滚动",
                summary = "打开日志页时自动跳到最新一行",
                checked = UiPrefs.autoScrollLog,
                onCheckedChange = UiPrefs::setAutoScrollLog,
            )
        }

        // -- 网络 ------------------------------------------------------------
        group("网络") {
            ArrowPreference(
                title = "GitHub 加速前缀",
                summary = UiPrefs.mirror.ifEmpty { "未设置（依次尝试直连与内置加速器）" },
                startAction = { Icon(imageVector = MiuixIcons.Update, contentDescription = null) },
                endActions = {
                    Text(
                        text = "在「更新内核」里修改",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                },
                onClick = { onNavigate(Screen.CoreUpdate) },
            )
            GroupDivider()
            TextField(
                value = UiPrefs.testUrl,
                onValueChange = UiPrefs::setTestUrl,
                label = "延迟测速地址",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            GroupDivider()
            OverlayDropdownPreference(
                title = "内核架构",
                summary = "下载内核时使用的 ABI，默认 arm64-v8",
                items = coreVariants,
                selectedIndex = variantIndex,
                onSelectedIndexChange = { index -> UiPrefs.setCoreVariant(coreVariants[index]) },
            )
        }

        // -- 其他 ------------------------------------------------------------
        group("其他") {
            ArrowPreference(
                title = "关于",
                summary = "模块信息与使用说明",
                startAction = { Icon(imageVector = MiuixIcons.Info, contentDescription = null) },
                onClick = { onNavigate(Screen.About) },
            )
            GroupDivider()
            SwitchPreference(
                title = "显示隐藏的代理组",
                summary = "展示配置里被标记为 hidden 的代理组",
                checked = UiPrefs.showHidden,
                onCheckedChange = UiPrefs::setShowHidden,
            )
        }

        item { TopSpacer() }
    }
}

/** Radio list dialog used by the「日志级别」row. */
@Composable
private fun LogLevelDialog(
    show: Boolean,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "日志级别",
        summary = "修改后立即通过 PATCH /configs 生效",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            logLevels.forEachIndexed { index, level ->
                RadioButtonPreference(
                    title = logLevelLabel(level),
                    summary = level,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private fun logLevelLabel(level: String): String = when (level) {
    "trace" -> "trace（全部）"
    "debug" -> "debug（调试）"
    "info" -> "info（信息）"
    "warning" -> "warning（警告）"
    "error" -> "error（错误）"
    "silent" -> "silent（静默）"
    else -> level
}
