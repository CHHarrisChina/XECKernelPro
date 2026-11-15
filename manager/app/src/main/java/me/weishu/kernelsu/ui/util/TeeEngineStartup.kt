package me.weishu.kernelsu.ui.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ui.screen.settings.TeeDiagnostics

/** Repairs a missing runtime after root becomes available; never changes the user's switch. */
object TeeEngineStartup {
    private val lock = Mutex()
    private var lastAttemptMs: Long? = null

    fun isInjected(): Boolean? {
        val result = runCatching {
            getRootShell().newJob().add(
                "KS=\$(pidof keystore2 2>/dev/null); I=1; [ -n \"\$KS\" ] || I=0; " +
                    "for K in \$KS; do grep -q libTEESimulator /proc/\$K/maps 2>/dev/null || I=0; done; " +
                    "echo injected=\$I",
            ).to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        return when (result.out.lastOrNull()?.trim()) {
            "injected=1" -> true
            "injected=0" -> false
            else -> null
        }
    }

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
            val enabled = runCatching {
                shell.newJob().add("'${getKsuDaemonPath()}' builtin tee")
                    .to(ArrayList<String>(), ArrayList<String>()).exec()
            }.getOrNull() ?: return@withLock
            // An unavailable daemon or unknown answer must not be interpreted as enabled.
            if (!enabled.isSuccess || enabled.out.joinToString(" ").trim() != "on") return@withLock
            if (isInjected() != false) return@withLock
            // Let an in-flight boot service finish before considering a restart.
            delay(5_000L)
            if (!KsuCli.rootReady.value || Natives.isSafeMode || isInjected() != false) return@withLock
            val stillEnabled = runCatching {
                shell.newJob().add("'${getKsuDaemonPath()}' builtin tee")
                    .to(ArrayList<String>(), ArrayList<String>()).exec()
            }.getOrNull() ?: return@withLock
            if (!stillEnabled.isSuccess || stillEnabled.out.joinToString(" ").trim() != "on") return@withLock
            val now = SystemClock.elapsedRealtime()
            if (lastAttemptMs?.let { now - it < 60_000L } == true) return@withLock
            currentCoroutineContext().ensureActive()
            lastAttemptMs = now
            TeeDiagnostics.restartEngine(null).fold(
                onSuccess = { Log.i("TeeEngineStartup", "Engine startup verified: $it") },
                onFailure = { Log.w("TeeEngineStartup", "Engine startup failed", it) },
            )
        }
    }
}
