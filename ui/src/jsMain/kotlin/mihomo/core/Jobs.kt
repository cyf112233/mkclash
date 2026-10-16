package mihomo.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * A long module operation (subscription download, kernel download/validation, config
 * install, core start/stop) that runs **detached** on the device.
 *
 * Why: the KernelSU host implements `ksu.exec` synchronously, so whatever the command does
 * happens while the WebView's JS thread is blocked — a subscription download froze the
 * whole panel for its duration. `mihomo.sh job <kind>` therefore spawns the work in the
 * background, answers in a few hundred milliseconds, and keeps writing progress into
 * `job.json` inside the served UI directory. The panel reads that file with a plain
 * same-origin `fetch` (no shell, no blocked thread) and wires the result back into state.
 */
data class JobInfo(
    val id: String = "",
    val kind: String = "",
    val state: String = "idle",
    val message: String = "",
) {
    val running: Boolean get() = state == "running"
    val finished: Boolean get() = state == "done" || state == "failed"
    val failed: Boolean get() = state == "failed"
}

object Jobs {
    var current by mutableStateOf(JobInfo())
        private set

    /** Set when a job settles, so the shell can show it once as a snackbar. */
    var lastResult by mutableStateOf("")

    val busy: Boolean get() = current.running

    /** Human label for the running job. */
    val label: String
        get() = when (current.kind) {
            "sub-update" -> "正在更新订阅"
            "sub-add" -> "正在添加订阅"
            "sub-remove" -> "正在删除订阅"
            "core-start" -> "正在启动内核"
            "core-stop" -> "正在停止内核"
            "core-restart" -> "正在重启内核"
            "config-install" -> "正在校验并写入配置"
            "core-update" -> "正在下载并替换内核"
            "geo-update" -> "正在更新 Geo 数据"
            "port-auto" -> "正在切换控制端口"
            else -> "正在执行任务"
        }

    /**
     * Starts a job and returns as soon as the script has spawned it. The command itself
     * takes ~0.3s (one exec), the actual work happens outside this call.
     */
    suspend fun start(kind: String, vararg args: String): Boolean {
        val result = Shell.ctl("job", kind, *args)
        val values = Shell.parseKeyValues(result.stdout)
        val id = values["job"] ?: ""
        if (!result.ok || id.isEmpty()) {
            val error = values["error"] ?: result.stderr.trim().ifEmpty { "无法启动任务" }
            lastResult = "$kind 启动失败：$error"
            current = JobInfo(id = "", kind = kind, state = "failed", message = error)
            return false
        }
        current = JobInfo(id = id, kind = kind, state = "running", message = "任务已开始")
        return true
    }

    /** Reads the progress file the script keeps updating (same origin, no shell). */
    suspend fun poll(): JobInfo? {
        val text = fetchRelativeText("job.json") ?: return null
        // the file is JSON (job_write); parsing it as key=value silently returned null for
        // every job, which made every task "time out" after 15 minutes
        val parsed = parseJson<JobFile>(text) ?: return null
        if (parsed.state == "idle") return JobInfo(id = "", kind = "", state = "idle", message = "")
        val info = JobInfo(
            id = parsed.id,
            kind = parsed.kind,
            state = parsed.state,
            message = parsed.message,
        )
        current = info
        return info
    }

    /**
     * Polls until the current job settles, then reports it. Returns the settled job.
     *
     * Stops early when the status file reports a different id (a newer job has started) and
     * gives up after [timeoutMs] so a crashed script cannot leave the UI spinning forever.
     */
    suspend fun watch(timeoutMs: Long = 15 * 60 * 1000L): JobInfo {
        val id = current.id
        val started = nowMillis()
        while (true) {
            delay(600)
            val info = poll() ?: continue
            if (id.isNotEmpty() && info.id != id && info.running) return info
            if (info.finished) return info
            if (nowMillis() - started > timeoutMs) {
                val timeout = JobInfo(id = id, kind = current.kind, state = "failed", message = "任务超时")
                current = timeout
                return timeout
            }
        }
    }

    fun clear() {
        current = JobInfo()
    }

    private fun nowMillis(): Double = dateNowFn() as Double
}

/** On-disk shape of `job.json` (written by `job_write` in the control script). */
@kotlinx.serialization.Serializable
private data class JobFile(
    val id: String = "",
    val kind: String = "",
    val state: String = "idle",
    val message: String = "",
    val started: Double = 0.0,
    val updated: Double = 0.0,
)
