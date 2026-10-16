package mihomo.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mihomo.ui.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/** Data class describing the outcome of a "check for core update" run. */
data class CoreUpdateInfo(
    val current: String = "",
    val latest: String = "",
    val asset: String = "",
    val url: String = "",
    val size: String = "",
    val source: String = "",
) {
    val hasUpdate: Boolean get() = latest.isNotEmpty() && latest != current
}

/**
 * Single source of truth for the whole UI.
 *
 * The app is a tiny, single-activity-ish WebView page without a lifecycle owner, so a
 * plain object holding Compose state is both simpler and more predictable than a
 * ViewModel that would never be cleared.
 */
object AppStore {
    // -- environment ---------------------------------------------------------
    var ksuAvailable by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var backendLabel by mutableStateOf("")
    var notice by mutableStateOf("")
    var lastMessage by mutableStateOf("")
    var busy by mutableStateOf(false)
    var autostart by mutableStateOf(true)
    /** Last failure of the KernelSU `exec` bridge (empty when it works). */
    var execError by mutableStateOf("")

    // -- controller connection ----------------------------------------------
    /** Where the current address came from: ksu / api.json / origin / manual / default. */
    var connectionSource by mutableStateOf("")
    var manualPreferred by mutableStateOf(Prefs.getBool("api.manualPreferred", false))
    var manualHost by mutableStateOf(Prefs.getString("api.host", ""))
    var manualPort by mutableStateOf(Prefs.getInt("api.port", 9090))
    var manualSecret by mutableStateOf(Prefs.getString("api.secret", ""))
    /** Currently visible main tab (0…4), or -1 while a secondary page is open. */
    var activeTab by mutableStateOf(0)
    /** Which main tab to restore when a secondary page is closed. */
    var lastMainTab by mutableStateOf(0)
    /** Who answers on the controller port: `ours` / `other` / `none` / "" (unknown). */
    var controllerOwner by mutableStateOf("")

    // -- core ----------------------------------------------------------------
    /** True while a core start/stop/restart job runs: the API is legitimately down. */
    var transitioning by mutableStateOf(false)
    var apiOnline by mutableStateOf(false)
    var coreRunning by mutableStateOf(false)
    var coreVersion by mutableStateOf("")
    var coreBinaryInfo by mutableStateOf("")
    var mode by mutableStateOf("rule")
    var logLevel by mutableStateOf("info")
    var tunEnabled by mutableStateOf(false)
    var mixedPort by mutableStateOf(0)
    var allowLan by mutableStateOf(false)
    var memoryInuse by mutableStateOf(0L)

    // -- traffic -------------------------------------------------------------
    var uploadTotal by mutableStateOf(0L)
    var downloadTotal by mutableStateOf(0L)
    var uploadSpeed by mutableStateOf(0L)
    var downloadSpeed by mutableStateOf(0L)
    var activeConnections by mutableStateOf(0)

    // -- collections ---------------------------------------------------------
    var proxies by mutableStateOf<Map<String, ProxyInfo>>(emptyMap())
    var proxyProviders by mutableStateOf<Map<String, ProxyProviderInfo>>(emptyMap())
    var rules by mutableStateOf<List<RuleInfo>>(emptyList())
    var connections by mutableStateOf<List<ConnInfo>>(emptyList())
    var logs by mutableStateOf<List<String>>(emptyList())
    var configText by mutableStateOf("")
    var configLoaded by mutableStateOf(false)
    var subscriptions by mutableStateOf<List<Subscription>>(emptyList())
    var coreUpdate by mutableStateOf<CoreUpdateInfo?>(null)
    var updateRunning by mutableStateOf(false)
    var updateOutput by mutableStateOf("")
    var ruleSearch by mutableStateOf("")

    private var lastTrafficAt = 0.0
    private var lastUploadTotal = 0L
    private var lastDownloadTotal = 0L

    private fun nowMs(): Double = js("Date.now()") as Double

