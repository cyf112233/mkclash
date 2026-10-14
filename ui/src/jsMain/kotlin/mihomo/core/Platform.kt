package mihomo.core

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Thin wrappers over the browser primitives we need.
 *
 * Everything the KernelSU WebView gives us is reached through `window` globals, so this
 * file is the only place that talks to JavaScript directly.
 */

/** UTF-8 safe base64 encode. `btoa` alone breaks on any non-Latin1 character (e.g. 中文). */
fun b64EncodeUtf8(text: String): String = js(
    "(function(s){var b=new TextEncoder().encode(s),c='',i=0;" +
        "while(i<b.length){c+=String.fromCharCode.apply(null,b.subarray(i,i+0x8000));i+=0x8000;}" +
        "return btoa(c);})(text)"
)

/** UTF-8 safe base64 decode. */
fun b64DecodeUtf8(text: String): String = js(
    "(function(s){s=s.replace(/\\s+/g,'');var bin=atob(s),a=new Uint8Array(bin.length);" +
        "for(var i=0;i<bin.length;i++){a[i]=bin.charCodeAt(i);}" +
        "return new TextDecoder('utf-8').decode(a);})(text)"
)

private val rawStorage: dynamic = js("(typeof localStorage==='undefined')?null:localStorage")

/** Tiny persistent key/value store backed by `localStorage`. */
object Prefs {
    fun getString(key: String, def: String = ""): String {
        if (rawStorage == null) return def
        val v: dynamic = rawStorage.getItem(key)
        return if (v == null) def else v.toString()
    }

    fun putString(key: String, value: String) {
        if (rawStorage != null) rawStorage.setItem(key, value)
    }

    fun getBool(key: String, def: Boolean = false): Boolean = getString(key, if (def) "1" else "0") == "1"

    fun putBool(key: String, value: Boolean) = putString(key, if (value) "1" else "0")

    fun getInt(key: String, def: Int): Int = getString(key, def.toString()).toIntOrNull() ?: def

    fun putInt(key: String, value: Int) = putString(key, value.toString())

    fun remove(key: String) {
        if (rawStorage != null) rawStorage.removeItem(key)
    }
}

/** `prefers-color-scheme: dark` of the host WebView. */
fun systemDark(): Boolean = js("(typeof matchMedia!=='undefined')&&!!matchMedia('(prefers-color-scheme: dark)').matches")

fun jsLog(message: String) {
    js("(typeof console!=='undefined')&&console.log(message)")
}

internal fun jsNum(value: dynamic): Int = when {
    value == null -> -1
    jsTypeOf(value) == "number" -> (value as Number).toInt()
    jsTypeOf(value) == "string" -> value.toString().toIntOrNull() ?: -1
    jsTypeOf(value) == "boolean" -> if (value as Boolean) 0 else -1
    else -> -1
}

internal fun jsStr(value: dynamic): String = if (value == null) "" else value.toString()

/** `location.origin` of the page hosting the UI (empty outside a browser). */
fun pageOrigin(): String = js(
    "(typeof location==='undefined'||!location.origin)?'':location.origin",
) as String

/** `location.href`, used to explain the current hosting context to the user. */
fun pageHref(): String = js("(typeof location==='undefined')?'':location.href") as String

/**
 * Shared JS entry points. These are plain *references* with no free variables, so
 * minified builds keep them intact (see the warning on [Ksu.execDriver]).
 */
internal val fetchFn: dynamic = js("(typeof fetch==='undefined')?null:fetch")
internal val encodeUriComponentFn: dynamic = js("encodeURIComponent")
internal val dateParseFn: dynamic = js("Date.parse")
internal val dateNowFn: dynamic = js("Date.now")

private val windowRef: dynamic = js("(typeof window==='undefined')?null:window")
private val openDriver: dynamic = js("(function (w, url) { w.open(url, '_blank'); })")

/** Opens a link in a new tab/window (no-op outside a browser). */
fun openExternal(url: String) {
    val w = windowRef ?: return
    runCatching { openDriver(w, url) }
}

/** Fetches a file next to the page (same origin) — used for the module's `api.json`. */
suspend fun fetchRelativeText(path: String): String? {
    val impl = fetchFn ?: return null
    return try {
        val options: dynamic = js("({})")
        options["cache"] = "no-store"
        val promise: Promise<dynamic> = impl(path, options)
        val response: dynamic = promise.await()
        if (response.ok != true) return null
        val textPromise: Promise<dynamic> = response.text()
        jsStr(textPromise.await()).ifEmpty { null }
    } catch (t: Throwable) {
        null
    }
}
