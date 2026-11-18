package me.weishu.kernelsu.ui.component.miuix

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.LoadingDialogHandle
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.design.panel.Xc8DialogAction
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.util.getBugreportFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SendLogDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    loadingDialog: LoadingDialogHandle,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logSavedText = stringResource(R.string.log_saved)
    val sendLogText = stringResource(R.string.send_log)
    val exportBugreportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            loadingDialog.show()
            val failure = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    getBugreportFile(context).inputStream().use {
                        it.copyTo(output)
                    }
                } ?: throw IllegalStateException("cannot open target document")
            }.exceptionOrNull()
            loadingDialog.hide()
            val message = if (failure == null) logSavedText else context.getString(R.string.log_save_failed)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    XDialog(
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        XDialogTitle(text = stringResource(R.string.send_log))
        Xc8Row(
            title = stringResource(id = R.string.save_log),
            leading = Icons.Rounded.Save,
            onClick = {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
                val current = LocalDateTime.now().format(formatter)
                exportBugreportLauncher.launch("XECKernelUltra_bugreport_${current}.tar.gz")
                onDismissRequest()
            },
        )
        Xc8Row(
            title = stringResource(id = R.string.send_log),
            leading = Icons.Rounded.Share,
            onClick = {
                scope.launch {
                    onDismissRequest()
                    // 生成/分享失败只提示，不让 FileProvider 的异常把整个页面带崩。
                    val failure = runCatching {
                        val bugreport = loadingDialog.withLoading {
                            withContext(Dispatchers.IO) {
                                getBugreportFile(context)
                            }
                        }
                        val uri: Uri = FileProvider.getUriForFile(
                            context,
                            "${BuildConfig.APPLICATION_ID}.fileprovider",
                            bugreport,
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_STREAM, uri)
                            setDataAndType(uri, "application/gzip")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, sendLogText))
                    }.exceptionOrNull()
                    if (failure != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.log_save_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Xc8DialogAction(
                text = stringResource(android.R.string.cancel),
                color = Xc.colors.textSecondary,
                modifier = Modifier.weight(1f),
                onClick = {
                    onDismissRequest()
                },
            )
        }
    }
}
