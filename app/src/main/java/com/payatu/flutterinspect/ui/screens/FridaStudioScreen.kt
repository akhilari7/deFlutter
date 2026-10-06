package com.payatu.flutterinspect.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.payatu.flutterinspect.data.frida.FridaSnippetGenerator
import com.payatu.flutterinspect.data.models.FunctionEntry
import com.payatu.flutterinspect.data.shell.RootShell
import com.payatu.flutterinspect.data.symbols.SymbolManager
import com.payatu.flutterinspect.ui.theme.*
import kotlinx.coroutines.withContext
import java.io.File

enum class StudioModeTab(val title: String) {
    EDITOR("Inspector / Editor"),
    CONSOLE("Live Console")
}

enum class TracerEngine(val title: String, val badge: String, val description: String) {
    FRIDA("Frida Engine", "JS Dynamic", "Ad-hoc JS scripting, memory tampering & live function scanning"),
    DOBBY("Dobby Engine", "Native Inline", "Zero-overhead native C++ inline hooks via Zygisk & LR return detour")
}

@Composable
fun FridaStudioScreen(
    targetProcess: String,
    targetLib: String,
    selectedFunction: FunctionEntry?,
    batchFunctions: List<FunctionEntry> = emptyList(),
    allFunctions: List<FunctionEntry>,
    onSelectFunction: (FunctionEntry?) -> Unit,
    isHookRunning: Boolean,
    onHookRunningChange: (Boolean) -> Unit,
    fridaSession: RootShell.FridaSession?,
    onFridaSessionChange: (RootShell.FridaSession?) -> Unit,
    consoleLogs: androidx.compose.runtime.snapshots.SnapshotStateList<String>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val globalHookRunning by com.payatu.flutterinspect.server.TraceLogBroadcaster.isHookRunning.collectAsState()
    val globalEngine by com.payatu.flutterinspect.server.TraceLogBroadcaster.activeEngine.collectAsState()
    val globalActiveHooks by com.payatu.flutterinspect.server.TraceLogBroadcaster.activeHooks.collectAsState()

    var selectedEngine by remember { 
        mutableStateOf(if (globalHookRunning && globalEngine == "DOBBY") TracerEngine.DOBBY else TracerEngine.FRIDA) 
    }
    var activeTab by remember { mutableStateOf(if (isHookRunning || (globalHookRunning && globalEngine == "DOBBY")) StudioModeTab.CONSOLE else StudioModeTab.EDITOR) }
    var selectedTemplate by remember { mutableStateOf(FridaSnippetGenerator.TemplateType.DART_ARG_INSPECTOR) }
    var showFunctionMenu by remember { mutableStateOf(false) }
    var isAttachMode by remember { mutableStateOf(false) }
    var isVerbose by remember { mutableStateOf(false) }

    // Dobby Engine specific state
    var dobbySession by remember { mutableStateOf<RootShell.DobbySession?>(null) }
    var isDobbyRunning by remember { mutableStateOf(false) }
    val isDobbyActive = isDobbyRunning || (globalHookRunning && globalEngine == "DOBBY")
    var traceReturnValue by remember { mutableStateOf(false) }
    var dobbyBasicTraceOnly by remember { mutableStateOf(false) }

    // Bi-directional state sync with Web Terminal and global broadcaster
    LaunchedEffect(globalHookRunning, globalEngine) {
        if (globalHookRunning && globalEngine == "DOBBY") {
            isDobbyRunning = true
            selectedEngine = TracerEngine.DOBBY
            onHookRunningChange(true)
        } else if (!globalHookRunning && globalEngine == "DOBBY") {
            isDobbyRunning = false
            onHookRunningChange(false)
        }
    }

    // Auto-detect and load symbols.json if not already loaded
    LaunchedEffect(targetProcess) {
        if (targetProcess.isNotBlank()) {
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateTarget(targetProcess)
        }
        if (targetProcess.isNotBlank() && !SymbolManager.isLoaded.value) {
            withContext(Dispatchers.IO) {
                SymbolManager.autoLoadForPackage(targetProcess)
            }
        }
    }

    // Mirror logs arriving from Web Terminal or background into consoleLogs
    LaunchedEffect(Unit) {
        com.payatu.flutterinspect.server.TraceLogBroadcaster.logsFlow.collect { line ->
            if (line == "__CLEAR_LOGS__") {
                consoleLogs.clear()
            } else if (!consoleLogs.contains(line)) {
                consoleLogs.add(line)
            }
        }
    }

    val consoleListState = rememberLazyListState()

    val effectiveTemplate = if (selectedFunction == null) {
        FridaSnippetGenerator.TemplateType.MULTI_FUNCTION_BATCH
    } else {
        selectedTemplate
    }

    val effectiveBatchFuncs = remember(selectedFunction, batchFunctions, allFunctions) {
        if (selectedFunction == null && batchFunctions.isNotEmpty()) {
            batchFunctions.take(20)
        } else {
            allFunctions.take(20)
        }
    }

    val scriptCode = remember(effectiveTemplate, targetLib, selectedFunction, effectiveBatchFuncs, isVerbose) {
        FridaSnippetGenerator.generateSnippet(
            templateType = effectiveTemplate,
            targetLib = targetLib.ifBlank { "libapp.so" },
            targetFunc = selectedFunction,
            allFuncs = effectiveBatchFuncs,
            isVerbose = isVerbose
        )
    }

    // Auto scroll console to latest item
    LaunchedEffect(consoleLogs.size) {
        if (consoleLogs.isNotEmpty()) {
            consoleListState.animateScrollToItem(consoleLogs.size - 1)
        }
    }

    fun startFrida() {
        scope.launch {
            val available = RootShell.isFridaInjectAvailable()
            if (!available) {
                Toast.makeText(context, "frida-inject not found at /data/local/tmp/frida-inject", Toast.LENGTH_LONG).show()
                return@launch
            }

            consoleLogs.clear()
            com.payatu.flutterinspect.server.TraceLogBroadcaster.clear()
            consoleLogs.add("[*] Initializing Frida in-app interceptor...")
            consoleLogs.add("[*] Target App: $targetProcess")
            consoleLogs.add("[*] Mode: ${if (isAttachMode) "ATTACH (Live PID)" else "SPAWN (Cold Start)"}")
            val funcDesc = when {
                selectedFunction != null -> "${selectedFunction.name} (${selectedFunction.offset})"
                batchFunctions.isNotEmpty() -> "${batchFunctions.size} Selected Functions (Batch Mode)"
                else -> "All Functions (Batch Mode)"
            }
            consoleLogs.add("[*] Function: $funcDesc")
            if (isAttachMode) {
                consoleLogs.add("[*] Attaching to running process without restarting...")
            } else {
                consoleLogs.add("[*] Spawning target process and injecting hook...")
            }

            onHookRunningChange(true)
            activeTab = StudioModeTab.CONSOLE
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateTarget(targetProcess)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateHookState(true, "FRIDA")

            val res = RootShell.startFridaInterceptor(
                packageName = targetProcess,
                scriptContent = scriptCode,
                isAttachMode = isAttachMode,
                onLogLine = { line ->
                    val dec = SymbolManager.decorateLogLine(line)
                    consoleLogs.add(dec)
                    com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(dec)
                }
            )

            if (res.isSuccess) {
                onFridaSessionChange(res.getOrNull())
                com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog("[✓] Frida interceptor active for $targetProcess")
            } else {
                onHookRunningChange(false)
                com.payatu.flutterinspect.server.TraceLogBroadcaster.updateHookState(false, "FRIDA")
                val errMsg = "[-] Failed to start Frida: ${res.exceptionOrNull()?.message}"
                consoleLogs.add(errMsg)
                com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(errMsg)
            }
        }
    }

    fun stopFrida() {
        fridaSession?.stop()
        onFridaSessionChange(null)
        onHookRunningChange(false)
        com.payatu.flutterinspect.server.TraceLogBroadcaster.updateHookState(false, "FRIDA")
        val msg = "[*] Interceptor detached & stopped."
        consoleLogs.add(msg)
        com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(msg)
    }

    fun startDobby() {
        scope.launch {
            if (selectedFunction == null && batchFunctions.isEmpty() && allFunctions.isEmpty()) {
                Toast.makeText(context, "No functions available to hook", Toast.LENGTH_SHORT).show()
                return@launch
            }

            consoleLogs.clear()
            com.payatu.flutterinspect.server.TraceLogBroadcaster.clear()
            val msg1 = "[★] Initializing Dobby Native Inline Tracer..."
            val msg2 = "[*] Target App: $targetProcess"
            consoleLogs.add(msg1)
            consoleLogs.add(msg2)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(msg1)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(msg2)

            val targetList = if (selectedFunction != null) {
                listOf(selectedFunction)
            } else if (batchFunctions.isNotEmpty()) {
                batchFunctions.take(20)
            } else {
                allFunctions.take(20)
            }
            val modeNote = if (selectedFunction != null) "Single" else if (batchFunctions.isNotEmpty()) "Batch (${targetList.size} selected)" else "Batch (Top ${targetList.size})"
            val msg3 = "[*] Registering ${targetList.size} function(s) natively via Zygisk lifecycle ($modeNote):"
            consoleLogs.add(msg3)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(msg3)

            targetList.forEach { f ->
                val fMsg = "    ├── Offset: ${f.offset} (${f.name})"
                consoleLogs.add(fMsg)
                com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(fMsg)
            }

            val hooks = targetList.mapNotNull { f ->
                val off = f.offset.removePrefix("0x").toLongOrNull(16)
                    ?: f.offset.toLongOrNull()
                if (off != null) {
                    val symFunc = SymbolManager.findFunction(f.offset)
                    val paramNames = symFunc?.params?.map { it.name } ?: emptyList()
                    com.payatu.flutterinspect.data.models.HookTargetConfig(
                        offset = off,
                        name = f.name,
                        traceReturn = traceReturnValue,
                        params = paramNames
                    )
                } else null
            }

            if (hooks.isEmpty()) {
                Toast.makeText(context, "Invalid function offset(s)", Toast.LENGTH_SHORT).show()
                return@launch
            }

            isDobbyRunning = true
            onHookRunningChange(true)
            activeTab = StudioModeTab.CONSOLE
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateTarget(targetProcess)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateActiveHooks(hooks)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateHookState(true, "DOBBY")

            val res = RootShell.startDobbyTracer(
                packageName = targetProcess,
                hooks = hooks,
                onLogLine = { line ->
                    val dec = SymbolManager.decorateLogLine(line)
                    consoleLogs.add(dec)
                    com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(dec)
                }
            )

            if (res.isSuccess) {
                dobbySession = res.getOrNull()
                val okMsg1 = "[✓] Dobby hooks dispatched to Zygisk. Live memory interception active!"
                val okMsg2 = "[*] Listening for native function hits (logcat -s FlutterInspect-Tracer)..."
                consoleLogs.add(okMsg1)
                consoleLogs.add(okMsg2)
                com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(okMsg1)
                com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(okMsg2)
            } else {
                isDobbyRunning = false
                onHookRunningChange(false)
                com.payatu.flutterinspect.server.TraceLogBroadcaster.updateHookState(false, "DOBBY")
                val failMsg = "[-] Failed to start Dobby tracer: ${res.exceptionOrNull()?.message}"
                consoleLogs.add(failMsg)
                com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(failMsg)
            }
        }
    }

    fun stopDobby() {
        scope.launch {
            dobbySession?.stop()
            dobbySession = null
            isDobbyRunning = false
            onHookRunningChange(false)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateHookState(false, "DOBBY")
            com.payatu.flutterinspect.server.TraceLogBroadcaster.updateActiveHooks(emptyList())
            val target = targetProcess.ifBlank { com.payatu.flutterinspect.server.TraceLogBroadcaster.activeTarget.value }
            if (target.isNotBlank()) {
                RootShell.clearDobbyHooks(target)
            }
            val msg = "[*] Dobby native hooks cleared and detached."
            consoleLogs.add(msg)
            com.payatu.flutterinspect.server.TraceLogBroadcaster.postLog(msg)
        }
    }

    val anyHookRunning = isHookRunning || isDobbyActive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compact Target Header Card
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (anyHookRunning) EmeraldSuccess else SlateBorder, RoundedCornerShape(10.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Target & Function Info (Single compact line)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (anyHookRunning) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(EmeraldSuccess)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    val resolvedSym = selectedFunction?.let { SymbolManager.resolveFunction(it.offset) }
                    val displayFunc = when {
                        selectedFunction != null -> if (resolvedSym != null) "${selectedFunction.offset} [$resolvedSym]" else "${selectedFunction.offset} [${selectedFunction.name}]"
                        batchFunctions.isNotEmpty() -> "Batch (${batchFunctions.size} selected functions)"
                        else -> "All Functions (Batch Mode)"
                    }

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "TARGET: $targetProcess",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = CyanPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = displayFunc,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Action: Change Target Dropdown
                Box {
                    TextButton(
                        onClick = { showFunctionMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Change", color = CyanPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    DropdownMenu(
                        expanded = showFunctionMenu,
                        onDismissRequest = { showFunctionMenu = false },
                        modifier = Modifier.background(SlateSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Functions (Batch Mode)", color = TextPrimary) },
                            onClick = {
                                onSelectFunction(null)
                                selectedTemplate = FridaSnippetGenerator.TemplateType.MULTI_FUNCTION_BATCH
                                showFunctionMenu = false
                            }
                        )
                        allFunctions.forEach { func ->
                            val sym = SymbolManager.resolveFunction(func.offset)
                            val label = if (sym != null) "${func.offset} [$sym]" else "${func.offset} - ${func.name}"
                            DropdownMenuItem(
                                text = { Text(label, color = TextPrimary, fontSize = 12.sp) },
                                onClick = {
                                    onSelectFunction(func)
                                    showFunctionMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Engine Selector: Frida vs Dobby
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SlateSurface)
                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TracerEngine.values().forEach { engine ->
                val isSelected = selectedEngine == engine
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) {
                                if (engine == TracerEngine.DOBBY) PurpleHighlight.copy(alpha = 0.22f)
                                else CyanPrimary.copy(alpha = 0.22f)
                            } else Color.Transparent
                        )
                        .border(
                            1.dp,
                            if (isSelected) {
                                if (engine == TracerEngine.DOBBY) PurpleHighlight else CyanPrimary
                            } else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            if (!anyHookRunning) {
                                selectedEngine = engine
                            } else {
                                Toast.makeText(context, "Stop running hook before switching engines", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            if (engine == TracerEngine.DOBBY) Icons.Default.RocketLaunch else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isSelected) {
                                if (engine == TracerEngine.DOBBY) PurpleHighlight else CyanPrimary
                            } else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = engine.title,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                color = if (isSelected) TextPrimary else TextMuted
                            )
                            Text(
                                text = engine.badge,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = if (isSelected) {
                                    if (engine == TracerEngine.DOBBY) PurpleHighlight else CyanPrimary
                                } else TextMuted
                            )
                        }
                    }
                }
            }
        }

        // Sub Tabs: Editor vs Live Console
        TabRow(
            selectedTabIndex = activeTab.ordinal,
            containerColor = SlateSurface,
            contentColor = if (selectedEngine == TracerEngine.DOBBY) PurpleHighlight else CyanPrimary,
            divider = {}
        ) {
            StudioModeTab.values().forEach { tab ->
                Tab(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (tab == StudioModeTab.EDITOR) Icons.Default.Code else Icons.Default.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.title,
                                fontSize = 12.sp,
                                fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                            if (tab == StudioModeTab.CONSOLE && consoleLogs.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (anyHookRunning) EmeraldSuccess else SlateBorder)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${consoleLogs.size}",
                                        color = if (anyHookRunning) SlateBackground else TextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

        // Tab Content
        if (activeTab == StudioModeTab.EDITOR) {
            if (selectedEngine == TracerEngine.DOBBY) {
                // Dobby Native Inspector Overview Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PurpleHighlight.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.RocketLaunch, contentDescription = null, tint = PurpleHighlight, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Native Dobby Tracer",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                                    color = TextPrimary
                                )
                                Text(
                                    "In-process ARM64 trampoline hook via Zygisk lifecycle",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    color = TextSecondary
                                )
                            }
                        }

                        HorizontalDivider(color = SlateBorder)

                        // Target Info
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("TARGET FUNCTION", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = TextMuted)
                            Text(
                                selectedFunction?.name ?: (if (batchFunctions != null) "Selected Batch (${batchFunctions.size} functions)" else "Batch Mode"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                color = CyanPrimary
                            )
                            Text(
                                "Offset: ${selectedFunction?.offset ?: "Batch (${batchFunctions?.size ?: 0} targets)"} | Target Lib: $targetLib",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                color = TextSecondary
                            )
                        }

                        HorizontalDivider(color = SlateBorder)

                        // Toggles & Capabilities
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("TRACING CONFIGURATION", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = TextMuted)

                            // Toggle 1: Basic Call Tracing vs Arg Inspector
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SlateSurfaceVariant)
                                    .clickable { dobbyBasicTraceOnly = !dobbyBasicTraceOnly }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Timeline, contentDescription = null, tint = if (dobbyBasicTraceOnly) PurpleHighlight else TextMuted, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Basic Call Tracing", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (dobbyBasicTraceOnly) "Active: logs function calls & returns only (Arg Inspector OFF)" else "Disabled: full Dart Argument Inspector active",
                                            fontSize = 9.sp,
                                            color = if (dobbyBasicTraceOnly) PurpleHighlight else TextSecondary
                                        )
                                    }
                                }
                                Switch(
                                    checked = dobbyBasicTraceOnly,
                                    onCheckedChange = { dobbyBasicTraceOnly = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = PurpleHighlight,
                                        checkedTrackColor = PurpleHighlight.copy(alpha = 0.3f)
                                    )
                                )
                            }

                            // Toggle 2: Verbose Output Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SlateSurfaceVariant)
                                    .clickable { isVerbose = !isVerbose }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isVerbose) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = if (isVerbose) PurpleHighlight else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Verbose Output", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (isVerbose) "Showing raw addresses & binary byte buffer hex dumps" else "Clean mode: raw addresses & hex dumps hidden (only decoded semantic data)",
                                            fontSize = 9.sp,
                                            color = if (isVerbose) PurpleHighlight else TextSecondary
                                        )
                                    }
                                }
                                Switch(
                                    checked = isVerbose,
                                    onCheckedChange = { isVerbose = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = PurpleHighlight,
                                        checkedTrackColor = PurpleHighlight.copy(alpha = 0.3f)
                                    )
                                )
                            }

                            // Toggle 3: Trace Returns (LR Detour)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SlateSurfaceVariant)
                                    .clickable { traceReturnValue = !traceReturnValue }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Reply, contentDescription = null, tint = PurpleHighlight, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Trace Returns (LR Detour)", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (traceReturnValue) "Active: detours LR to capture return value & latency" else "Disabled: return detour skipped",
                                            fontSize = 9.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                Switch(
                                    checked = traceReturnValue,
                                    onCheckedChange = { traceReturnValue = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = PurpleHighlight,
                                        checkedTrackColor = PurpleHighlight.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
            if (selectedFunction == null) {
                // Batch mode header banner
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = SlateSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().border(1.dp, PurpleHighlight.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = PurpleHighlight, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Batch Interceptor (${effectiveBatchFuncs.size} targets)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PurpleHighlight)
                            Text("Simultaneously hooks all ${effectiveBatchFuncs.size} selected functions in libapp.so with offset tracking", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
            } else {
                // Single Function: 2 tabs (Argument Inspector & Basic Tracer)
                val singleTemplates = listOf(
                    FridaSnippetGenerator.TemplateType.DART_ARG_INSPECTOR,
                    FridaSnippetGenerator.TemplateType.BASIC_TRACE
                )
                val activeSingleIndex = singleTemplates.indexOf(
                    if (selectedTemplate in singleTemplates) selectedTemplate else FridaSnippetGenerator.TemplateType.DART_ARG_INSPECTOR
                ).coerceAtLeast(0)

                TabRow(
                    selectedTabIndex = activeSingleIndex,
                    containerColor = SlateSurfaceVariant,
                    contentColor = CyanPrimary,
                    divider = {}
                ) {
                    singleTemplates.forEach { template ->
                        Tab(
                            selected = selectedTemplate == template,
                            onClick = { selectedTemplate = template },
                            text = {
                                Text(
                                    text = template.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTemplate == template) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                Text(
                    text = selectedTemplate.description,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = TextSecondary
                )
            }

            // Verbose Output Toggle for Frida
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateSurfaceVariant)
                    .clickable { isVerbose = !isVerbose }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isVerbose) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = if (isVerbose) CyanPrimary else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Verbose Output", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isVerbose) "Showing ASLR addresses (0x7a...), thread IDs & return pointers" else "Clean output: raw addresses, thread IDs & pointers hidden",
                            fontSize = 9.sp,
                            color = if (isVerbose) CyanPrimary else TextSecondary
                        )
                    }
                }
                Switch(
                    checked = isVerbose,
                    onCheckedChange = { isVerbose = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanPrimary,
                        checkedTrackColor = CyanPrimary.copy(alpha = 0.3f)
                    )
                )
            }

            // Monospace Code Editor / Viewer Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CodeBackground)
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(vScroll)
                        .horizontalScroll(hScroll)
                ) {
                    Text(
                        text = scriptCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = CodeString,
                        lineHeight = 16.sp
                    )
                }
            }

            // Copy & Share Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Frida Script", scriptCode))
                        Toast.makeText(context, "Script copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SlateSurfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("COPY SCRIPT", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, scriptCode)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Frida Script")
                        context.startActivity(shareIntent)
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SHARE", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 11.sp)
                }
            }
            }
        } else {
            // LIVE CONSOLE TAB
            val filteredLogs = remember(consoleLogs.size, isVerbose, dobbyBasicTraceOnly, selectedEngine) {
                val list = mutableListOf<String>()
                for (rawLine in consoleLogs) {
                    if (selectedEngine == TracerEngine.DOBBY && dobbyBasicTraceOnly) {
                        val trimmed = rawLine.trimStart()
                        if (trimmed.startsWith("├──") || trimmed.startsWith("└──") || trimmed.startsWith("│") ||
                            trimmed.contains("(no active arguments or frame data detected)")) {
                            continue
                        }
                    }
                    if (!isVerbose) {
                        val trimmed = rawLine.trimStart()
                        if (trimmed.startsWith("├──") || trimmed.startsWith("└──") || trimmed.startsWith("│")) {
                            val afterColon = if (rawLine.contains(": ")) rawLine.substringAfter(": ") else rawLine

                            // Check for decorators:
                            val hasClassDecorator = Regex("""cid=0x[0-9a-fA-F]+:\s*[A-Za-z0-9_]""").containsMatchIn(rawLine)
                            val hasParamDecorator = Regex("""Arg\d+\s*\((?!0x)[A-Za-z0-9_:]+""").containsMatchIn(rawLine)
                            val hasSlotDecorator = Regex("""\[FP[+-]0x[0-9a-fA-F]+\]\s*\((?!0x)[A-Za-z0-9_]+""").containsMatchIn(rawLine)

                            // Check for semantic value:
                            val withoutAsciiPreview = afterColon.replace(Regex("""\s*\("[^"]*"\)\s*"""), "")
                            val hasSemanticString = withoutAsciiPreview.contains("\"")
                            val hasSemanticInt = afterColon.contains("(int)")
                            val hasMap = afterColon.contains("Map(len=")

                            val isValuable = hasClassDecorator || hasParamDecorator || hasSlotDecorator || hasSemanticString || hasSemanticInt || hasMap
                            if (!isValuable) {
                                continue
                            }

                            // Clean up clutter: strip raw hex dumps and ascii preview from decorated byte buffers
                            var cleaned = rawLine
                                .replace(Regex(""",\s*0x[0-9a-fA-F]{6,16}"""), "")
                                .replace(Regex("""\s*\((0x[0-9a-fA-F]{6,16})\)"""), "")

                            if ((cleaned.contains("Uint8List(") || cleaned.contains("Bytes(")) && cleaned.contains("-> [")) {
                                if (!(cleaned.contains("Instance(") && hasSemanticString)) {
                                    cleaned = cleaned.replace(Regex("""\s*->\s*\[[0-9a-fA-F\s.]+\](?:\s*\("[^"]*"\))?"""), "")
                                }
                            }
                            list.add(cleaned)
                        } else {
                            val cleaned = rawLine
                                .replace(Regex(""",\s*0x[0-9a-fA-F]{6,16}"""), "")
                                .replace(Regex("""\s*\((0x[0-9a-fA-F]{6,16})\)"""), "")
                            list.add(cleaned)
                        }
                    } else {
                        list.add(rawLine)
                    }
                }

                // If in clean mode, fix trailing tree connectors so the last param in each tree block uses └──
                if (!isVerbose) {
                    for (i in list.indices) {
                        val cur = list[i].trimStart()
                        if (cur.startsWith("├──")) {
                            val nextIsParam = (i + 1 < list.size) && (list[i + 1].trimStart().startsWith("├──") || list[i + 1].trimStart().startsWith("└──"))
                            if (!nextIsParam) {
                                list[i] = list[i].replaceFirst("├──", "└──")
                            }
                        }
                    }
                }
                list
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Console Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (anyHookRunning) "INTERCEPTOR STREAMING" else "CONSOLE LOGS",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                        color = if (anyHookRunning) EmeraldSuccess else TextMuted
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Quick Verbose filter chip in console
                        FilterChip(
                            selected = isVerbose,
                            onClick = { isVerbose = !isVerbose },
                            label = { Text(if (isVerbose) "Verbose" else "Clean", fontSize = 10.sp) },
                            leadingIcon = {
                                Icon(
                                    if (isVerbose) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (selectedEngine == TracerEngine.DOBBY) PurpleHighlight.copy(alpha = 0.2f) else CyanPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = if (selectedEngine == TracerEngine.DOBBY) PurpleHighlight else CyanPrimary,
                                selectedLeadingIconColor = if (selectedEngine == TracerEngine.DOBBY) PurpleHighlight else CyanPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isVerbose,
                                borderColor = if (isVerbose) (if (selectedEngine == TracerEngine.DOBBY) PurpleHighlight else CyanPrimary) else SlateBorder
                            ),
                            modifier = Modifier.height(28.dp)
                        )

                        if (selectedEngine == TracerEngine.DOBBY) {
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = dobbyBasicTraceOnly,
                                onClick = { dobbyBasicTraceOnly = !dobbyBasicTraceOnly },
                                label = { Text(if (dobbyBasicTraceOnly) "Calls Only" else "Args", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PurpleHighlight.copy(alpha = 0.2f),
                                    selectedLabelColor = PurpleHighlight
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = dobbyBasicTraceOnly,
                                    borderColor = if (dobbyBasicTraceOnly) PurpleHighlight else SlateBorder
                                ),
                                modifier = Modifier.height(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = {
                                val allText = filteredLogs.joinToString("\n")
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Tracer Logs", allText))
                                Toast.makeText(context, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            enabled = filteredLogs.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                consoleLogs.clear()
                                com.payatu.flutterinspect.server.TraceLogBroadcaster.clear()
                            },
                            enabled = consoleLogs.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear", tint = RoseError, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Monospace Terminal Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CodeBackground)
                        .border(1.dp, if (anyHookRunning) EmeraldSuccess.copy(alpha = 0.5f) else SlateBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (consoleLogs.isEmpty()) "Ready to intercept." else "Logs hidden by current filter.",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = if (consoleLogs.isEmpty()) "Tap 'RUN HOOK' or 'HOOK WITH DOBBY' below." else "Toggle 'Calls Only' or 'Clean' above to reveal.",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = consoleListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredLogs) { line ->
                                val color = when {
                                    line.startsWith("[★]") || line.startsWith("[+]") -> EmeraldSuccess
                                    line.startsWith("[✓]") -> CyanPrimary
                                    line.startsWith("[-]") || line.contains("error", ignoreCase = true) -> RoseError
                                    line.startsWith("[*]") -> AmberWarning
                                    line.contains("├──") || line.contains("└──") -> {
                                        if (line.contains("\"")) EmeraldSuccess else CyanPrimary
                                    }
                                    line.contains("RETURN") -> PurpleHighlight
                                    line.contains("[FP") || line.contains("[Stack") || line.contains("offset=") -> CyanPrimary
                                    else -> TextPrimary
                                }
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = color,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Dual-Engine In-App Hook Action Buttons
        if (selectedEngine == TracerEngine.DOBBY) {
            if (!isDobbyActive) {
                Button(
                    onClick = { startDobby() },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleHighlight),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.RocketLaunch, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "HOOK WITH DOBBY (NATIVE)",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            } else {
                Button(
                    onClick = { stopDobby() },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    val hookCountLabel = if (globalActiveHooks.isNotEmpty()) " (${globalActiveHooks.size} ACTIVE)" else ""
                    Text(
                        text = "STOP DOBBY HOOK$hookCountLabel",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            // Frida Action Buttons
            if (!isHookRunning) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mode Selector Toggle: Spawn vs Attach
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateSurface)
                            .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Spawn Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isAttachMode) CyanPrimary.copy(alpha = 0.18f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (!isAttachMode) CyanPrimary else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { isAttachMode = false }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.FlashOn,
                                    contentDescription = null,
                                    tint = if (!isAttachMode) CyanPrimary else TextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "SPAWN (COLD)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = if (!isAttachMode) CyanPrimary else TextMuted
                                )
                            }
                        }

                        // Attach Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isAttachMode) EmeraldSuccess.copy(alpha = 0.18f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isAttachMode) EmeraldSuccess else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { isAttachMode = true }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    tint = if (isAttachMode) EmeraldSuccess else TextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "ATTACH (LIVE)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = if (isAttachMode) EmeraldSuccess else TextMuted
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { startFrida() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAttachMode) EmeraldSuccess else CyanPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            if (isAttachMode) Icons.Default.Link else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = SlateBackground
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAttachMode) "ATTACH TO LIVE APP" else "RUN HOOK (SPAWN)",
                            fontWeight = FontWeight.Bold,
                            color = SlateBackground,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                Button(
                    onClick = { stopFrida() },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STOP INTERCEPTOR",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
