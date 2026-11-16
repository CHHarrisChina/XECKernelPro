package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import me.weishu.kernelsu.ui.screen.settings.RkpRepair
import me.weishu.kernelsu.ui.screen.settings.TeeDiagnostics
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Standalone tools dialog. Opening and refreshing only run diagnostic probes. */
@Composable
fun TeeRepairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<TeeDiagnostics.Snapshot?>(null) }
    var tool by remember { mutableStateOf<RkpRepair.Tool?>(null) }
    var busy by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf<String?>(null) }
    var diagnosticError by remember { mutableStateOf<String?>(null) }
    var softwareResult by remember { mutableStateOf<String?>(null) }
    var softwareSteps by remember { mutableStateOf<List<TeeDiagnostics.RepairStep>>(emptyList()) }
    var vendorResult by remember { mutableStateOf<String?>(null) }
    var confirmVendor by remember { mutableStateOf(false) }

    fun failure(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    suspend fun diagnose() {
        diagnosticError = null
        snapshot = null
        tool = null
        confirmVendor = false
        try {
            snapshot = TeeDiagnostics.probe()
            tool = RkpRepair.probeTool()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnosticError = failure(error)
        }
    }

    fun repair(vendor: Boolean) {
        if (busy) return
        busy = true
        confirmVendor = false
        if (vendor) vendorResult = null else {
            softwareResult = null
            softwareSteps = emptyList()
        }
        scope.launch {
            try {
                if (vendor) {
                    phase = context.getString(R.string.omy_tee_repair_vendor_running)
                    RkpRepair.installLive().fold(
                        onSuccess = {
                            vendorResult = context.getString(R.string.omy_tee_repair_vendor_done, it)
                        },
                        onFailure = {
                            vendorResult = context.getString(R.string.omy_tee_repair_failed, failure(it))
                        },
                    )
                } else {
                    phase = context.getString(R.string.omy_tee_repair_software_running)
                    // The API invokes onPhase from Dispatchers.IO; marshal UI updates to Main.
                    TeeDiagnostics.oneTapRepair(snapshot?.engineDir) { next ->
                        scope.launch { phase = next }
                    }.fold(
                        onSuccess = { steps ->
                            softwareSteps = steps
                            softwareResult = context.getString(
                                if (steps.isNotEmpty() && steps.all { it.ok }) {
                                    R.string.omy_tee_repair_software_done
                                } else {
                                    R.string.omy_tee_repair_software_partial
                                },
                            )
                        },
                        onFailure = {
                            softwareResult = context.getString(R.string.omy_tee_repair_failed, failure(it))
                        },
                    )
                }
                phase = context.getString(R.string.omy_tee_repair_diagnosing)
                diagnose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = context.getString(R.string.omy_tee_repair_failed, failure(error))
                if (vendor) vendorResult = message else softwareResult = message
            } finally {
                busy = false
                phase = null
            }
        }
    }

    LaunchedEffect(Unit) {
        phase = context.getString(R.string.omy_tee_repair_diagnosing)
        try {
            diagnose()
        } finally {
            busy = false
            phase = null
        }
    }

    XDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XDialogTitle(text = stringResource(R.string.omy_tee_repair_title))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TeeRepairNote(stringResource(R.string.omy_tee_repair_read_only))
                phase?.let { TeeRepairNote(it) }
                diagnosticError?.let { TeeRepairNote(it) }
                snapshot?.let { state ->
                    val yes = stringResource(R.string.omy_tee_repair_yes)
                    val no = stringResource(R.string.omy_tee_repair_no)
                    val unknown = stringResource(R.string.omy_tee_repair_unknown)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TeeRepairNote(stringResource(R.string.omy_tee_repair_engine,
                                state.engineDir ?: unknown,
                                if (state.engineEnabled) yes else no,
                                state.enginePid,
                                if (state.engineInjected) yes else no))
                            TeeRepairNote(stringResource(R.string.omy_tee_repair_keybox,
                                if (state.keyboxPresent) yes else no,
                                state.keyboxBytes,
                                state.keybox?.let { if (it.ok) yes else no } ?: unknown))
                            TeeRepairNote(stringResource(R.string.omy_tee_repair_targets,
                                state.targetCount, state.targetForge, state.targetPatch, state.targetAuto))
                            TeeRepairNote(stringResource(R.string.omy_tee_repair_boot,
                                state.bootStateProp ?: unknown,
                                state.flashLockedProp ?: unknown,
                                state.deviceStateProp ?: unknown))
                            (state.errors + state.keybox?.errors.orEmpty() + state.logLines)
                                .forEach { TeeRepairNote(it) }
                        }
                    }
                }
                TeeRepairNote(stringResource(R.string.omy_tee_repair_software_hint))
                TextButton(
                    text = stringResource(R.string.omy_tee_repair_software_action),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { repair(vendor = false) },
                )
                softwareResult?.let { TeeRepairNote(it) }
                softwareSteps.forEach { step ->
                    TeeRepairNote(stringResource(
                        if (step.ok) R.string.omy_tee_repair_step_ok else R.string.omy_tee_repair_step_failed,
                        step.label, step.detail,
                    ))
                }
                TeeRepairNote(stringResource(R.string.omy_tee_repair_vendor_hint))
                val vendorTool = tool
                if (vendorTool == null) {
                    TeeRepairNote(stringResource(R.string.omy_tee_repair_vendor_absent))
                } else {
                    TeeRepairNote(vendorTool.path)
                    if (!vendorTool.usable) {
                        TeeRepairNote(vendorTool.reason ?: stringResource(R.string.omy_tee_repair_vendor_unusable))
                    }
                    if (snapshot?.keyboxPresent != true) {
                        TeeRepairNote(stringResource(R.string.omy_tee_repair_vendor_needs_keybox))
                    }
                    if (confirmVendor) {
                        TeeRepairNote(stringResource(R.string.omy_tee_repair_vendor_confirm))
                        TextButton(
                            text = stringResource(R.string.omy_tee_repair_vendor_confirm_action),
                            enabled = !busy && vendorTool.usable && snapshot?.keyboxPresent == true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { repair(vendor = true) },
                        )
                        TextButton(
                            text = stringResource(R.string.omy_tee_repair_cancel),
                            onClick = { confirmVendor = false },
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.omy_tee_repair_vendor_action),
                            enabled = !busy && vendorTool.usable && snapshot?.keyboxPresent == true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { confirmVendor = true },
                        )
                    }
                }
                vendorResult?.let { TeeRepairNote(it) }
            }
            TextButton(
                text = stringResource(R.string.omy_tee_repair_refresh),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            phase = context.getString(R.string.omy_tee_repair_diagnosing)
                            try {
                                diagnose()
                            } finally {
                                busy = false
                                phase = null
                            }
                        }
                    }
                },
            )
            TextButton(
                text = stringResource(R.string.omy_tee_repair_close),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun TeeRepairNote(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = colorScheme.onSurfaceVariantSummary,
    )
}