    suspend fun bootstrap(scope: CoroutineScope) {
        ksuAvailable = Ksu.available
        // Resolve the controller *before* choosing a transport: a same-origin panel
        // (mihomo `external-ui`) can talk to the API with plain fetch.
        resolveConnection()
        Http.detect()
        backendLabel = Http.backend.label
        if (ksuAvailable) {
            if (connectionSource != "ksu") {
                // The bridge exists but the script did not answer: the panel still works,
                // module control does not — say so instead of showing a bare 401.
                notice = "KernelSU 接口可用，但执行模块脚本失败：${execError.ifEmpty { "未知错误" }}。" +
                    "已改用「${connectionLabel()}」连接内核；启停内核、配置、订阅、内核更新可能不可用。"
            }
        } else {
            notice = "未检测到 KernelSU WebUI 接口：面板功能（状态/代理/规则/连接/日志）可用，" +
                "但启停内核、编辑配置、订阅、更新内核需要在 KernelSU / WebUI X 宿主里打开本页面。"
        }
        refreshAll()
        startStreaming()
        ready = true
    }

    /**
     * Auto-connect, module first:
     *   1. `ksu.exec` → module control script (`state`, a single round-trip)
     *   2. same-origin `api.json` written by the module (works without the bridge)
     *   3. the page's own origin when mihomo itself serves the panel
     *   4. a manually saved address, but only when the user asked for it
     */
    private suspend fun resolveConnection() {
        if (manualPreferred && manualHost.isNotEmpty() && manualPort > 0) {
            Mihomo.host = manualHost
            Mihomo.port = manualPort
            Mihomo.secret = manualSecret
            connectionSource = "manual"
            return
        }
        if (ksuAvailable && loadState()) {
            connectionSource = "ksu"
            return
        }
        if (loadFromApiJson()) {
            connectionSource = "api.json"
            return
        }
        val origin = pageOrigin()
        if (origin.startsWith("http")) {
            val host = js("(typeof location==='undefined')?'':location.hostname") as String
            val port = (js("(typeof location==='undefined')?0:Number(location.port)") as Number).toInt()
            if (host.isNotEmpty() && port > 0) {
                Mihomo.host = host
                Mihomo.port = port
                connectionSource = "origin"
                return
            }
        }
        Mihomo.host = manualHost.ifEmpty { "127.0.0.1" }
        Mihomo.port = if (manualPort > 0) manualPort else 9090
        Mihomo.secret = manualSecret
        connectionSource = "default"
    }

    /**
     * One `state` call for everything the shell needs from the module: controller
     * coordinates, autostart flag, controller ownership, version and subscription UA.
     * Batching matters because each `exec` blocks the WebView thread on the host side.
     */
    private suspend fun loadState(): Boolean {
        val result = Shell.ctl("state")
        if (!result.ok) {
            execError = result.stderr.ifEmpty { result.stdout.ifEmpty { "errno=${result.errno}" } }
            return false
        }
        execError = ""
        val values = Shell.parseKeyValues(result.stdout)
        values["host"]?.takeIf { it.isNotEmpty() }?.let { Mihomo.host = it }
        values["port"]?.toIntOrNull()?.let { if (it > 0) Mihomo.port = it }
        values["secret"]?.let { Mihomo.secret = it }
        values["autostart"]?.let { autostart = it == "1" }
        values["owner"]?.let { controllerOwner = it }
        return values.containsKey("secret")
    }

    /** Reads controller host/port/secret from the module control script. */
    private suspend fun loadApiInfo(): Boolean {
        val result = Shell.ctl("api-info")
        if (!result.ok) {
            execError = result.stderr.ifEmpty { result.stdout.ifEmpty { "errno=${result.errno}" } }
            return false
        }
        execError = ""
        val values = Shell.parseKeyValues(result.stdout)
        values["host"]?.takeIf { it.isNotEmpty() }?.let { Mihomo.host = it }
        values["port"]?.toIntOrNull()?.let { if (it > 0) Mihomo.port = it }
        values["secret"]?.let { Mihomo.secret = it }
        return Mihomo.secret.isNotEmpty() || values.containsKey("secret")
    }

    /** Reads the `api.json` the module writes next to the WebUI (same origin). */
    private suspend fun loadFromApiJson(): Boolean {
        val text = fetchRelativeText("api.json") ?: return false
        val info = runCatching { mihomoJson.decodeFromString(ApiJson.serializer(), text) }.getOrNull()
            ?: return false
        if (info.port <= 0) return false
        Mihomo.host = info.host.ifEmpty { "127.0.0.1" }
        Mihomo.port = info.port
        Mihomo.secret = info.secret
        return true
    }

