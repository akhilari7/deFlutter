package com.payatu.flutterinspect.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.payatu.flutterinspect.data.models.InspectConfig
import com.payatu.flutterinspect.data.models.InstalledApp
import com.payatu.flutterinspect.data.shell.RootShell
import com.payatu.flutterinspect.data.symbols.SymbolManager
import com.payatu.flutterinspect.data.symbols.DartSymbolFunction
import com.payatu.flutterinspect.server.WebTerminalServer
import com.payatu.flutterinspect.util.FridaInstaller
import com.payatu.flutterinspect.ui.components.AppPickerSheet
import com.payatu.flutterinspect.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConfigScreen(
    config: InspectConfig,
    installedApps: List<InstalledApp>,
    onConfigChange: (InspectConfig) -> Unit,
    onSaveConfig: () -> Unit,
    onRestartApp: () -> Unit,
    isSaving: Boolean,
    isRestarting: Boolean
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showAppPicker by remember { mutableStateOf(false) }
    var newKeywordInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // Web Terminal Server State
    val isServerRunning by WebTerminalServer.isRunning.collectAsState()
    val connectedClients by WebTerminalServer.connectedClients.collectAsState()
    var deviceIps by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(isServerRunning) {
        if (isServerRunning) {
            deviceIps = withContext(Dispatchers.IO) {
                WebTerminalServer.getDeviceIpAddresses()
            }
        }
    }

    // Blutter Symbols State
    val isSymbolsLoaded by SymbolManager.isLoaded.collectAsState()
    val symbolStats by SymbolManager.symbolStats.collectAsState()
    var symbolSearchQuery by remember { mutableStateOf("") }
    var symbolSearchResults by remember { mutableStateOf<List<DartSymbolFunction>>(emptyList()) }
    var isAutoDetecting by remember { mutableStateOf(false) }

    val symbolPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    val res = if (stream != null) {
                        stream.buffered(65536).use { SymbolManager.loadFromStream(it) }
                    } else {
                        Result.failure(Exception("Could not open file stream"))
                    }
                    withContext(Dispatchers.Main) {
                        if (res.isSuccess) {
                            val (fCount, cCount) = res.getOrThrow()
                            Toast.makeText(context, "Loaded $fCount functions & $cCount classes!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to load symbols: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Auto-detect symbols when target process is set or changes
    LaunchedEffect(config.process) {
        if (config.process.isNotBlank() && !SymbolManager.isLoaded.value) {
            withContext(Dispatchers.IO) {
                SymbolManager.autoLoadForPackage(config.process)
            }
        }
    }

    LaunchedEffect(symbolSearchQuery, isSymbolsLoaded) {
        if (symbolSearchQuery.isNotBlank() && isSymbolsLoaded) {
            symbolSearchResults = withContext(Dispatchers.Default) {
                SymbolManager.searchFunctions(symbolSearchQuery, limit = 20)
            }
        } else {
            symbolSearchResults = emptyList()
        }
    }

    val keywordPresets = listOf("command", "login", "http", "token", "auth", "crypto", "secret", "user")

    // Frida Engine State
    var fridaStatus by remember { mutableStateOf<FridaInstaller.FridaStatus?>(null) }
    var isCheckingFrida by remember { mutableStateOf(false) }
    var isInstallingFrida by remember { mutableStateOf(false) }
    var fridaInstallProgress by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        isCheckingFrida = true
        fridaStatus = FridaInstaller.checkStatus()
        isCheckingFrida = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Target Process
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TARGET PROCESS",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = TextMuted
                    )
                    TextButton(onClick = { showAppPicker = true }) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick App", color = CyanPrimary, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = config.process,
                    onValueChange = { onConfigChange(config.copy(process = it)) },
                    placeholder = { Text("e.g. co.ostorlab.insecure_app", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = SlateBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = SlateSurfaceVariant,
                        unfocusedContainerColor = SlateSurfaceVariant
                    ),
                    singleLine = true
                )
            }
        }

        // Section: Blutter Ground Truth Symbols & Verification
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isSymbolsLoaded) EmeraldSuccess.copy(alpha = 0.5f) else SlateBorder,
                    RoundedCornerShape(12.dp)
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = if (isSymbolsLoaded) EmeraldSuccess else CyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "BLUTTER SYMBOLS",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = if (isSymbolsLoaded) EmeraldSuccess else CyanPrimary
                        )
                    }

                    if (isSymbolsLoaded) {
                        TextButton(
                            onClick = { SymbolManager.clear() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Clear", color = RoseError, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isSymbolsLoaded) {
                        "✓ Active: ${symbolStats ?: "Symbols loaded"} from Blutter"
                    } else {
                        "Upload 'symbols.json' generated from Blutter (asm/ + blutter_frida.js) to resolve exact class/method names and eliminate false positives."
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = if (isSymbolsLoaded) EmeraldSuccess else TextSecondary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Upload / Picker button
                    Button(
                        onClick = { symbolPickerLauncher.launch("*/*") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSymbolsLoaded) SlateSurfaceVariant else CyanPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = if (isSymbolsLoaded) CyanPrimary else SlateBackground,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSymbolsLoaded) "Replace JSON" else "Load symbols.json",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSymbolsLoaded) CyanPrimary else SlateBackground
                        )
                    }

                    // Auto-detect button
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isAutoDetecting = true
                                val found = withContext(Dispatchers.IO) {
                                    SymbolManager.autoLoadForPackage(config.process)
                                }
                                isAutoDetecting = false
                                if (found) {
                                    Toast.makeText(context, "Auto-loaded symbols from storage!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No symbols.json in /sdcard/Download/FlutterInspect/${config.process}/", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isAutoDetecting,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (isAutoDetecting) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CyanPrimary)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Auto-Detect", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }

                // If symbols loaded, provide Quick Symbol Lookup right here!
                if (isSymbolsLoaded) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = SlateBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "SYMBOL LOOKUP & VERIFICATION",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = symbolSearchQuery,
                        onValueChange = { symbolSearchQuery = it },
                        placeholder = { Text("Search symbols (e.g. SecurityService, encrypt, 0x3bec20)...", color = TextMuted, fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                        },
                        trailingIcon = {
                            if (symbolSearchQuery.isNotBlank()) {
                                IconButton(onClick = { symbolSearchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = SlateBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = SlateSurfaceVariant,
                            unfocusedContainerColor = SlateSurfaceVariant
                        ),
                        singleLine = true
                    )

                    if (symbolSearchResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            symbolSearchResults.forEach { sym ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(SlateSurfaceVariant)
                                        .clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Offset", sym.offset))
                                            Toast.makeText(context, "Copied ${sym.offset} to clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sym.name.ifBlank { "${sym.className}.${sym.method}" },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1
                                        )
                                        if (sym.lib.isNotBlank()) {
                                            Text(
                                                text = sym.lib,
                                                fontSize = 9.sp,
                                                color = TextMuted,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CyanPrimary.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = sym.offset,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyanPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Frida-Inject Engine Status & On-Device Installer
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (fridaStatus?.isInstalled == true) CyanPrimary.copy(alpha = 0.5f) else SlateBorder,
                    RoundedCornerShape(12.dp)
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (fridaStatus?.isInstalled == true) CyanPrimary else AmberWarning,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "FRIDA-INJECT ENGINE",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (fridaStatus?.isInstalled == true) CyanPrimary.copy(alpha = 0.15f)
                                            else AmberWarning.copy(alpha = 0.15f)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isCheckingFrida -> "CHECKING..."
                                            fridaStatus?.isInstalled == true -> "v${fridaStatus?.version ?: "ACTIVE"}"
                                            else -> "NOT FOUND"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (fridaStatus?.isInstalled == true) CyanPrimary else AmberWarning
                                    )
                                }
                            }
                            Text(
                                text = if (fridaStatus?.isInstalled == true)
                                    "Ready at /data/local/tmp/frida-inject • Standalone on-device engine"
                                else
                                    "Required for Frida JS Interceptor. 1-tap download from GitHub releases",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = TextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                isCheckingFrida = true
                                fridaStatus = FridaInstaller.checkStatus()
                                isCheckingFrida = false
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (isInstallingFrida) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SlateSurfaceVariant)
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = CyanPrimary,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = fridaInstallProgress.ifBlank { "Installing frida-inject..." },
                                fontSize = 11.sp,
                                color = CyanPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else if (fridaStatus?.isInstalled == false) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isInstallingFrida = true
                                val res = FridaInstaller.downloadAndInstall(context.cacheDir) { progress ->
                                    fridaInstallProgress = progress
                                }
                                isInstallingFrida = false
                                if (res.isSuccess) {
                                    fridaStatus = FridaInstaller.checkStatus()
                                    Toast.makeText(context, res.getOrNull(), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Install failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = SlateBackground, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "INSTALL FRIDA-INJECT (AUTO-FETCH LATEST)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SlateBackground
                        )
                    }
                }
            }
        }

        // Section: Embedded Web Terminal & Workstation Server (Port 8088)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isServerRunning) EmeraldSuccess.copy(alpha = 0.5f) else SlateBorder,
                    RoundedCornerShape(12.dp)
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = if (isServerRunning) EmeraldSuccess else CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "WEB TERMINAL SERVER",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (isServerRunning) EmeraldSuccess.copy(alpha = 0.15f)
                                            else SlateSurfaceVariant
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isServerRunning) ":8088 ONLINE" else "OFFLINE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isServerRunning) EmeraldSuccess else TextMuted
                                    )
                                }
                            }
                            Text(
                                text = if (isServerRunning) "$connectedClients client(s) connected • streaming live logs"
                                       else "Stream trace logs & manage hooks from desktop browser",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = if (isServerRunning) EmeraldSuccess else TextSecondary
                            )
                        }
                    }

                    Switch(
                        checked = isServerRunning,
                        onCheckedChange = { startServer ->
                            val prefs = context.getSharedPreferences("flutterinspect_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("web_server_enabled", startServer).apply()
                            if (startServer) {
                                val res = WebTerminalServer.start(context.applicationContext)
                                if (res.isFailure) {
                                    Toast.makeText(context, "Failed to start server: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Web Terminal started on port 8088", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                WebTerminalServer.stop()
                                Toast.makeText(context, "Web Terminal stopped", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = EmeraldSuccess,
                            checkedTrackColor = EmeraldSuccess.copy(alpha = 0.3f)
                        )
                    )
                }

                if (isServerRunning) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = SlateBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "WORKSTATION ACCESS OPTIONS",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Method 1: ADB Port Forward (USB)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SlateSurfaceVariant)
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "USB / ADB Forward (Recommended)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CyanPrimary
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", "adb forward tcp:8088 tcp:8088"))
                                    Toast.makeText(context, "Copied 'adb forward tcp:8088 tcp:8088'", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy command", tint = TextMuted, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SlateBackground.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$ adb forward tcp:8088 tcp:8088",
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldSuccess
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Browser: http://localhost:8088",
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = TextSecondary
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", "http://localhost:8088"))
                                    Toast.makeText(context, "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = TextMuted, modifier = Modifier.size(13.dp))
                            }
                        }
                    }

                    // Method 2: Wi-Fi IP
                    if (deviceIps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SlateSurfaceVariant)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Local Wi-Fi Network Access",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PurpleHighlight
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            deviceIps.forEach { ip ->
                                val url = "http://$ip:8088"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = url,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                                            Toast.makeText(context, "Copied $url to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Live Discovery Mode (rodata heuristic scanner) - Placed at bottom before Deploy
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (config.discoveryEnabled) CyanPrimary.copy(alpha = 0.5f) else SlateBorder, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConfigChange(config.copy(discoveryEnabled = !config.discoveryEnabled)) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (config.discoveryEnabled) Icons.Default.Radar else Icons.Default.SensorsOff,
                            contentDescription = null,
                            tint = if (config.discoveryEnabled) CyanPrimary else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "LIVE DISCOVERY MODE",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                color = TextPrimary
                            )
                            Text(
                                text = if (config.discoveryEnabled) "Active: scanning .rodata strings & Capstone xrefs in memory"
                                       else "Disabled: memory scan shut (using Blutter symbols directly)",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = if (config.discoveryEnabled) CyanPrimary else TextSecondary
                            )
                        }
                    }
                    Switch(
                        checked = config.discoveryEnabled,
                        onCheckedChange = { onConfigChange(config.copy(discoveryEnabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanPrimary,
                            checkedTrackColor = CyanPrimary.copy(alpha = 0.3f)
                        )
                    )
                }

                if (config.discoveryEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = SlateBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "SCAN STRATEGY",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isKeywordMode = config.mode == "keyword_search"

                        // Keyword Search Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isKeywordMode) CyanPrimary.copy(alpha = 0.2f) else SlateSurfaceVariant)
                                .border(
                                    1.dp,
                                    if (isKeywordMode) CyanPrimary else SlateBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onConfigChange(config.copy(mode = "keyword_search")) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Keyword Search",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (isKeywordMode) CyanPrimary else TextSecondary
                                )
                                Text(
                                    text = "Fast targeted .rodata xrefs",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                                    color = TextMuted
                                )
                            }
                        }

                        // Full Enumerate Button
                        val isFullMode = config.mode == "full_enumerate"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isFullMode) PurpleHighlight.copy(alpha = 0.2f) else SlateSurfaceVariant)
                                .border(
                                    1.dp,
                                    if (isFullMode) PurpleHighlight else SlateBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onConfigChange(config.copy(mode = "full_enumerate")) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Full Enumerate",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (isFullMode) PurpleHighlight else TextSecondary
                                )
                                Text(
                                    text = "Exhaustive Dart identifiers",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Keywords (if in keyword_search mode & discoveryEnabled)
        if (config.discoveryEnabled && config.mode == "keyword_search") {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SEARCH KEYWORDS",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Keyword Chips Flow
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Presets
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(keywordPresets) { preset ->
                                val isSelected = config.keywords.contains(preset)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val updated = if (isSelected) {
                                            config.keywords.filter { it != preset }
                                        } else {
                                            config.keywords + preset
                                        }
                                        onConfigChange(config.copy(keywords = updated))
                                    },
                                    label = { Text(preset, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                        selectedLabelColor = CyanPrimary,
                                        containerColor = SlateSurfaceVariant,
                                        labelColor = TextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = SlateBorder,
                                        selectedBorderColor = CyanPrimary
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Active keywords list with remove
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        config.keywords.forEach { kw ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SlateSurfaceVariant)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = kw,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = TextPrimary
                                )
                                IconButton(
                                    onClick = {
                                        onConfigChange(config.copy(keywords = config.keywords.filter { it != kw }))
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = RoseError,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Add custom keyword input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newKeywordInput,
                            onValueChange = { newKeywordInput = it },
                            placeholder = { Text("Add custom keyword...", color = TextMuted, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = SlateBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = SlateSurfaceVariant,
                                unfocusedContainerColor = SlateSurfaceVariant
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (newKeywordInput.isNotBlank() && !config.keywords.contains(newKeywordInput.trim())) {
                                    onConfigChange(config.copy(keywords = config.keywords + newKeywordInput.trim()))
                                    newKeywordInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", tint = SlateBackground)
                        }
                    }
                }
            }
        }

        // Section: Target Library (only for Live Discovery)
        if (config.discoveryEnabled) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "TARGET LIBRARIES",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val hasAppSo = config.targetLibs.contains("libapp.so")
                        FilterChip(
                            selected = hasAppSo,
                            onClick = {
                                val updated = if (hasAppSo) config.targetLibs - "libapp.so" else config.targetLibs + "libapp.so"
                                onConfigChange(config.copy(targetLibs = updated))
                            },
                            label = { Text("libapp.so (User Code)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = CyanPrimary
                            )
                        )

                        val hasFlutterSo = config.targetLibs.contains("libflutter.so")
                        FilterChip(
                            selected = hasFlutterSo,
                            onClick = {
                                val updated = if (hasFlutterSo) config.targetLibs - "libflutter.so" else config.targetLibs + "libflutter.so"
                                onConfigChange(config.copy(targetLibs = updated))
                            },
                            label = { Text("libflutter.so (Engine)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = CyanPrimary
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Action Buttons
        val hasTargetProcess = config.process.isNotBlank()

        if (config.discoveryEnabled) {
            Button(
                onClick = onSaveConfig,
                enabled = !isSaving && hasTargetProcess,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    disabledContainerColor = SlateBorder.copy(alpha = 0.4f),
                    disabledContentColor = TextMuted
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = SlateBackground, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, tint = if (hasTargetProcess) SlateBackground else TextMuted)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("DEPLOY DISCOVERY CONFIG TO ZYGISK", fontWeight = FontWeight.Bold, color = if (hasTargetProcess) SlateBackground else TextMuted)
                }
            }
        }

        OutlinedButton(
            onClick = onRestartApp,
            enabled = !isRestarting && hasTargetProcess,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AmberWarning,
                disabledContentColor = TextMuted
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (hasTargetProcess) AmberWarning else SlateBorder),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isRestarting) {
                CircularProgressIndicator(color = AmberWarning, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (hasTargetProcess) AmberWarning else TextMuted)
                Spacer(modifier = Modifier.width(8.dp))
                Text("SAVE & FORCE RESTART APP", fontWeight = FontWeight.Bold, color = if (hasTargetProcess) AmberWarning else TextMuted)
            }
        }

        if (!config.discoveryEnabled) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "ℹ Live Discovery is OFF. Symbols can be traced directly in Trace Lab. Use 'Save & Restart' only if switching to an app not yet registered with Zygisk.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }

        var isExportingLibs by remember { mutableStateOf(false) }

        OutlinedButton(
            onClick = {
                if (config.process.isBlank()) {
                    android.widget.Toast.makeText(context, "Please select a target app first", android.widget.Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                coroutineScope.launch {
                    isExportingLibs = true
                    val res = RootShell.exportNativeLibs(context, config.process)
                    isExportingLibs = false
                    if (res.isSuccess) {
                        android.widget.Toast.makeText(context, "Exported to ${res.getOrNull()}!", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(context, "Failed: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !isExportingLibs && config.process.isNotBlank(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PurpleHighlight),
            border = androidx.compose.foundation.BorderStroke(1.dp, PurpleHighlight),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isExportingLibs) {
                CircularProgressIndicator(color = PurpleHighlight, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Download, contentDescription = null, tint = PurpleHighlight)
                Spacer(modifier = Modifier.width(8.dp))
                Text("EXPORT NATIVE LIBS (FOR BLUTTER)", fontWeight = FontWeight.Bold, color = PurpleHighlight)
            }
        }
    }

    if (showAppPicker) {
        AppPickerSheet(
            apps = installedApps,
            onAppSelected = { app ->
                onConfigChange(config.copy(process = app.packageName))
            },
            onDismiss = { showAppPicker = false }
        )
    }
}
