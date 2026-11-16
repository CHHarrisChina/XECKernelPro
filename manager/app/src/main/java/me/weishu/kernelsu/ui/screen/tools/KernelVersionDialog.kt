package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.getKsuDaemonPath
import me.weishu.kernelsu.ui.util.shQuote
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
fun KernelVersionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf("") }
    var autoStart by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val busy = loading || saving
    val valid = validUnameField(release) && validUnameField(version)

    LaunchedEffect(Unit) {
        try {
            val config = withContext(Dispatchers.IO) {
                val shell = awaitRootShell()
                check(shell.isRoot) { context.getString(R.string.kernel_version_root_required) }
                val result = runSusfs(shell, "config list")
                check(result.isSuccess) {
                    result.err.joinToString("\n").ifBlank {
                        context.getString(R.string.kernel_version_load_failed)
                    }
                }
                JSONObject(result.out.joinToString("\n"))
            }
            release = config.optString("uname_value").takeUnless { it == "default" }.orEmpty()
            version = config.optString("build_time_value").takeUnless { it == "default" }.orEmpty()
            autoStart = config.optString("auto_start_enabled") == "true"
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = context.getString(R.string.kernel_version_load_failed) +
                e.message?.let { "\n$it" }.orEmpty()
        } finally {
            loading = false
        }
    }

    XDialog(show = true, onDismissRequest = { if (!saving) onDismiss() }) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XDialogTitle(text = stringResource(R.string.kernel_version_title))
            Text(
                text = stringResource(R.string.kernel_version_hint),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            TextField(
                value = release,
                onValueChange = { release = it; message = null },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.kernel_version_release),
                enabled = loaded && !busy,
                maxLines = 1,
            )
            TextField(
                value = version,
                onValueChange = { version = it; message = null },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.kernel_version_build_time),
                enabled = loaded && !busy,
                maxLines = 1,
            )
            if (!valid) {
                Text(text = stringResource(R.string.kernel_version_invalid), fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.kernel_version_auto_start),
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                )
                Switch(
                    checked = autoStart,
                    enabled = loaded && !busy,
                    onCheckedChange = { autoStart = it; message = null },
                )
            }
            if (busy) {
                Text(
                    text = stringResource(
                        if (loading) R.string.kernel_version_loading else R.string.kernel_version_saving,
                    ),
                    fontSize = 13.sp,
                )
            }
            message?.let { Text(text = it, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.close),
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                TextButton(
                    text = stringResource(R.string.kernel_version_apply),
                    enabled = loaded && !busy && valid,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val newRelease = release.ifEmpty { "default" }
                        val newVersion = version.ifEmpty { "default" }
                        val newAutoStart = autoStart
                        saving = true
                        message = null
                        scope.launch {
                            var applied = false
                            try {
                                withContext(Dispatchers.IO) {
                                    val shell = awaitRootShell()
                                    check(shell.isRoot) {
                                        context.getString(R.string.kernel_version_root_required)
                                    }
                                    val result = runSusfs(
                                        shell,
                                        "set-uname -- ${shQuote(newRelease)} ${shQuote(newVersion)}",
                                    )
                                    check(result.isSuccess) {
                                        result.err.joinToString("\n").ifBlank {
                                            context.getString(R.string.kernel_version_apply_failed)
                                        }
                                    }
                                    applied = true
                                    for ((key, value) in listOf(
                                        "uname_value" to newRelease,
                                        "build_time_value" to newVersion,
                                        "auto_start_enabled" to newAutoStart.toString(),
                                    )) {
                                        val saved = runSusfs(shell, "config set $key ${shQuote(value)}")
                                        check(saved.isSuccess) {
                                            saved.err.joinToString("\n").ifBlank {
                                                context.getString(R.string.kernel_version_save_failed)
                                            }
                                        }
                                    }
                                }
                                message = context.getString(R.string.kernel_version_applied)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = context.getString(
                                    if (applied) R.string.kernel_version_save_failed
                                    else R.string.kernel_version_apply_failed,
                                ) + e.message?.let { "\n$it" }.orEmpty()
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun validUnameField(value: String): Boolean =
    value.toByteArray(Charsets.UTF_8).size < 65 &&
        value.none { it == '\u0000' || it == '\n' || it == '\r' }

// Explicit collectors are required: libsu newJob() otherwise discards output.
private fun runSusfs(shell: Shell, args: String): Shell.Result = shell.newJob()
    .add("${shQuote(getKsuDaemonPath())} susfs $args")
    .to(ArrayList<String>(), ArrayList<String>())
    .exec()
