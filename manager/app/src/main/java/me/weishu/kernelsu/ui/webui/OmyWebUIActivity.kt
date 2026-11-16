package me.weishu.kernelsu.ui.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.withNewRootShell
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Manager-owned assets only. This bridge intentionally has no command/path argument. */
@SuppressLint("SetJavaScriptEnabled")
class OmyWebUIActivity : Activity() {
    private lateinit var webView: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var closed = false
    private val originals = mutableMapOf<String, String>() // Accessed only by worker.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.omy_webui_title)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.blockNetworkLoads = true
            isSaveEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                @Deprecated("Legacy WebView callback")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                    return if (request.url.toString() == PAGE && request.method == "GET") {
                        loader.shouldInterceptRequest(request.url) ?: denied()
                    } else denied()
                }
            }
            addJavascriptInterface(Bridge(), "Omy")
        }
        setContentView(webView)
        webView.loadUrl(PAGE)
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private inner class Bridge {
        @JavascriptInterface
        fun request(id: Int, action: String, payload: String) {
            if (closed || id < 0 || action !in ACTIONS || payload.length > MAX_BYTES * 2) return
            // Bounded requests: the local page disables controls while one request runs.
            synchronized(worker) {
                if (closed || !busy.compareAndSet(false, true)) return
                worker.execute {
                    val reply = runCatching {
                        withNewRootShell {
                            require(isRoot) { "root" }
                            when (action) {
                                "load" -> load(this)
                                "saveConfig", "saveTargets" -> save(this, action, payload)
                                else -> restart(this, action)
                            }
                        }
                    }.getOrElse {
                        JSONObject().put("ok", false).put("message", getString(R.string.omy_webui_operation_failed))
                    }
                    reply.put("labels", labels())
                    busy.set(false)
                    if (!closed) runOnUiThread {
                        if (!closed) webView.evaluateJavascript("window.omyReply($id,${JSONObject.quote(reply.toString())})", null)
                    }
                }
            }
        }
    }

    private fun command(shell: Shell, script: String): String {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = shell.newJob().add(script).to(stdout, stderr).exec()
        check(result.code == 0) { "operation" } // Never expose shell output/errors or key material.
        return stdout.joinToString("\n")
    }

    private fun readFile(shell: Shell, path: String): String? {
        val encoded = command(shell, "if [ -f '$path' ] && [ ! -L '$path' ] && [ \"${'$'}(wc -c < '$path')\" -le $MAX_BYTES ]; then base64 < '$path'; else printf MISSING; fi")
        return if (encoded == "MISSING") null else String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun load(shell: Shell): JSONObject {
        originals.clear()
        val result = JSONObject().put("ok", true)
        for ((name, path) in FILES) {
            val text = readFile(shell, path)
            result.put(name, text ?: JSONObject.NULL)
            if (name != "injector" && text != null) originals[name] = text
        }
        // PID files are untrusted input; only decimal PIDs are passed to kill.
        val state = command(shell, """
            for name in keymint injector; do
                file=/data/adb/omk/${'$'}{name}-daemon.pid
                pid=${'$'}(cat "${'$'}{file}" 2>/dev/null)
                case "${'$'}{pid}" in ''|*[!0-9]*) printf '%s: stopped/unknown\n' "${'$'}{name}";;
                *) if kill -0 "${'$'}{pid}" 2>/dev/null; then printf '%s: running (PID %s)\n' "${'$'}{name}" "${'$'}{pid}"; else printf '%s: stopped/unknown\n' "${'$'}{name}"; fi;; esac
            done
            for name in keymint injector all; do
                if [ -f /data/adb/omk/restart.${'$'}{name} ]; then printf 'restart.%s: pending\n' "${'$'}{name}"; fi
            done
        """.trimIndent())
        return result.put("status", state).put("message", getString(R.string.omy_webui_loaded))
    }

    private fun save(shell: Shell, action: String, text: String): JSONObject {
        val name = if (action == "saveConfig") "config" else "targets"
        val path = FILES.getValue(name)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && '\u0000' !in text)
        require(!text.contains("PRIVATE KEY", ignoreCase = true))
        val previous = originals[name] ?: error("readonly")
        check(readFile(shell, path) == previous) { "changed" }
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val expected = Base64.encodeToString(previous.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val owner = if (name == "config") "1017:1017" else "0:0"
        val mode = if (name == "config") "660" else "644"
        // All paths are fixed constants; user text reaches the shell only as Base64 data.
        command(shell, """
            (
                set -e
                file='$path'
                dir='${path.substringBeforeLast('/')}'
                [ -d "${'$'}{dir}" ] && [ ! -L "${'$'}{dir}" ] && [ -f "${'$'}{file}" ] && [ ! -L "${'$'}{file}" ] || exit 1
                [ ! -d "${'$'}{file}.webui.bak" ] && [ ! -L "${'$'}{file}.webui.bak" ] || exit 1
                mkdir "${'$'}{dir}/.omy-webui.lock"
                tmp=''
                bak=''
                trap 'rm -f "${'$'}{tmp}" "${'$'}{bak}"; rmdir "${'$'}{dir}/.omy-webui.lock"' EXIT
                [ "${'$'}(base64 < "${'$'}{file}" | tr -d '\n\r')" = '$expected' ]
                tmp=${'$'}(mktemp "${'$'}{dir}/.omy-webui.XXXXXX")
                bak=${'$'}(mktemp "${'$'}{dir}/.omy-webui-backup.XXXXXX")
                printf '%s' '$encoded' | base64 -d > "${'$'}{tmp}"
                chown $owner "${'$'}{tmp}"
                chmod $mode "${'$'}{tmp}"
                cp -p "${'$'}{file}" "${'$'}{bak}"
                mv -f "${'$'}{bak}" "${'$'}{file}.webui.bak"
                mv -f "${'$'}{tmp}" "${'$'}{file}"
            )
        """.trimIndent())
        originals[name] = text
        return JSONObject().put("ok", true).put("message", getString(if (name == "config") R.string.omy_webui_config_saved else R.string.omy_webui_targets_saved))
    }

    private fun restart(shell: Shell, action: String): JSONObject {
        val marker = when (action) {
            "restartKeymint" -> "keymint"
            "restartInjector" -> "injector"
            "restartAll" -> "all"
            else -> error("action")
        }
        command(shell, "[ -d /data/adb/omk ] && [ ! -L /data/adb/omk ] && [ ! -L /data/adb/omk/restart.$marker ] && touch /data/adb/omk/restart.$marker")
        return JSONObject().put("ok", true).put("message", getString(R.string.omy_webui_restart_requested))
    }

    private fun labels() = JSONObject().apply {
        LABELS.forEach { (name, resource) -> put(name, getString(resource)) }
    }

    override fun onDestroy() {
        synchronized(worker) { closed = true; worker.shutdownNow() }
        webView.removeJavascriptInterface("Omy")
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PAGE = "https://appassets.androidplatform.net/assets/omy/index.html"
        private const val MAX_BYTES = 131072
        private val ACTIONS = setOf("load", "saveConfig", "saveTargets", "restartKeymint", "restartInjector", "restartAll")
        private val FILES = linkedMapOf("config" to "/data/misc/keystore/omk/config.toml", "injector" to "/data/misc/keystore/omk/injector.toml", "targets" to "/data/adb/tricky_store/target.txt")
        private val LABELS = mapOf(
            "title" to R.string.omy_webui_title, "intro" to R.string.omy_webui_intro,
            "refresh" to R.string.omy_webui_refresh, "configHint" to R.string.omy_webui_config_hint,
            "targetsHint" to R.string.omy_webui_targets_hint, "injectorHint" to R.string.omy_webui_injector_hint,
            "saveConfig" to R.string.omy_webui_save_config, "saveTargets" to R.string.omy_webui_save_targets,
            "restartKeymint" to R.string.omy_webui_restart_keymint, "restartInjector" to R.string.omy_webui_restart_injector,
            "restartAll" to R.string.omy_webui_restart_all, "restartHint" to R.string.omy_webui_restart_hint,
            "missing" to R.string.omy_webui_missing, "failed" to R.string.omy_webui_operation_failed,
            "busy" to R.string.omy_webui_busy, "confirmRestart" to R.string.omy_webui_confirm_restart,
            "confirmRefresh" to R.string.omy_webui_confirm_refresh,
        )
    }
}
