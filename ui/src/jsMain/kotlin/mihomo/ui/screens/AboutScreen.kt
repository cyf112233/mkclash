package mihomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mihomo.core.AppStore
import mihomo.core.Ksu
import mihomo.core.Mihomo
import mihomo.core.openExternal
import mihomo.core.Paths
import mihomo.ui.GroupDivider
import mihomo.ui.MiuixPage
import mihomo.ui.TopSpacer
import mihomo.ui.UiPrefs
import mihomo.ui.group
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val MIHOMO_URL = "https://github.com/MetaCubeX/mihomo"
private const val KERNELSU_URL = "https://kernelsu.org/"

/** Opens a link in a new tab (delegates to the shared driver in Platform.kt). */
private fun openUrl(url: String) = openExternal(url)

/** Secondary page: module / environment information and usage notes. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    MiuixPage(
        title = "关于",
        onBack = onBack,
        ownInsets = true,
        bottomPadding = 0.dp,
    ) {
        item { TopSpacer() }

        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "mkclash",
                        style = MiuixTheme.textStyles.title2,
                    )
                    Text(
                        text = if (AppStore.coreVersion.isNotEmpty()) {
                            "内核 ${AppStore.coreVersion} · ${AppStore.backendLabel.ifEmpty { "未知后端" }}"
                        } else {
                            "内核未运行 · ${AppStore.backendLabel.ifEmpty { "未知后端" }}"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Text(
                        text = "mkclash 把 MetaCubeX/mihomo 内核打包成 KernelSU / Magisk 模块，" +
                            "并提供这个 WebUI 用于查看状态、切换节点、管理订阅与更新内核。" +
                            "内核由 MetaCubeX/mihomo 提供，本项目只做模块封装与界面。",
                        style = MiuixTheme.textStyles.paragraph,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }

        group("环境") {
            InfoRow("KernelSU 接口", if (AppStore.ksuAvailable) "可用" else "不可用")
            GroupDivider()
            InfoRow("HTTP 后端", AppStore.backendLabel.ifEmpty { "-" })
            GroupDivider()
            InfoRow("API 地址", "${Mihomo.host}:${Mihomo.port}")
            GroupDivider()
            InfoRow("模块 id", Ksu.moduleId.ifEmpty { "-" })
            GroupDivider()
            InfoRow("界面主题", themeLabel(UiPrefs.themeMode))
            GroupDivider()
            InfoRow("内核架构", UiPrefs.coreVariant.ifEmpty { "-" })
        }

        group("使用说明") {
            BasicComponent(
                title = "核心随模块开机自启",
                summary = "由 KernelSU 模块脚本在开机时拉起 mihomo，也可以在首页手动启动 / 停止。",
                startAction = { Icon(imageVector = MiuixIcons.Info, contentDescription = null) },
            )
            GroupDivider()
            BasicComponent(
                title = "配置与订阅",
                summary = "订阅更新与手动编辑最终都会写入配置文件；保存前会先做 mihomo -t 校验。",
                startAction = { Icon(imageVector = MiuixIcons.Settings, contentDescription = null) },
            )
            GroupDivider()
            PathRow("配置文件", Paths.CONFIG)
            GroupDivider()
            PathRow("控制脚本", Paths.CTL)
            GroupDivider()
            PathRow("运行日志", Paths.LOG)
            GroupDivider()
            PathRow("内核二进制", "${Paths.ROOT}/bin/mihomo")
        }

        group("链接") {
            ArrowPreference(
                title = "mihomo 项目主页",
                summary = MIHOMO_URL,
                startAction = { Icon(imageVector = MiuixIcons.WorldClock, contentDescription = null) },
                onClick = { openUrl(MIHOMO_URL) },
            )
            GroupDivider()
            ArrowPreference(
                title = "KernelSU 文档",
                summary = KERNELSU_URL,
                startAction = { Icon(imageVector = MiuixIcons.Help, contentDescription = null) },
                onClick = { openUrl(KERNELSU_URL) },
            )
            GroupDivider()
            ArrowPreference(
                title = "内核 Release 列表",
                summary = "查看所有可用版本与架构",
                startAction = { Icon(imageVector = MiuixIcons.Link, contentDescription = null) },
                onClick = { openUrl("$MIHOMO_URL/releases") },
            )
        }

        item {
            Text(
                text = "本页面只读取模块状态，不会修改任何文件。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            )
        }

        item { TopSpacer() }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** A read-only row whose value is a long filesystem path (rendered small, full width). */
@Composable
private fun PathRow(title: String, path: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MiuixTheme.textStyles.main)
        Text(
            text = path,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

private fun themeLabel(mode: String): String = when (mode) {
    "dark" -> "深色"
    "light" -> "浅色"
    else -> "跟随系统"
}