    /** Saves a manual controller address and makes it the preferred one. */
    suspend fun applyManualConnection(host: String, port: Int, secret: String): Boolean {
        manualHost = host.trim()
        manualPort = port
        manualSecret = secret.trim()
        Prefs.putString("api.host", manualHost)
        Prefs.putInt("api.port", manualPort)
        Prefs.putString("api.secret", manualSecret)
        Mihomo.host = manualHost
        Mihomo.port = manualPort
        Mihomo.secret = manualSecret
        connectionSource = "manual"
        Http.reset()
        Http.detect()
        backendLabel = Http.backend.label
        val ok = testConnection()
        // Only a working address may take over from auto-discovery; otherwise one typo would
        // hijack every future start of the panel with no visible way back.
        manualPreferred = ok && manualHost.isNotEmpty() && manualPort > 0
        Prefs.putBool("api.manualPreferred", manualPreferred)
        if (!manualPreferred) {
            lastMessage = "连接失败：${Mihomo.lastError.ifEmpty { "无法访问该地址" }}；已保留自动连接"
            useAutoConnection()
            return false
        }
        lastMessage = "已连接 ${Mihomo.host}:${Mihomo.port}"
        restartStreaming()
        refreshAll()
        return true
    }

    /** Drops the manual override and re-detects the module's own controller. */
    suspend fun useAutoConnection() {
        manualPreferred = false
        Prefs.putBool("api.manualPreferred", false)
        Mihomo.secret = ""
        resolveConnection()
        Http.reset()
        Http.detect()
        backendLabel = Http.backend.label
        // the live channels were opened for the old address: rebuild them
        restartStreaming()
        refreshAll()
        lastMessage = "已恢复自动连接（${connectionLabel()}）"
    }

    /** Re-runs auto detection (the dashboard's "重新检测" button). */
    suspend fun retryConnection() {
        resolveConnection()
        Http.reset()
        Http.detect()
        restartStreaming()
        backendLabel = Http.backend.label
        if (ksuAvailable) {
            refreshAutostart()
            refreshPortStatus()
        }
        refreshAll()
        lastMessage = if (apiOnline) "已连接（${connectionLabel()}）" else "仍然无法连接：${Mihomo.lastError}"
    }

    fun connectionLabel(): String = when (connectionSource) {
        "ksu" -> "模块脚本（KernelSU 桥）"
        "api.json" -> "模块 api.json（同源）"
        "origin" -> "页面同源（mihomo 托管）"
        "manual" -> "手动指定"
        else -> "默认参数"
    }

    /** One `/version` call; returns true when the controller answers with our secret. */
    suspend fun testConnection(): Boolean {
        val text = Mihomo.request("GET", "/version") ?: return false
        return text.contains("\"version\"")
    }

    suspend fun refreshAutostart() {
        if (!ksuAvailable) return
        val values = Shell.parseKeyValues(Shell.ctl("autostart").stdout)
        if (values.containsKey("autostart")) autostart = values["autostart"] == "1"
    }

    suspend fun setAutostart(enabled: Boolean): Boolean {
        if (!ksuAvailable) return false
        val result = Shell.ctl("autostart", if (enabled) "on" else "off")
        val values = Shell.parseKeyValues(result.stdout)
        autostart = values["autostart"] == "1"
        lastMessage = when {
            autostart != enabled -> result.stderr.trim().ifEmpty { "设置开机自启失败" }
            enabled -> "已开启开机自启"
            else -> "已关闭开机自启"
        }
        return autostart == enabled
    }

    /** Who currently answers on the controller port (`ours` / `other` / `none`). */
    suspend fun refreshPortStatus() {
        if (!ksuAvailable) return
        val values = Shell.parseKeyValues(Shell.ctl("port-status").stdout)
        controllerOwner = values["owner"] ?: ""
        values["port"]?.toIntOrNull()?.let { if (it > 0) Mihomo.port = it }
        values["host"]?.takeIf { it.isNotEmpty() }?.let { Mihomo.host = it }
    }

    /** Moves the controller to a free port when another proxy owns the configured one. */
    suspend fun autoSelectPort(): Boolean {
        val ok = runJob("port-auto")
        loadState()
        restartStreaming()
        refreshVersion()
        refreshConfigs()
        refreshPortStatus()
        return ok
    }

