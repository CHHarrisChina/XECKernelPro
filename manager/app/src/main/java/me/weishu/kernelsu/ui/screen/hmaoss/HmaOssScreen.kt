package me.weishu.kernelsu.ui.screen.hmaoss

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.repository.ModuleRepositoryImpl
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.getFileName
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val HMA_PACKAGE = "org.frknkrc44.hma_oss"
private const val HMA_MODULE_ID = "hma_oss_zygisk"
private const val HMA_RELEASES = "https://github.com/frknkrc44/HMA-OSS/releases/latest"

@Composable
fun HmaOssScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val repository = remember { ModuleRepositoryImpl() }
    val scrollBehavior = MiuixScrollBehavior()
    var checkingApp by remember { mutableStateOf(true) }
    var appInfo by remember { mutableStateOf<PackageInfo?>(null) }
    var moduleStatus by remember { mutableStateOf<Int?>(null) }
    var moduleVersion by remember { mutableStateOf<String?>(null) }
    var canInstall by remember { mutableStateOf(false) }

    LifecycleResumeEffect(context) {
        val refresh = scope.launch {
            appInfo = withContext(Dispatchers.IO) { context.packageManager.findHmaApp() }
            checkingApp = false

            val hasRoot = awaitRootShell(timeoutMs = 2_500L).isRoot
            canInstall = hasRoot && !Natives.isSafeMode
            val modules = if (hasRoot) repository.getModules().getOrNull() else null
            val hmaModule = modules?.firstOrNull { it.id == HMA_MODULE_ID }
            moduleStatus = when {
                modules == null -> R.string.hma_oss_module_unavailable
                hmaModule == null -> R.string.hma_oss_module_not_found
                else -> hmaModule.hmaStatus()
            }
            moduleVersion = hmaModule?.version?.takeIf { it.isNotBlank() }
        }
        onPauseOrDispose { refresh.cancel() }
    }

    fun showError(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    var pendingZip by rememberSaveable { mutableStateOf<Uri?>(null) }
    val installConfirm = rememberConfirmDialog(
        onConfirm = {
            pendingZip?.let { uri ->
                scope.launch {
                    if (awaitRootShell(timeoutMs = 2_500L).isRoot && !Natives.isSafeMode) {
                        navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri))))
                    } else {
                        showError(R.string.hma_oss_module_install_unavailable)
                    }
                }
            }
            pendingZip = null
        },
        onDismiss = { pendingZip = null },
    )
    val selectZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    runCatching { uri.getFileName(context) }.getOrNull()
                }
                pendingZip = uri
                installConfirm.showConfirm(
                    title = context.getString(R.string.hma_oss_install_zip),
                    content = context.getString(
                        R.string.module_install_prompt_with_name,
                        name ?: context.getString(R.string.hma_oss_zip_file),
                    ),
                )
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.hma_oss_title),
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        val layoutDirection = LocalLayoutDirection.current
                        Icon(
                            modifier = Modifier.graphicsLayer {
                                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                            },
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.remote_hub_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 22.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Xc8Panel {
                        Xc8Row(
                            title = stringResource(R.string.hma_oss_app),
                            subtitle = if (checkingApp) {
                                stringResource(R.string.hma_oss_checking)
                            } else {
                                appInfo?.run {
                                    versionName?.takeIf { it.isNotBlank() } ?: longVersionCode.toString()
                                } ?: stringResource(R.string.hma_oss_app_missing)
                            },
                        )
                        val status = stringResource(moduleStatus ?: R.string.hma_oss_checking)
                        Xc8Row(
                            title = stringResource(R.string.hma_oss_module),
                            subtitle = moduleVersion?.let {
                                stringResource(R.string.hma_oss_module_status_with_version, it, status)
                            } ?: status,
                        )
                        if (appInfo != null) {
                            Xc8Row(
                                title = stringResource(R.string.hma_oss_open),
                                leading = Icons.Rounded.OpenInNew,
                                trailing = { Xc8Chevron() },
                                onClick = {
                                    runCatching {
                                        val intent = context.packageManager.getLaunchIntentForPackage(HMA_PACKAGE)
                                        if (intent == null) showError(R.string.hma_oss_app_open_failed)
                                        else context.startActivity(intent)
                                    }.onFailure { showError(R.string.hma_oss_app_open_failed) }
                                },
                            )
                        }
                    }
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        Xc8Row(
                            title = stringResource(R.string.hma_oss_releases),
                            subtitle = stringResource(R.string.hma_oss_install_hint),
                            leading = Icons.Rounded.OpenInNew,
                            trailing = { Xc8Chevron() },
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, HMA_RELEASES.toUri())
                                runCatching { context.startActivity(intent) }
                                    .onFailure { showError(R.string.hma_oss_releases_open_failed) }
                            },
                        )
                        Xc8Row(
                            title = stringResource(R.string.hma_oss_install_zip),
                            subtitle = if (!canInstall && moduleStatus != null) {
                                stringResource(R.string.hma_oss_module_install_unavailable)
                            } else null,
                            leading = Icons.Rounded.GetApp,
                            enabled = canInstall,
                            trailing = { if (canInstall) Xc8Chevron() },
                            onClick = {
                                runCatching {
                                    selectZip.launch(
                                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                                    )
                                }.onFailure { showError(R.string.hma_oss_picker_open_failed) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.findHmaApp(): PackageInfo? =
    try {
        getPackageInfo(HMA_PACKAGE, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

private fun Module.hmaStatus(): Int = when {
    remove -> R.string.hma_oss_remove_pending
    update -> R.string.hma_oss_update_pending
    enabled -> R.string.hma_oss_enabled
    else -> R.string.hma_oss_disabled
}
