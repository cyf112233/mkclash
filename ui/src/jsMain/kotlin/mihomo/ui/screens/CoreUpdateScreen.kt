package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihomo.core.AppStore
import mihomo.ui.JobCard
import mihomo.ui.GroupDivider
import mihomo.ui.MiuixPage
import mihomo.ui.TopSpacer
import mihomo.ui.UiPrefs
import mihomo.ui.group
import mihomo.ui.shortenHost
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val coreVariants = listOf("arm64-v8", "armv7", "amd64", "386")

/** Human readable label for the source that resolved the latest version. */
private fun sourceLabel(source: String): String = when (source) {
    "github-api" -> "GitHub API"
    "github-redirect" -> "GitHub Release 跳转（API 不可用时的回退）"
    "jsdelivr" -> "jsDelivr（GitHub 不可达时的回退）"
    "atom" -> "Release Atom 订阅源（最后回退）"
    else -> source
}

/**
 * Secondary page: check the latest mihomo release and install it through the module
 * control script (which validates the binary and rolls back on failure).
 */
@Composable
fun CoreUpdateScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var mirrorText by remember { mutableStateOf(UiPrefs.mirror) }
    var showInstall by remember { mutableStateOf(false) }
    var manualVersion by remember { mutableStateOf("") }
    var showManualInstall by remember { mutableStateOf(false) }
    val info = AppStore.coreUpdate
    val latest = info?.latest.orEmpty()
    val variantIndex = coreVariants.indexOf(UiPrefs.coreVariant).coerceAtLeast(0)
    val manualValid = manualVersion.trim().matches(Regex("""v?[0-9]+\.[0-9]+\.[0-9]+"""))

    /**
     * 加速前缀只在真正发起网络请求时落盘（`UiPrefs.setMirror` 会写 localStorage，
     * 逐键落盘既没必要也拖慢输入），返回去掉首尾空格后的值。
     */
    fun commitMirror(): String {
        val value = mirrorText.trim()
        UiPrefs.setMirror(value)
        return value
    }

    LaunchedEffect(Unit) {
        AppStore.refreshBinaryInfo()
        AppStore.checkCoreUpdate(UiPrefs.mirror, UiPrefs.coreVariant)
    }

    MiuixPage(
        title = "内核更新",
        modifier = Modifier.imePadding(),
        subtitle = if (AppStore.updateRunning) "正在安装…" else "MetaCubeX/mihomo 官方预编译",
        onBack = onBack,
        ownInsets = true,
        bottomPadding = 0.dp,
        snackbarHostState = snackbarHostState,
        actions = {
            if (AppStore.busy || AppStore.updateRunning) {
                InfiniteProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MiuixTheme.colorScheme.primary,
                    size = 20.dp,
                )
            } else {
                IconButton(
                    onClick = {
                        val mirror = commitMirror()
                        scope.launch { AppStore.checkCoreUpdate(mirror, UiPrefs.coreVariant) }
                    },
                ) {
                    Icon(imageVector = MiuixIcons.Refresh, contentDescription = "检查更新")
                }
            }
        },
        overlays = {
            OverlayDialog(
                show = showInstall,
                title = "下载并安装内核？",
                summary = "将下载 mihomo $latest（${UiPrefs.coreVariant}）并替换当前内核二进制，" +
                    "随后自动重启内核。安装脚本会先校验新二进制，失败时自动回滚到旧内核。",
                onDismissRequest = { showInstall = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showInstall = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "下载并安装",
                        onClick = {
                            showInstall = false
                            val mirror = commitMirror()
                            scope.launch {
                                val ok = AppStore.installCoreUpdate(latest, UiPrefs.coreVariant, mirror)
                                snackbarHostState.showSnackbar(
                                    if (ok) "内核已更新" else "安装失败，详见下方脚本输出",
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            OverlayDialog(
                show = showManualInstall,
                title = "安装 ${manualVersion.trim()}？",
                summary = "将按 ${UiPrefs.coreVariant} 架构下载 mihomo ${manualVersion.trim()}，" +
                    "校验通过后替换当前内核并重启；启动失败会自动回滚。",
                onDismissRequest = { showManualInstall = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showManualInstall = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "下载并安装",
                        onClick = {
                            val version = manualVersion.trim()
                            showManualInstall = false
                            val mirror = commitMirror()
                            scope.launch {
                                val ok = AppStore.installCoreUpdate(version, UiPrefs.coreVariant, mirror)
                                snackbarHostState.showSnackbar(
                                    if (ok) "内核已更新" else "安装失败，详见下方脚本输出",
                                )
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
        item { JobCard() }

        group("当前内核") {
            BasicComponent(
                title = "内核版本",
                summary = AppStore.coreVersion.ifEmpty { "未获取到版本，内核可能未运行" },
                startAction = { Icon(imageVector = MiuixIcons.Info, contentDescription = null) },
            )
            if (AppStore.coreBinaryInfo.isNotEmpty()) {
                GroupDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = "二进制信息", style = MiuixTheme.textStyles.subtitle)
                    Text(
                        text = AppStore.coreBinaryInfo,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }

        group("检查更新") {
            BasicComponent(
                title = "更新状态",
                summary = when {
                    AppStore.busy -> "正在检查最新版本…"
                    info == null -> "尚未检查"
                    latest.isEmpty() -> AppStore.lastMessage.ifEmpty { "没有获取到可用版本" }
                    info.hasUpdate -> "可更新到 $latest"
                    else -> "已是最新版本"
                },
                startAction = { Icon(imageVector = MiuixIcons.Update, contentDescription = null) },
            )

            if (info != null && latest.isNotEmpty()) {
                GroupDivider()
                BasicComponent(
                    title = "当前版本",
                    endActions = {
                        Text(
                            text = info.current.ifEmpty { "-" },
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    },
                )
                GroupDivider()
                BasicComponent(
                    title = "最新版本",
                    endActions = {
                        Text(
                            text = latest,
                            color = MiuixTheme.colorScheme.primary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    },
                )
                if (info.asset.isNotEmpty()) {
                    GroupDivider()
                    BasicComponent(
                        title = "架构文件",
                        endActions = {
                            Text(
                                text = info.asset,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        },
                    )
                }
                if (info.size.isNotEmpty()) {
                    GroupDivider()
                    BasicComponent(
                        title = "文件大小",
                        endActions = {
                            Text(
                                text = info.size,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        },
                    )
                }
                if (info.url.isNotEmpty()) {
                    GroupDivider()
                    BasicComponent(
                        title = "下载地址",
                        summary = shortenHost(info.url, 70),
                    )
                }
                if (info.source.isNotEmpty()) {
                    GroupDivider()
                    BasicComponent(
                        title = "版本来源",
                        summary = sourceLabel(info.source),
                    )
                }
            }

            if (latest.isEmpty() && !AppStore.busy) {
                GroupDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "未能自动获取版本",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.error,
                    )
                    Text(
                        text = AppStore.lastMessage.ifEmpty {
                            "常见原因：api.github.com 在当前网络不可达（部分网络会直接超时）。" +
                                "可填写加速前缀后重试，或在下方手动指定版本号安装。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }

            GroupDivider()
            TextField(
                value = mirrorText,
                onValueChange = { mirrorText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                label = "GitHub 加速前缀",
                singleLine = true,
            )
            Text(
                text = "留空则依次尝试：GitHub API → Release 跳转 → jsDelivr → Release 订阅源；" +
                    "若当前网络连 github.com 都不可达，请填写形如 https://ghfast.top 的加速前缀。" +
                    "检查更新与下载内核都会使用该前缀。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )

            GroupDivider()
            OverlayDropdownPreference(
                title = "内核架构",
                summary = "需要与设备 ABI 匹配，选错可能导致内核无法启动",
                items = coreVariants,
                selectedIndex = variantIndex,
                enabled = !AppStore.updateRunning,
                onSelectedIndexChange = { index ->
                    UiPrefs.setCoreVariant(coreVariants[index])
                    scope.launch { snackbarHostState.showSnackbar("已选择 ${coreVariants[index]}") }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Button(
                    onClick = {
                        val mirror = commitMirror()
                        scope.launch { AppStore.checkCoreUpdate(mirror, UiPrefs.coreVariant) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !AppStore.busy && !AppStore.updateRunning,
                ) {
                    Icon(imageVector = MiuixIcons.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("检查最新版本")
                }
            }
        }

        group("手动指定版本") {
            TextField(
                value = manualVersion,
                onValueChange = { manualVersion = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                label = "版本号，例如 v1.19.32",
                singleLine = true,
            )
            Text(
                text = if (manualVersion.isNotEmpty() && !manualValid) {
                    "版本号格式不正确，应形如 v1.19.32 或 1.19.32"
                } else {
                    "自动检查失败（api.github.com 被墙、返回限流等）时，可以直接填版本号安装：" +
                        "脚本会按「加速前缀 + 架构 + 版本号」拼出 GitHub Release 地址下载。"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = if (manualVersion.isNotEmpty() && !manualValid) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onBackgroundVariant
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Button(
                    onClick = { showManualInstall = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = manualValid && !AppStore.updateRunning && !AppStore.busy,
                ) {
                    Icon(imageVector = MiuixIcons.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("安装 ${manualVersion.trim().ifEmpty { "指定版本" }}")
                }
            }
        }

        group("安装") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "将从 MetaCubeX/mihomo 的官方 Release 下载 ${UiPrefs.coreVariant} 架构的内核二进制，" +
                        "替换 /data/adb/mihomo/bin/mihomo 后自动重启内核。安装脚本会先校验新二进制，" +
                        "失败时自动回滚到旧内核。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Button(
                    onClick = {
                        if (latest.isEmpty()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("请先点击「检查最新版本」获取可安装的版本")
                            }
                        } else {
                            showInstall = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !AppStore.updateRunning && !AppStore.busy,
                ) {
                    Icon(imageVector = MiuixIcons.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("下载并安装 ${latest.ifEmpty { "最新版" }}")
                }
                if (AppStore.updateRunning) {
                    LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (AppStore.updateOutput.isNotEmpty()) {
            item {
                Card(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(16.dp),
                    ) {
                        Text(text = "脚本输出", style = MiuixTheme.textStyles.subtitle)
                        Text(
                            text = AppStore.updateOutput.takeLast(4000),
                            style = MiuixTheme.textStyles.footnote2,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        item { TopSpacer() }
    }
}
