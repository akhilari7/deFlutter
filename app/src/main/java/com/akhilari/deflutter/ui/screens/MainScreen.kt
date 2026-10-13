package com.akhilari.deflutter.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akhilari.deflutter.data.models.FunctionEntry
import com.akhilari.deflutter.data.models.InspectConfig
import com.akhilari.deflutter.data.models.InstalledApp
import com.akhilari.deflutter.data.models.ScanResult
import androidx.compose.runtime.saveable.rememberSaveable
import com.akhilari.deflutter.data.shell.RootShell
import com.akhilari.deflutter.data.symbols.SymbolManager
import com.akhilari.deflutter.ui.components.AppTopBar
import com.akhilari.deflutter.ui.theme.*
import kotlinx.coroutines.launch

enum class AppTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CONFIG("Config", Icons.Default.Tune),
    FUNCTIONS("Functions", Icons.Default.ListAlt),
    TRACE("Trace Lab", Icons.Default.Code)
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by rememberSaveable { mutableStateOf(AppTab.FUNCTIONS) }
    var isRootAvailable by remember { mutableStateOf(false) }

    var config by remember { mutableStateOf(InspectConfig()) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }

    var isLoadingResults by remember { mutableStateOf(false) }
    var isSavingConfig by remember { mutableStateOf(false) }
    var isRestartingApp by remember { mutableStateOf(false) }

    var selectedFunctionForFrida by remember { mutableStateOf<FunctionEntry?>(null) }
    var batchFunctionsForFrida by remember { mutableStateOf<List<FunctionEntry>>(emptyList()) }

    // Hoisted Frida session & console logs to preserve state across tab switches
    var fridaSession by remember { mutableStateOf<RootShell.FridaSession?>(null) }
    var isFridaHookRunning by remember { mutableStateOf(false) }
    val fridaConsoleLogs = remember { mutableStateListOf<String>() }

    DisposableEffect(Unit) {
        onDispose {
            fridaSession?.stop()
        }
    }

    val activeTarget by com.akhilari.deflutter.server.TraceLogBroadcaster.activeTarget.collectAsState()
    val globalHookRunning by com.akhilari.deflutter.server.TraceLogBroadcaster.isHookRunning.collectAsState()
    val globalEngine by com.akhilari.deflutter.server.TraceLogBroadcaster.activeEngine.collectAsState()

    LaunchedEffect(globalHookRunning, globalEngine) {
        isFridaHookRunning = globalHookRunning && globalEngine == "FRIDA"
    }

    // Sync state when activeTarget is updated externally (via Host Bridge or Web Terminal)
    LaunchedEffect(activeTarget) {
        if (activeTarget.isNotBlank() && activeTarget != config.process) {
            val currentCfg = RootShell.loadCurrentConfig() ?: config
            val updatedCfg = currentCfg.copy(process = activeTarget)
            config = updatedCfg

            isLoadingResults = true
            val res = RootShell.loadScanResult(activeTarget)
            scanResult = res.getOrNull()
            SymbolManager.autoLoadForPackage(activeTarget)
            isLoadingResults = false
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        isRootAvailable = RootShell.isRootAvailable()

        // Load config from disk
        val currentCfg = RootShell.loadCurrentConfig()
        val activeProc = currentCfg?.process ?: config.process
        if (currentCfg != null) {
            config = currentCfg
        }
        if (activeProc.isNotBlank()) {
            com.akhilari.deflutter.server.TraceLogBroadcaster.updateTarget(activeProc)
        }

        // Load installed apps
        installedApps = RootShell.loadInstalledApps(context)

        // Load scan results
        isLoadingResults = true
        val res = RootShell.loadScanResult(activeProc)
        scanResult = res.getOrNull()
        SymbolManager.autoLoadForPackage(activeProc)
        isLoadingResults = false
    }

    val reloadData = {
        scope.launch {
            isLoadingResults = true
            isRootAvailable = RootShell.isRootAvailable()
            val currentCfg = RootShell.loadCurrentConfig()
            val activeProc = com.akhilari.deflutter.server.TraceLogBroadcaster.activeTarget.value.ifBlank {
                currentCfg?.process ?: config.process
            }
            if (currentCfg != null && activeProc.isNotBlank()) {
                config = currentCfg.copy(process = activeProc)
            }

            val res = RootShell.loadScanResult(activeProc)
            scanResult = res.getOrNull()
            SymbolManager.autoLoadForPackage(activeProc)
            isLoadingResults = false

            if (scanResult != null && scanResult?.functions?.isNotEmpty() == true) {
                Toast.makeText(context, "Loaded ${scanResult?.functions?.size} discovered functions for $activeProc", Toast.LENGTH_SHORT).show()
            } else if (SymbolManager.isLoaded.value) {
                Toast.makeText(context, "Loaded ${SymbolManager.functionsCount} symbols for $activeProc", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No results or symbols found for $activeProc", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                isRootAvailable = isRootAvailable,
                onRefresh = { reloadData() }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SlateSurface,
                tonalElevation = 8.dp
            ) {
                AppTab.values().forEach { tab ->
                    val globalHookRunning by com.akhilari.deflutter.server.TraceLogBroadcaster.isHookRunning.collectAsState()
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            if (tab == AppTab.TRACE && (isFridaHookRunning || globalHookRunning)) {
                                BadgedBox(badge = {
                                    Badge(containerColor = EmeraldSuccess) {
                                        Text("${fridaConsoleLogs.size}", fontSize = 9.sp, color = SlateBackground)
                                    }
                                }) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanPrimary,
                            selectedTextColor = CyanPrimary,
                            indicatorColor = CyanPrimary.copy(alpha = 0.15f),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        },
        containerColor = SlateBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(SlateBackground)
        ) {
            when (currentTab) {
                AppTab.CONFIG -> {
                    ConfigScreen(
                        config = config,
                        installedApps = installedApps,
                        onConfigChange = {
                            config = it
                            if (it.process.isNotBlank()) {
                                com.akhilari.deflutter.server.TraceLogBroadcaster.updateTarget(it.process)
                            }
                        },
                        onSaveConfig = {
                            scope.launch {
                                isSavingConfig = true
                                val res = RootShell.saveConfig(config)
                                isSavingConfig = false
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Configuration deployed to Zygisk!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onRestartApp = {
                            scope.launch {
                                isRestartingApp = true
                                RootShell.saveConfig(config)
                                val res = RootShell.restartTargetApp(config.process)
                                isRestartingApp = false
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Restarted ${config.process}!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        isSaving = isSavingConfig,
                        isRestarting = isRestartingApp
                    )
                }

                AppTab.FUNCTIONS -> {
                    ResultsScreen(
                        scanResult = scanResult,
                        isLoading = isLoadingResults,
                        isDiscoveryEnabled = config.discoveryEnabled,
                        onReload = { reloadData() },
                        onInspectInFrida = { func ->
                            selectedFunctionForFrida = func
                            batchFunctionsForFrida = emptyList()
                            currentTab = AppTab.TRACE
                        },
                        onBatchInspectInFrida = { batchList ->
                            selectedFunctionForFrida = null
                            batchFunctionsForFrida = batchList
                            currentTab = AppTab.TRACE
                        },
                        onAddKeywordAndRescan = { keywordTokens ->
                            scope.launch {
                                isLoadingResults = true
                                val newKeywords = (config.keywords + keywordTokens).distinct()
                                val updatedConfig = config.copy(keywords = newKeywords)
                                config = updatedConfig
                                RootShell.saveConfig(updatedConfig)

                                val pid = RootShell.getRunningAppPid(updatedConfig.process)
                                if (pid != null) {
                                    // Dual-Engine Synergy: Live in-memory scan (Zero restart!)
                                    Toast.makeText(context, "⚡ Live Scanning memory for: ${keywordTokens.take(2).joinToString(", ")}...", Toast.LENGTH_SHORT).show()
                                    val liveRes = RootShell.scanRunningAppWithFrida(updatedConfig.process, keywordTokens)
                                    if (liveRes.isSuccess) {
                                        val newFuncs = liveRes.getOrNull() ?: emptyList()
                                        RootShell.mergeScanResultsIntoCache(updatedConfig.process, newFuncs)
                                        val reloadRes = RootShell.loadScanResult(updatedConfig.process)
                                        scanResult = reloadRes.getOrNull()
                                        isLoadingResults = false
                                        Toast.makeText(context, "⚡ Live scan found ${newFuncs.size} functions! Total: ${scanResult?.functions?.size ?: 0}", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                }

                                // Fallback: Cold restart via Zygisk
                                Toast.makeText(context, "Restarting ${updatedConfig.process} to resolve with Zygisk...", Toast.LENGTH_SHORT).show()
                                RootShell.restartTargetApp(updatedConfig.process)
                                kotlinx.coroutines.delay(2500)

                                val res = RootShell.loadScanResult(updatedConfig.process)
                                scanResult = res.getOrNull()
                                isLoadingResults = false

                                val count = scanResult?.functions?.count { f -> keywordTokens.any { kw -> f.matches(kw) } } ?: 0
                                if (count > 0) {
                                    Toast.makeText(context, "Discovered $count matching functions!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Scan complete (${scanResult?.functions?.size ?: 0} total functions)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                AppTab.TRACE -> {
                    FridaStudioScreen(
                        targetProcess = scanResult?.process ?: config.process,
                        targetLib = scanResult?.lib ?: "libapp.so",
                        selectedFunction = selectedFunctionForFrida,
                        batchFunctions = batchFunctionsForFrida,
                        allFunctions = scanResult?.functions ?: emptyList(),
                        onSelectFunction = {
                            selectedFunctionForFrida = it
                            if (it != null) batchFunctionsForFrida = emptyList()
                        },
                        isHookRunning = isFridaHookRunning,
                        onHookRunningChange = { isFridaHookRunning = it },
                        fridaSession = fridaSession,
                        onFridaSessionChange = { fridaSession = it },
                        consoleLogs = fridaConsoleLogs
                    )
                }
            }
        }
    }
}