    /**
     * Runs a detached module job and waits for its result.
     *
     * The start call is one short exec (a few hundred ms); the work itself happens outside
     * the WebView's JS thread, so the panel keeps rendering. Progress and the final message
     * come from `job.json` through a plain same-origin fetch.
     */
    private suspend fun runJob(kind: String, vararg args: String): Boolean {
        if (!ksuAvailable) {
            lastMessage = "需要 KernelSU / WebUI X 宿主才能执行该操作"
            return false
        }
        if (Jobs.busy) {
            lastMessage = "已有任务在执行：${Jobs.label}"
            return false
        }
        busy = true
        yieldForUi()
        val started = Jobs.start(kind, *args)
        busy = false
        if (!started) {
            lastMessage = Jobs.lastResult
            return false
        }
        val coreJob = kind.startsWith("core-")
        if (coreJob) transitioning = true
        val info = Jobs.watch()
        val label = jobsLabelFor(kind)
        lastMessage = if (info.failed) {
            "${label}失败：${info.message.ifEmpty { "未知错误" }}"
        } else {
            "${label}完成${if (info.message.isEmpty()) "" else "：" + info.message}"
        }
        if (coreJob) {
            // stopping means staying down; starting/restarting means waiting for the API
            if (kind != "core-stop") waitForApi()
            transitioning = false
        }
        Jobs.lastResult = lastMessage
        return !info.failed
    }

    private fun jobsLabelFor(kind: String): String = when (kind) {
        "sub-update" -> "更新订阅"
        "core-start" -> "启动内核"
        "core-stop" -> "停止内核"
        "core-restart" -> "重启内核"
        "config-install" -> "写入配置"
        "core-update" -> "更新内核"
        "geo-update" -> "更新 Geo 数据"
        "port-auto" -> "切换控制端口"
        else -> "任务"
    }

    /**
     * Waits for the controller to answer again after a start/stop/restart, so the panel does
     * not flash "无法连接" for a few seconds while the core comes back up.
     */
    suspend fun waitForApi(timeoutMs: Long = 8000L): Boolean {
        val started = nowMs()
        while (nowMs() - started < timeoutMs) {
            val text = Mihomo.request("GET", "/version")
            if (text != null && Http.lastOk && text.contains("\"version\"")) {
                apiOnline = true
                coreRunning = true
                return true
            }
            delay(300)
        }
        return false
    }

    /** Applies one `/traffic` frame pushed by the core. */
    fun onTrafficFrame(sample: TrafficSample) {
        downloadSpeed = sample.down
        uploadSpeed = sample.up
        if (sample.downTotal > 0) downloadTotal = sample.downTotal
        if (sample.upTotal > 0) uploadTotal = sample.upTotal
        apiOnline = true
    }

    /** Applies one `/connections` frame pushed by the core. */
    fun onConnectionsFrame(response: ConnectionsResponse) {
        connections = response.connections
        activeConnections = response.connections.size
        downloadTotal = response.downloadTotal
        uploadTotal = response.uploadTotal
        if (response.memory > 0) memoryInuse = response.memory
        apiOnline = true
        coreRunning = true
    }

    /** Applies one `/memory` frame pushed by the core. */
    fun onMemoryFrame(sample: MemorySample) {
        if (sample.inuse > 0) memoryInuse = sample.inuse
    }

    /** Log lines pushed by the core (`/logs` WebSocket) — the log viewer watches these. */
    var liveLogs by mutableStateOf<List<String>>(emptyList())

    /** Opens the WebSocket channels to the current controller. */
    /** Rebuilds the live channels (after the controller address changed). */
    fun restartStreaming() {
        Stream.restart(
            onTraffic = { onTrafficFrame(it) },
            onConnections = { onConnectionsFrame(it) },
            onMemory = { onMemoryFrame(it) },
            onLog = { onLogFrame(it) },
            logLevel = logLevel.ifEmpty { "info" },
        )
    }

    /** Opens the WebSocket channels to the current controller. */
    fun startStreaming() {
        Stream.start(
            onTraffic = { onTrafficFrame(it) },
            onConnections = { onConnectionsFrame(it) },
            onMemory = { onMemoryFrame(it) },
            onLog = { onLogFrame(it) },
            logLevel = logLevel.ifEmpty { "info" },
        )
    }

