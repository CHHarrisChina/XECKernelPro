package me.weishu.kernelsu.ui.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ui.screen.settings.TeeDiagnostics

/**
 * 引擎就绪自检：root 到手之后确认内置 TEE 引擎真的在跑，不在就就地拉起来。
 *
 * 2026-11-17 真机反馈「TEE 引擎首次不启动」，根因有两条，都在这里：
 *  1. 旧判据只看 [`isInjected`]，而它要读 `/proc/<keystore2>/maps` —— 有些设备上那个
 *     读不到，探针只能给 `null`；旧代码把 `null` 当成"没坏"直接返回，引擎永远没人修。
 *  2. 修复失败只有一行日志，界面上什么都看不到，用户只看到"开关是开的、引擎没动"。
 *
 * 现在把判据拆成两件互不依赖的事实，任一为假就修：
 *  - [`snapshot`]：内置树在不在、`keymint` 进程在不在（不读 maps，任何设备都读得到）；
 *  - [`isInjected`]：keystore2 里有没有载荷（可能 `null`，`null` 不再等于健康）。
 * 修复会先 `builtin start`，失败再补一次 `builtin install` 后重试；结果只写进 [state]
 * 和日志，**不再弹 Toast**（2026-11-18 用户明确要求：TEE 修复功能整体下线，
 * 不允许开机/进首页时再弹「修复失败」这类提示）。
 */
object TeeEngineStartup {
    enum class State { Unknown, Ready, Repaired, Failed }

    private val lock = Mutex()
    private var lastAttemptMs: Long? = null
    private var startedByUser = false
    /** 本进程内是否已经尝试过修复：失败过就不再反复装/起，避免每次刷新都重跑一遍。 */
    private var attemptedThisProcess = false

    private val _state = MutableStateFlow(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 用户刚在设置里打开开关：这一次允许跳过"等开机 service 跑完"的 5 秒宽限。 */
    fun markStartedByUser() {
        startedByUser = true
    }

    fun isInjected(): Boolean? {
        val result = runCatching {
            getRootShell().newJob().add(
                "if sh /data/adb/ksu/builtin/tricky_store/injection-status.sh; " +
                    "then echo injected=1; else echo injected=0; fi",
            ).to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        return when (result.out.lastOrNull()?.trim()) {
            "injected=1" -> true
            "injected=0" -> false
            else -> null
        }
    }

    private data class Snapshot(val treePresent: Boolean, val keymintPid: Int) {
        val healthy: Boolean get() = treePresent && keymintPid > 0
    }

    /** 不读 maps 的事实：内置树 + keymint 进程。任何设备都能拿到。 */
    private fun snapshot(): Snapshot? {
        val result = runCatching {
            getRootShell().newJob().add(
                "T=0; [ -f /data/adb/ksu/builtin/tricky_store/service.sh ] && T=1; " +
                    "P=\$(pidof keymint 2>/dev/null | cut -d' ' -f1); " +
                    "echo \"tree=\$T pid=\${P:-0}\"",
            ).to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        val line = result.out.lastOrNull { it.trim().startsWith("tree=") }?.trim() ?: return null
        val tree = line.substringAfter("tree=").substringBefore(' ').trim() == "1"
        val pid = line.substringAfter("pid=", "").trim().toIntOrNull() ?: 0
        return Snapshot(tree, pid)
    }

    private fun engineSwitchOn(): Boolean? {
        val result = runCatching {
            getRootShell().newJob().add("'${getKsuDaemonPath()}' builtin tee")
                .to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        return when (result.out.joinToString(" ").trim()) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    private fun runRoot(command: String, timeoutSeconds: Long = 240L): Boolean = runCatching {
        getRootShell().newJob()
            .add("timeout $timeoutSeconds $command 2>&1")
            .to(ArrayList<String>(), ArrayList<String>()).exec().isSuccess
    }.getOrDefault(false)


    suspend fun ensureStarted() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (Natives.isSafeMode || !KsuCli.rootReady.value) return@withLock
            val shell = getRootShell()
            if (!shell.isAlive || !shell.isRoot) return@withLock
            val uid = runCatching {
                shell.newJob().add("id -u")
                    .to(ArrayList<String>(), ArrayList<String>()).exec()
            }.getOrNull() ?: return@withLock
            if (!uid.isSuccess || uid.out.singleOrNull()?.trim() != "0") return@withLock
            // 开关关掉是用户的明确选择：什么都不做。
            if (engineSwitchOn() != true) return@withLock

            val snapshotNow = snapshot()
            val injected = isInjected()
            if (injected == true) {
                _state.value = State.Ready
                return@withLock
            }
            // 修不修只看"能不能证明它坏了"：探针明确说没注入、或树/进程有一件不在。
            // 探针读不到（null）而树和进程都在时**不动手** —— 那可能只是读不到 maps，
            // 重启 keystore2 反而会打断正在用的证明链。
            val definitelyBroken = injected == false || snapshotNow == null || !snapshotNow.healthy
            if (definitelyBroken) {
                // 正常开机时 service 阶段可能还在跑；给一次宽限再动手，但用户刚点了
                // 开关就不等（他自己要求立刻生效）。
                if (!startedByUser) delay(5_000L)
                if (!KsuCli.rootReady.value || Natives.isSafeMode) return@withLock

                val before = snapshot()
                val stillInjected = isInjected()
                if (stillInjected == true && before?.healthy == true) {
                    _state.value = State.Ready
                    return@withLock
                }

                if (attemptedThisProcess && !startedByUser) return@withLock
                val now = SystemClock.elapsedRealtime()
                if (lastAttemptMs?.let { now - it < 60_000L } == true) return@withLock
                attemptedThisProcess = true
                currentCoroutineContext().ensureActive()
                lastAttemptMs = now

                Log.i("TeeEngineStartup", "engine repair: present=$before injected=$stillInjected")
                var repaired = TeeDiagnostics.restartEngine(null).fold(
                    onSuccess = { true },
                    onFailure = { error ->
                        Log.w("TeeEngineStartup", "builtin start failed: ${error.message}")
                        false
                    },
                )
                if (!repaired) {
                    // 树没落盘（首装、被清理、或换过 APK 版本）时 builtin start 也会失败：
                    // 先补装一次再试，别把"没装"报成"起不来"。
                    Log.i("TeeEngineStartup", "engine tree missing or broken, running builtin install")
                    runRoot("'${getKsuDaemonPath()}' builtin install")
                    repaired = TeeDiagnostics.restartEngine(null).isSuccess
                }

                val after = snapshot()
                val afterInjected = isInjected()
                val healthy = after?.healthy == true && afterInjected != false
                _state.value = if (healthy) State.Repaired else State.Failed
                if (healthy) {
                    Log.i("TeeEngineStartup", "engine repaired: pid=${after?.keymintPid} injected=$afterInjected")
                    startedByUser = false
                } else {
                    // 静默：这是后台自愈，不是用户操作；失败不该在首页糊一个 Toast。
                    Log.w("TeeEngineStartup", "engine still not running: $after injected=$afterInjected")
                }
                return@withLock
            }
            // 探针读不到、树也在、进程也在：不冒险重启 keystore2，只记状态。
            _state.value = State.Unknown
        }
    }
}
