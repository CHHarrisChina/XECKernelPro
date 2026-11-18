package me.weishu.kernelsu.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.model.ModuleUpdateInfo
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.isNetworkAvailable
import me.weishu.kernelsu.ui.util.listModules
import me.weishu.kernelsu.ui.util.module.sanitizeVersionString
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.getStringIgnoreCase(key: String, default: String = ""): String {
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        if (k.equals(key, ignoreCase = true)) {
            return optString(k, default)
        }
    }
    return default
}

class ModuleRepositoryImpl : ModuleRepository {

    companion object {
        private const val TAG = "ModuleRepository"

        /**
         * 兜底读取脚本：2026-11-18 真机反馈「31438 模块页已安装列表空白」。
         *
         * `xudc module list` 走的是 APK 里那份 `libxeccli.so`；一旦这个二进制在设备上
         * 跑不起来（未解压、被 SELinux 拦、升级后路径失效等），`listModules()` 会
         * 静默返回 `[]`，页面就只剩空列表、没有任何提示。这里直接用 root shell 把
         * `/data/adb/modules` 读一遍，字段与 `module list` 的 JSON 对齐，保证只要
         * root shell 能跑，模块页就一定有内容。
         *
         * 输出格式：一行一个模块，字段间用 ASCII Unit Separator (\u001F) 分隔，
         * 顺序与 [parseFallbackLine] 一一对应；字段值里的换行替换成空格。
         */
        private val FALLBACK_SCRIPT: String = listOf(
            "for d in /data/adb/modules/*/; do",
            "  [ -f \"\$d/module.prop\" ] || continue",
            "  id=\$(basename \"\$d\")",
            "  p=\$(cat \"\$d/module.prop\" 2>/dev/null)",
            "  name=\$(printf '%s\\n' \"\$p\" | sed -n 's/^name=//p' | head -n1 | tr '\\n' ' ')",
            "  version=\$(printf '%s\\n' \"\$p\" | sed -n 's/^version=//p' | head -n1 | tr '\\n' ' ')",
            "  vcode=\$(printf '%s\\n' \"\$p\" | sed -n 's/^versionCode=//p' | head -n1 | tr '\\n' ' ')",
            "  author=\$(printf '%s\\n' \"\$p\" | sed -n 's/^author=//p' | head -n1 | tr '\\n' ' ')",
            "  desc=\$(printf '%s\\n' \"\$p\" | sed -n 's/^description=//p' | head -n1 | tr '\\n' ' ')",
            "  ujson=\$(printf '%s\\n' \"\$p\" | sed -n 's/^updateJson=//p' | head -n1 | tr '\\n' ' ')",
            "  meta=\$(printf '%s\\n' \"\$p\" | sed -n 's/^metamodule=//p' | head -n1 | tr '\\n' ' ')",
            "  enabled=1; [ -f \"\$d/disable\" ] && enabled=0",
            "  remove=0; [ -f \"\$d/remove\" ] && remove=1",
            "  update=0; [ -f \"\$d/update\" ] && update=1",
            "  web=0; [ -d \"\$d/webroot\" ] && web=1",
            "  action=0; [ -f \"\$d/action.sh\" ] && action=1",
            "  printf '%s\\037%s\\037%s\\037%s\\037%s\\037%s\\037%s\\037%s\\037%s\\037%s\\037%s\\037%s\\037%s\\n' " +
                "\"\$id\" \"\$name\" \"\$version\" \"\$vcode\" \"\$author\" \"\$desc\" \"\$ujson\" \"\$enabled\" " +
                "\"\$update\" \"\$remove\" \"\$web\" \"\$action\" \"\$meta\"",
            "done",
        ).joinToString("\n")

        private const val FALLBACK_SCRIPT_LOG = "module-list fallback"
    }

    override suspend fun getModules(): Result<List<Module>> = withContext(Dispatchers.IO) {
        runCatching {
            val fromDaemon = runCatching { parseModules(listModules()) }.getOrNull().orEmpty()
            if (fromDaemon.isNotEmpty()) return@runCatching fromDaemon
            fallbackModules()
        }
    }