    private fun onLogFrame(frame: LogFrame) {
        if (frame.payload.isEmpty()) return
        liveLogs = (liveLogs + frame.payload).takeLast(500)
    }

    suspend fun refreshAll() {
        // with the live channels up, /connections arrives by itself — a periodic reconcile
        // is still useful, but at a much lower rate
        refreshVersion()
        refreshConfigs()
        if (!Stream.connected) refreshConnections()
    }

    suspend fun refreshVersion() {
        val text = Mihomo.request("GET", "/version")
        if (text == null) {
            apiOnline = false
            coreRunning = false
            return
        }
        if (text.contains("Unauthorized")) {
            // the controller port is answered by something that does not know our secret:
            // usually another proxy (or an older instance) already bound it
            apiOnline = false
            coreRunning = true
            coreVersion = ""
            lastMessage = "控制器 ${Mihomo.host}:${Mihomo.port} 返回 401：端口被其它代理占用或 secret 不匹配。" +
                "到「设置 → 控制器」点一下「自动选择空闲端口」即可修复"
            return
        }
        val info = runCatching { mihomoJson.decodeFromString(VersionInfo.serializer(), text) }.getOrNull()
        if (info != null && info.version.isNotEmpty()) {
            apiOnline = true
            coreRunning = true
            coreVersion = info.version
        } else {
            apiOnline = false
            coreRunning = false
        }
    }

    suspend fun refreshConfigs() {
        val configs = Mihomo.getAs<ConfigsInfo>("/configs") ?: return
        mode = configs.mode.ifEmpty { "rule" }
        logLevel = configs.logLevel.ifEmpty { "info" }
        tunEnabled = configs.tun.enable
        mixedPort = if (configs.mixedPort > 0) configs.mixedPort else configs.port
        allowLan = configs.allowLan
    }

    suspend fun refreshConnections() {
        val response = Mihomo.getAs<ConnectionsResponse>("/connections") ?: return
        connections = response.connections
        activeConnections = response.connections.size
        downloadTotal = response.downloadTotal
        uploadTotal = response.uploadTotal
        memoryInuse = response.memory
        val stamp = nowMs()
        if (lastTrafficAt > 0.0) {
            val seconds = ((stamp - lastTrafficAt) / 1000.0).coerceAtLeast(0.001)
            downloadSpeed = (((response.downloadTotal - lastDownloadTotal) / seconds).toLong()).coerceAtLeast(0)
            uploadSpeed = (((response.uploadTotal - lastUploadTotal) / seconds).toLong()).coerceAtLeast(0)
        }
        lastTrafficAt = stamp
        lastDownloadTotal = response.downloadTotal
        lastUploadTotal = response.uploadTotal
    }

    suspend fun refreshProxies() {
        val response = Mihomo.getAs<ProxiesResponse>("/proxies") ?: return
        proxies = response.proxies
    }

    suspend fun refreshRules() {
        val response = Mihomo.getAs<RulesResponse>("/rules") ?: return
        rules = response.rules
    }

    /**
     * Refreshes the log view.
     *
     * A detached `log-tail` loop on the device mirrors the log into the served UI directory,
     * so this is a same-origin fetch — the exec bridge used to block the panel on every
     * refresh. Falls back to a direct tail when the mirror is not there yet (no fetch).
     */
    suspend fun refreshLogs(lines: Int = 300) {
        if (Http.backend == HttpBackend.FETCH) {
            val text = fetchRelativeText("log.txt")
            if (text != null) {
                logs = text.trimEnd().lines().takeLast(lines)
                return
            }
        }
        if (!ksuAvailable) return
        val result = Shell.exec("tail -n $lines ${Paths.LOG} 2>/dev/null")
        logs = if (result.stdout.isEmpty()) emptyList() else result.stdout.trimEnd().lines()
    }

    /** Starts/stops the device-side log mirror (one short exec each). */
    suspend fun setLogTail(running: Boolean) {
        if (!ksuAvailable) return
        Shell.ctl("log-tail", if (running) "start" else "stop")
    }

