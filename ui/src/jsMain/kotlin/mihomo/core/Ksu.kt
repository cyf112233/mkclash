package mihomo.core

import kotlinx.coroutines.await
import kotlin.js.Promise

/** Result of one shell command, mirroring KernelSU's `exec()` payload. */
data class ExecResult(
    val errno: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = errno == 0
    val out: String get() = stdout.trim()
    val err: String get() = stderr.trim()
}

private val ksuApi: dynamic = js("(typeof ksu==='undefined')?null:ksu")
private val jsonParseFn: dynamic = js("JSON.parse")

/**
 * Bridge to the KernelSU-compatible WebView host (`window.ksu`).
 *
 * The real contract (see the official `kernelsu` npm package) is **callback based**:
 *
 * ```js
 * ksu.exec(command, '{}', callbackName)
 * window[callbackName] = (errno, stdout, stderr) => { ... }
 * ```
 *
 * `ksu.exec()` returns nothing at all, so an implementation that expects a return value
 * silently fails for *every* privileged action — including reading the controller secret,
 * which makes the panel query the API unauthenticated and show a bare 401. The result
 * arrives through `window[callbackName]`; hosts that return a Promise, a result object or
 * a JSON string are accepted too.
 *
 * ⚠️ [execDriver] is a JS **function expression with no free variables**, called with
 * values from ordinary Kotlin code. Referencing a *suspend function's* parameter inside
 * `js("…")` looks fine in development, but the compiler keeps such parameters as
 * state-machine fields, so the emitted code becomes `this.<mangled>` — and inside the
 * `new Promise(...)` callback `this` is `undefined`. That is why this only blew up in the
 * minified production bundle (`Cannot read properties of undefined (reading 'qou_1')`).
 */
object Ksu {
    private var callbackSeq = 0

    private val execDriver: dynamic = js(
        "(function (api, command, callbackName) {" +
            "  function normalise(a, b, c) {" +
            "    if (a && typeof a === 'object') return a;" +
            "    if (typeof a === 'string' && b === undefined && c === undefined) {" +
            "      try { return JSON.parse(a); } catch (e) { return { errno: 0, stdout: a, stderr: '' }; }" +
            "    }" +
            "    return { errno: a, stdout: b, stderr: c };" +
            "  }" +
            "  return new Promise(function (resolve) {" +
            "    var settled = false;" +
            "    function finish(value) {" +
            "      if (settled) return;" +
            "      settled = true;" +
            "      try { delete window[callbackName]; } catch (e) {}" +
            "      resolve(value);" +
            "    }" +
            "    window[callbackName] = function (a, b, c) {" +
            "      clearTimeout(timer);" +
            "      finish(normalise(a, b, c));" +
            "    };" +
            "    var timer = setTimeout(function () {" +
            "      finish({ errno: -1, stdout: '', stderr: 'exec 超时：宿主没有回调' });" +
            "    }, 120000);" +
            "    try {" +
            "      var ret = api.exec(command, '{}', callbackName);" +
            "      if (ret !== undefined && ret !== null) {" +
            "        if (typeof ret.then === 'function') {" +
            "          ret.then(function (v) { clearTimeout(timer); finish(normalise(v)); }," +
            "                   function (e) { clearTimeout(timer); finish({ errno: -1, stdout: '', stderr: String(e) }); });" +
            "        } else {" +
            "          clearTimeout(timer);" +
            "          finish(normalise(ret));" +
            "        }" +
            "      }" +
            "    } catch (e) {" +
            "      clearTimeout(timer);" +
            "      finish({ errno: -1, stdout: '', stderr: String(e) });" +
            "    }" +
            "  });" +
            "})",
    )

    val available: Boolean get() = ksuApi != null

    /** Module id as reported by the manager (`moduleInfo()`), empty when unavailable. */
    val moduleId: String by lazy {
        val api = ksuApi ?: return@lazy ""
        runCatching {
            val info: dynamic = api.moduleInfo()
            if (info == null) {
                ""
            } else if (jsTypeOf(info) == "string") {
                val text = info.toString()
                if (text.startsWith("{")) jsStr(jsonParseFn(text)["id"]) else text
            } else {
                jsStr(info.id)
            }
        }.getOrDefault("")
    }

    suspend fun exec(command: String): ExecResult {
        val api = ksuApi ?: return ExecResult(-1, "", "KernelSU WebUI 接口不可用")
        val callbackName = "mkclash_exec_cb_${callbackSeq++}"
        return try {
            val promise: Promise<dynamic> = execDriver(api, command, callbackName)
            val result: dynamic = promise.await()
            ExecResult(jsNum(result.errno), jsStr(result.stdout), jsStr(result.stderr))
        } catch (t: Throwable) {
            ExecResult(-1, "", t.message ?: "exec 调用失败")
        }
    }

    fun toast(message: String) {
        runCatching { ksuApi?.toast(message) }
    }

    fun fullScreen(enabled: Boolean) {
        runCatching { ksuApi?.fullScreen(enabled) }
    }

    fun enableEdgeToEdge(enabled: Boolean) {
        runCatching { ksuApi?.enableEdgeToEdge(enabled) }
    }
}