    /** `xudc module list` 的 JSON → 模型。 */
    private fun parseModules(raw: String): List<Module> {
        val array = JSONArray(raw)
        return (0 until array.length())
            .asSequence()
            .map { array.getJSONObject(it) }
            .map { obj ->
                Module(
                    id = obj.getString("id"),
                    name = obj.optString("name"),
                    author = obj.getStringIgnoreCase("author", ""),
                    version = obj.getStringIgnoreCase("version", ""),
                    versionCode = obj.optInt("versionCode", 0),
                    description = obj.optString("description"),
                    enabled = obj.getBoolean("enabled"),
                    update = obj.optBoolean("update"),
                    remove = obj.getBoolean("remove"),
                    updateJson = obj.optString("updateJson"),
                    hasWebUi = obj.optBoolean("web"),
                    hasActionScript = obj.optBoolean("action"),
                    metamodule = (obj.optInt("metamodule") != 0) || obj.optBoolean("metamodule"),
                    actionIconPath = obj.optString("actionIcon").takeIf { it.isNotBlank() },
                    webUiIconPath = obj.optString("webuiIcon").takeIf { it.isNotBlank() },
                )
            }
            .toList()
    }

    /**
     * 兜底：root shell 直接读 `/data/adb/modules`。只在 daemon 那条路拿不到任何模块时用，
     * 正常设备上不会走到这里。
     */
    private suspend fun fallbackModules(): List<Module> {
        val shell = runCatching { awaitRootShell() }.getOrNull() ?: return emptyList()
        if (!shell.isRoot) return emptyList()
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val ok = runCatching {
            shell.newJob().add(FALLBACK_SCRIPT).to(out, err).exec().isSuccess
        }.getOrDefault(false)
        if (!ok || out.isEmpty()) {
            android.util.Log.w(TAG, "$FALLBACK_SCRIPT_LOG: root shell could not read /data/adb/modules")
            return emptyList()
        }
        return out.mapNotNull { parseFallbackLine(it) }
    }

    private fun parseFallbackLine(line: String): Module? {
        val fields = line.split('\u001F')
        if (fields.size < 13) return null
        val id = fields[0].trim()
        if (id.isEmpty()) return null
        fun field(index: Int): String = fields[index].trim()
        return Module(
            id = id,
            name = field(1).ifEmpty { id },
            version = field(2),
            versionCode = field(3).toIntOrNull() ?: 0,
            author = field(4),
            description = field(5),
            updateJson = field(6),
            enabled = field(7) != "0",
            update = field(8) == "1",
            remove = field(9) == "1",
            hasWebUi = field(10) == "1",
            hasActionScript = field(11) == "1",
            metamodule = field(12) == "1" || field(12).equals("true", ignoreCase = true),
            actionIconPath = null,
            webUiIconPath = null,
        )
    }

    override suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isNetworkAvailable(ksuApp)) {
                return@runCatching ModuleUpdateInfo.Empty
            }
            if (module.updateJson.isEmpty() || module.remove || module.update || !module.enabled) {
                return@runCatching ModuleUpdateInfo.Empty
            }

            val url = module.updateJson
            val response = ksuApp.okhttpClient.newCall(
                Request.Builder().url(url).build()
            ).execute()

            val result = if (response.isSuccessful) {
                response.body.string()
            } else {
                ""
            }

            if (result.isEmpty()) {
                return@runCatching ModuleUpdateInfo.Empty
            }

            val updateJson = JSONObject(result)
            var version = updateJson.optString("version", "")
            version = sanitizeVersionString(version)
            val versionCode = updateJson.optInt("versionCode", 0)
            val zipUrl = updateJson.optString("zipUrl", "")
            val changelog = updateJson.optString("changelog", "")

            if (versionCode <= module.versionCode || zipUrl.isEmpty()) {
                ModuleUpdateInfo.Empty
            } else {
                ModuleUpdateInfo(zipUrl, version, changelog)
            }
        }
    }
}