    suspend fun clearLog() {
        if (!ksuAvailable) return
        Shell.exec(": > ${Paths.LOG}")
        logs = emptyList()
        // the viewer prefers the live WebSocket lines: clearing only the file left them on screen
        liveLogs = emptyList()
    }

    suspend fun refreshBinaryInfo() {
        if (!ksuAvailable) return
        val result = Shell.ctl("core-info")
        coreBinaryInfo = result.stdout.trim()
    }

    /**
     * Core lifecycle.
     *
     * `restart` is a core-owned action (`POST /restart`: the core re-executes itself), so it
     * needs neither the shell nor a job — it is one API call and the WebSocket channels
     * reconnect on their own. `start`/`stop` cannot come from the API (the process is gone
     * or not yet there), so they stay detached jobs.
     */
    suspend fun coreAction(action: String): Boolean {
        if (action == "restart" && apiOnline && Mihomo.restartCore()) {
            lastMessage = "已请求内核重启"
            // the socket drops and comes back; wait for the API to answer again
            waitForApi()
            refreshVersion()
            refreshConfigs()
            refreshPortStatus()
            return true
        }
        val ok = runJob("core-$action")
        // the shell surfaces lastMessage, so pages must not toast a second copy
        refreshVersion()
        refreshConfigs()
        refreshPortStatus()
        return ok
    }

    /** Geo databases: `POST /configs/geo` (core downloads them), script job as fallback. */
    suspend fun updateGeo(mirror: String = ""): Boolean {
        if (apiOnline) {
            busy = true
            yieldForUi()
            val ok = Mihomo.upgradeGeo()
            busy = false
            lastMessage = if (ok) "Geo 数据已由内核更新" else "内核更新 Geo 失败：${Mihomo.lastError}"
            if (ok) return true
        }
        val ok = runJob("geo-update", mirror)
        return ok
    }

    /**
     * `PATCH /configs` only changes the *running* core, so every switch used to revert on the
     * next restart. After a successful patch the same value is written into config.yaml via
     * `set <key> <value>` (one short exec — the core itself is already up to date).
     */
    private suspend fun persist(key: String, value: String) {
        if (!ksuAvailable) return
        val result = Shell.ctl("set", key, value)
        if (!result.ok) lastMessage = "已生效，但写入配置失败（重启后会回退）"
    }

    suspend fun setMode(value: String): Boolean {
        val ok = Mihomo.patchConfigs("""{"mode":${jsonString(value)}}""")
        if (ok) {
            mode = value
            persist("mode", value)
        } else {
            lastMessage = Mihomo.lastError
        }
        return ok
    }

    suspend fun setLogLevel(value: String): Boolean {
        val ok = Mihomo.patchConfigs("""{"log-level":${jsonString(value)}}""")
        if (ok) {
            logLevel = value
            persist("log-level", value)
        } else {
            lastMessage = Mihomo.lastError
        }
        return ok
    }

    suspend fun setTun(enabled: Boolean): Boolean {
        val ok = Mihomo.patchConfigs("""{"tun":{"enable":$enabled}}""")
        if (ok) {
            tunEnabled = enabled
            persist("tun.enable", if (enabled) "true" else "false")
        } else {
            lastMessage = Mihomo.lastError
        }
        return ok
    }

    suspend fun setAllowLan(enabled: Boolean): Boolean {
        val ok = Mihomo.patchConfigs("""{"allow-lan":$enabled}""")
        if (ok) {
            allowLan = enabled
            persist("allow-lan", if (enabled) "true" else "false")
        } else {
            lastMessage = Mihomo.lastError
        }
        return ok
    }

    suspend fun selectProxy(group: String, name: String): Boolean {
        val ok = Mihomo.selectProxy(group, name)
        if (ok) refreshProxies() else lastMessage = Mihomo.lastError
        return ok
    }

    /** Delay test target: honours the "延迟测速地址" setting instead of a hard-coded URL. */
    val testUrl: String
        get() = UiPrefs.testUrl.trim().ifEmpty { "http://www.gstatic.com/generate_204" }

    suspend fun testDelay(name: String, timeoutMs: Int = 5000, url: String = ""): Int? {
        val target = url.ifEmpty { testUrl }
        val delay = Mihomo.proxyDelay(name, timeoutMs, target)
        refreshProxies()
        return delay
    }

