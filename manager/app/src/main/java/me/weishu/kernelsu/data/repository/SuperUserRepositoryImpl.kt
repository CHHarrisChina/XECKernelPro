package me.weishu.kernelsu.data.repository

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.IKsuInterface
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.data.model.WEBVIEW_ZYGOTE_PROFILE_KEY
import me.weishu.kernelsu.data.model.WEBVIEW_ZYGOTE_UID
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.KsuService
import me.weishu.kernelsu.ui.util.awaitRootShell
import kotlin.coroutines.resume

class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"

        /** KsuService 绑定/回调的硬超时；超过它就不再等，按失败处理。 */
        private const val CONNECT_TIMEOUT_MS = 8_000L
    }

    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            // 绑定/回调如果永远不来，超级用户页会一直转圈；给一个硬超时，失败就走
            // runCatching 的正常失败路径（页面显示错误而不是无限等待）。
            val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                connectKsuService { Log.w(TAG, "KsuService disconnected") }
            } ?: throw IllegalStateException("KsuService bind timeout")

            var currentBinder = result.first
            var currentConnection = result.second

            try {
                suspend fun reconnect(): IKsuInterface {
                    withContext(Dispatchers.Main) {
                        RootService.unbind(currentConnection)
                    }
                    val retry = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        connectKsuService { Log.w(TAG, "KsuService disconnected") }
                    } ?: throw IllegalStateException("KsuService rebind timeout")
                    currentBinder = retry.first
                    currentConnection = retry.second
                    return IKsuInterface.Stub.asInterface(currentBinder)
                }

                val pm = ksuApp.packageManager
                val start = SystemClock.elapsedRealtime()

                var iface = IKsuInterface.Stub.asInterface(currentBinder)
                val idsArray = try {
                    iface.userIds
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.userIds
                }

                val slice = try {
                    iface.getPackages(0)
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.getPackages(0)
                }

                val packages = slice.list
                val newApps = packages.filter {
                    val ai = it.applicationInfo ?: return@filter false
                    ai.uid != WEBVIEW_ZYGOTE_UID &&
                            (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
                }.map {
                    val appInfo = it.applicationInfo!!
                    val profile = Natives.getAppProfile(it.packageName, appInfo.uid)
                    AppInfo(
                        label = appInfo.loadLabel(pm).toString(),
                        packageInfo = it,
                        profile = profile,
                    )
                }.toMutableList()

                // WebView Zygote is a single system UID, not a per-user package. Reuse the system icon.
                val systemInfo = ApplicationInfo(pm.getApplicationInfo("android", 0)).apply {
                    uid = WEBVIEW_ZYGOTE_UID
                }
                val placeholder = PackageInfo().apply {
                    packageName = ""
                    applicationInfo = systemInfo
                }
                newApps += AppInfo(
                    label = "WebView Zygote",
                    packageInfo = placeholder,
                    profile = Natives.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID),
                    profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                    special = true,
                )

                Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}")
                Pair(newApps, idsArray.toList())
            } finally {
                withContext(Dispatchers.Main) {
                    RootService.unbind(currentConnection)
                }
            }
        }
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = Natives.getAppProfile(it.profileKey, it.uid)
                it.copy(profile = profile)
            }
        }
    }

    private suspend inline fun connectKsuService(
        crossinline onDisconnect: () -> Unit = {}
    ): Pair<IBinder, ServiceConnection> = withContext(Dispatchers.Main) {
        // 必须等一条**真的 root** shell：主线程上取到的是"还没探到 root"时的轻量
        // 兜底 `sh`，拿那条非 root 壳去 exec 绑定任务，RootService 永远连不上
        // （onServiceConnected 不回调），getPackages() 拿不到包 —— 超级用户列表
        // 就一条应用都刷不出来（真机实测：进程表里根本没有 `:root` 子进程）。
        // 这一步是挂起的，所以只能放在 suspendCancellableCoroutine **外面**。
        val shell = awaitRootShell()
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceDisconnected(name: ComponentName?) {
                    onDisconnect()
                }

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (cont.isActive) {
                        cont.resume(binder as IBinder to this)
                    }
                }
            }

            cont.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    RootService.unbind(connection)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        RootService.unbind(connection)
                    }
                }
            }

            val intent = Intent(ksuApp, KsuService::class.java)

            val task = RootService.bindOrTask(
                intent,
                Shell.EXECUTOR,
                connection,
            )
            // 2026-11-18 bugreport（data_app_anr）根因：`shell.execTask(task)` 是**同步等待**
            // （R8 后的主线程栈就是 ShellImpl.execTask → SyncTask.await → LockSupport.park），
            // 而这段代码跑在 `withContext(Dispatchers.Main)` 里。真机上从冷启动等 root shell
            // + 拉起 KsuService 任务超过 5s，主线程直接被判 ANR（连续两份 ANR 都是这个栈），
            // 表现就是打开管理器后界面卡死/被系统杀掉。
            //
            // 修法：bind 注册留在主线程（它只是一次 bindService，很轻），**阻塞的任务执行**
            // 交给 libsu 自己的执行器；主线程只挂在这条 suspendCancellableCoroutine 上
            // 等 onServiceConnected 回调，不再持锁等待 shell 任务完成。
            if (task != null) {
                Shell.EXECUTOR.execute { shell.execTask(task) }
            }
        }
    }
}
