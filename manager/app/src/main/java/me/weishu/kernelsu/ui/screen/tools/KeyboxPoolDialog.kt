package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import java.text.DateFormat
import java.util.Date

/** Fetches on entry. Installation always requires selecting a row and pressing Apply. */
@Composable
fun KeyboxPoolDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pool by remember { mutableStateOf<KeyboxPoolRepository.Pool?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val successText = stringResource(R.string.keybox_pool_applied)
    val failureText = stringResource(R.string.keybox_pool_apply_failed)
    val fetchFailureText = stringResource(R.string.keybox_pool_fetch_failed)
    LaunchedEffect(Unit) {
        try {
            pool = KeyboxPoolRepository.fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = fetchFailureText
        } finally { loading = false }
    }
    val candidate = pool?.candidates?.firstOrNull { it.id == selected }
    XDialog(show = true, onDismissRequest = { if (!applying) onDismiss() }) {
        XDialogTitle(text = stringResource(R.string.keybox_pool_title))
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.keybox_pool_limits), fontSize = 13.sp)
            Text(if (loading) stringResource(R.string.keybox_pool_loading)
                else stringResource(R.string.keybox_pool_count, pool?.candidates?.size ?: 0))
            pool?.candidates?.forEach { item ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !applying) { selected = item.id; status = null }
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(stringResource(R.string.keybox_pool_row,
                        if (selected == item.id) "[x]" else "[ ]", item.id.take(12), item.keyCount))
                    Text(item.source, fontSize = 13.sp)
                    Text(item.sourceUrl, fontSize = 11.sp)
                    Text(stringResource(R.string.keybox_pool_fetched, poolTime(item.fetchedAt)), fontSize = 12.sp)
                    Text(stringResource(R.string.keybox_pool_modified,
                        item.sourceModifiedAt ?: stringResource(R.string.keybox_pool_unknown)), fontSize = 12.sp)
                    Text(stringResource(R.string.keybox_pool_window,
                        poolTime(item.validFrom), poolTime(item.expiresAt)), fontSize = 12.sp)
                    Text(stringResource(R.string.keybox_pool_checked), fontSize = 12.sp)
                }
            }
            pool?.notices?.forEach { Text(it, fontSize = 12.sp) }
            status?.let { Text(it, fontSize = 13.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = stringResource(R.string.keybox_pool_close), enabled = !applying,
                onClick = onDismiss, modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(if (applying) R.string.keybox_pool_applying else R.string.keybox_pool_apply),
                enabled = !loading && !applying && candidate != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    val chosen = candidate ?: return@TextButton
                    applying = true
                    scope.launch {
                        try {
                            status = if (KeyboxPoolRepository.apply(context, chosen)) successText else failureText
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = failureText
                        } finally { applying = false }
                    }
                },
            )
        }
    }
}

private fun poolTime(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