    suspend fun testGroupDelay(group: String, timeoutMs: Int = 5000, url: String = ""): Int {
        val target = url.ifEmpty { testUrl }
        val map = Mihomo.groupDelay(group, timeoutMs, target)
        refreshProxies()
        return map.size
    }

    suspend fun loadConfig() {
        // without the bridge the editor must still leave its "loading" state and say why
        if (!ksuAvailable) {
            configText = ""
            configLoaded = true
            return
        }
        configText = Shell.readText(Paths.CONFIG) ?: ""
        configLoaded = true
    }

    /**
     * Saves the edited config.
     *
     * Preferred path: hand the YAML to the core (`PUT /configs` payload). The core parses,
     * validates and applies it **live** — no staged file, no `mihomo -t`, no core restart,
     * and the parse error comes straight back. Only the disk copy needs the shell, and that
     * is a single short write.
     *
     * When the API is unreachable (core stopped) it falls back to the detached
     * `config-install` job, which validates and swaps the file on the device.
     */
    suspend fun saveConfig(text: String): Boolean {
        busy = true
        yieldForUi()
        if (apiOnline) {
            val error = Mihomo.applyConfigPayload(text)
            if (error == null) {
                val written = Shell.writeText(Paths.CONFIG, text)
                busy = false
                configText = text
                refreshConfigs()
                lastMessage = if (written) {
                    "配置已热应用并写入磁盘（内核未重启）"
                } else {
                    "已热应用，但写入 ${Paths.CONFIG} 失败（重启后会回退）"
                }
                return written
            }
            busy = false
            lastMessage = "内核拒绝该配置：$error"
            return false
        }
        busy = false
        val staged = "${Paths.RUN}/config.new.yaml"
        val written = Shell.writeText(staged, text)
        if (!written) {
            lastMessage = "写入临时文件失败"
            return false
        }
        val ok = runJob("config-install")
        if (ok) {
            configText = text
            refreshVersion()
            refreshConfigs()
        }
        return ok
    }

    /** Reloads config.yaml from disk: `PUT /configs {"path": …}`, script job as fallback. */
    suspend fun reloadConfig(): Boolean {
        if (Mihomo.reloadConfigFromDisk(Paths.CONFIG)) {
            lastMessage = "配置已重载（内核热加载，未重启）"
            refreshConfigs()
            return true
        }
        val ok = runJob("config-install")
        lastMessage = if (ok) "配置已重载" else Jobs.lastResult
        refreshConfigs()
        return ok
    }

