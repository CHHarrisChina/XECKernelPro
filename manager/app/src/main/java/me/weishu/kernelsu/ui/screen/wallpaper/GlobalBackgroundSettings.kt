package me.weishu.kernelsu.ui.screen.wallpaper

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcRadius
import me.weishu.kernelsu.ui.util.GlobalBackgroundStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import kotlin.math.roundToInt

@Composable
fun GlobalBackgroundSettings() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { GlobalBackgroundStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val perform: (suspend () -> Unit) -> Unit = { operation ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    operation()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.global_background_failed, Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickerOpen = false
        if (uri != null) perform { store.importImage(uri) }
    }
    val enabled = !busy && !pickerOpen
    Card(
        cornerRadius = XcRadius.md,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.defaultColors(color = Xc.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.global_background_title), style = Xc.typography.section, color = Xc.colors.text)
            Text(stringResource(R.string.global_background_summary), style = Xc.typography.caption, color = Xc.colors.textSecondary)
            Text(
                stringResource(
                    when {
                        busy -> R.string.global_background_busy
                        settings.image.isEmpty() -> R.string.global_background_default
                        else -> R.string.global_background_selected
                    }
                ),
                style = Xc.typography.caption,
                color = Xc.colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.global_background_pick),
                    enabled = enabled,
                    onClick = {
                        pickerOpen = true
                        try {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (_: Exception) {
                            pickerOpen = false
                            Toast.makeText(context, R.string.global_background_failed, Toast.LENGTH_LONG).show()
                        }
                    },
                )
                TextButton(
                    text = stringResource(R.string.global_background_reset),
                    enabled = enabled && (settings.image.isNotEmpty() || settings.opacity != 100 || settings.readability != 65),
                    onClick = { perform { store.reset() } },
                )
            }
            GlobalBackgroundSlider(
                title = stringResource(R.string.global_background_opacity),
                percent = settings.opacity,
                enabled = enabled && settings.image.isNotEmpty(),
                onChange = { perform { store.setOpacity(it) } },
            )
            GlobalBackgroundSlider(
                title = stringResource(R.string.global_background_readability),
                percent = settings.readability,
                enabled = enabled && settings.image.isNotEmpty(),
                onChange = { perform { store.setReadability(it) } },
            )
        }
    }
}

@Composable
private fun GlobalBackgroundSlider(title: String, percent: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Text("$title: ${value.roundToInt()}%", style = Xc.typography.caption, color = Xc.colors.text)
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChange(value.roundToInt()) },
        valueRange = 0f..100f,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}