    suspend fun refreshSubscriptions() {
        if (!ksuAvailable) return
        val result = Shell.ctl("sub-list")
        subscriptions = result.stdout.trim().lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2) null else Subscription(
                    name = parts[0],
                    url = parts[1],
                    updatedAt = parts.getOrElse(2) { "-" },
                    nodeCount = parts.getOrElse(3) { "-" }.toIntOrNull() ?: 0,
                )
            }
    }

    suspend fun addSubscription(name: String, url: String): Boolean {
        val result = Shell.ctl("sub-add", name, url)
        lastMessage = result.stdout.trim().ifEmpty { result.stderr.trim() }
        refreshSubscriptions()
        return result.ok
    }

    suspend fun removeSubscription(name: String): Boolean {
        val result = Shell.ctl("sub-remove", name)
        lastMessage = result.stdout.trim().ifEmpty { result.stderr.trim() }
        refreshSubscriptions()
        return result.ok
    }

    /**
     * Updates one subscription.
     *
     * The device only *fetches and merges* it (airports are User-Agent sensitive, so the
     * download has to happen there) and drops the result into the served UI directory. The
     * panel then hands that YAML to the core (`PUT /configs` payload), which parses and
     * applies it **live** — no core restart, connections survive, and a bad config reports
     * the core's own parse error.
     *
     * Falls back to the classic `sub-update` job (install + restart) when the API is down.
     */
    suspend fun updateSubscription(name: String): Boolean {
        if (apiOnline) {
            val fetched = runJob("sub-fetch", name)
            if (!fetched) return false
            val yaml = Jobs.readServed("pending.yaml")
            if (yaml != null && yaml.isNotBlank()) {
                val applied = Mihomo.applyConfigPayload(yaml)
                if (applied == null) {
                    val persisted = Shell.writeText(Paths.CONFIG, yaml)
                    lastMessage = if (persisted) {
                        "订阅已更新并热应用（内核未重启）"
                    } else {
                        "订阅已热应用，但写入磁盘失败（重启后会回退）"
                    }
                    refreshSubscriptions()
                    refreshConfigs()
                    refreshVersion()
                    return true
                }
                lastMessage = "核心拒绝该订阅配置：$applied"
                return false
            }
            return false
        }
        val ok = runJob("sub-update", name)
        refreshSubscriptions()
        refreshVersion()
        refreshConfigs()
        return ok
    }

    suspend fun checkCoreUpdate(mirror: String, variant: String = "") {
        // asking GitHub for the latest release waits on the network, so it runs as a job and
        // writes the result into `core-check.json` — the panel just fetches that file
        val ok = runJob("core-check", mirror, variant)
        if (!ok) {
            lastMessage = Jobs.lastResult
            return
        }
        // core-check.json is JSON as well
        val values = Jobs.readServed("core-check.json")?.let { text ->
            parseJson<CoreCheckFile>(text)?.let {
                mapOf(
                    "current" to it.current,
                    "latest" to it.latest,
                    "asset" to it.asset,
                    "url" to it.url,
                    "source" to it.source,
                )
            }
        } ?: emptyMap()
        coreUpdate = CoreUpdateInfo(
            current = values["current"] ?: "",
            latest = values["latest"] ?: "",
            asset = values["asset"] ?: "",
            url = values["url"] ?: "",
            size = values["size"] ?: "",
            source = values["source"] ?: "",
        )
        lastMessage = ""
    }

    /**
     * Installs a kernel build.
     *
     * When the target is simply "the latest release" the core updates itself
     * (`POST /upgrade`): it downloads its own release asset, replaces the binary and
     * restarts, so no shell, no 62 MB download through the script and the UI learns the
     * result from the WebSocket dropping and the new `/version` appearing.
     *
     * A pinned version or a GitHub mirror needs our multi-source downloader, so that goes
     * through the detached `core-update` job (which also backs up and can roll back).
     */
    suspend fun installCoreUpdate(version: String, variant: String, mirror: String): Boolean {
        updateOutput = ""
        val latest = version.isBlank() || version.equals("latest", ignoreCase = true)
        if (latest && mirror.isBlank() && apiOnline) {
            updateRunning = true
            yieldForUi()
            val before = coreVersion
            val requested = Mihomo.upgradeCore(channel = "release", force = true)
            updateRunning = false
            if (requested) {
                updateOutput = "内核正在自行下载并替换二进制…"
                lastMessage = "内核开始自更新，完成后会自动重启"
                // the core restarts itself: wait for the socket/API to come back, then look at
                // the version until it changes (or give up and report honestly)
                waitForApi()
                val started = nowMs()
                while (nowMs() - started < 120000) {
                    refreshVersion()
                    if (coreVersion.isNotEmpty() && coreVersion != before) break
                    delay(1000)
                }
                updateOutput = ""
                lastMessage = if (coreVersion != before) {
                    "内核已更新到 $coreVersion"
                } else {
                    "内核自更新已请求，但版本仍是 $coreVersion（可改用脚本方式重试）"
                }
                refreshVersion()
                refreshBinaryInfo()
                return coreVersion != before
            }
            updateOutput = "内核自更新不可用，改用脚本下载"
        }
        val args = buildList {
            add(version)
            add(variant)
            if (mirror.isNotBlank()) add(mirror)
        }.toTypedArray()
        updateRunning = true
        val ok = runJob("core-update", *args)
        updateRunning = false
        updateOutput = Jobs.current.message
        refreshVersion()
        refreshBinaryInfo()
        return ok
    }
}

/** Lets Compose paint the busy state before a blocking shell call takes over the thread. */
private suspend fun yieldForUi() {
    delay(30)
}

/** Shape of `core-check.json` (written by `cmd_core_check`). */
@kotlinx.serialization.Serializable
private data class CoreCheckFile(
    val current: String = "",
    val latest: String = "",
    val asset: String = "",
    val variant: String = "",
    val mirror: String = "",
    val source: String = "",
    val url: String = "",
    val up_to_date: Int = 0,
)
