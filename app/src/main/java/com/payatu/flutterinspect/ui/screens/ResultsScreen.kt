package com.payatu.flutterinspect.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.payatu.flutterinspect.data.models.DartSnapshot
import com.payatu.flutterinspect.data.models.FunctionEntry
import com.payatu.flutterinspect.data.models.ScanResult
import com.payatu.flutterinspect.data.symbols.SymbolManager
import com.payatu.flutterinspect.ui.components.FunctionCard
import com.payatu.flutterinspect.ui.theme.*

@Composable
fun ResultsScreen(
    scanResult: ScanResult?,
    isLoading: Boolean,
    isDiscoveryEnabled: Boolean = false,
    onReload: () -> Unit,
    onInspectInFrida: (FunctionEntry) -> Unit,
    onBatchInspectInFrida: (List<FunctionEntry>) -> Unit,
    onAddKeywordAndRescan: (List<String>) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current

    val isSymbolsLoaded by SymbolManager.isLoaded.collectAsState()
    val loadedPkg by SymbolManager.loadedPackage.collectAsState()
    val allFunctions = scanResult?.functions ?: emptyList()
    val activePkg = scanResult?.process?.ifBlank { com.payatu.flutterinspect.server.TraceLogBroadcaster.activeTarget.value } ?: com.payatu.flutterinspect.server.TraceLogBroadcaster.activeTarget.value
    val isSymbolsForActivePkg = isSymbolsLoaded && (loadedPkg.isBlank() || loadedPkg == activePkg)

    var searchBlutterMode by remember(isDiscoveryEnabled, isSymbolsForActivePkg, activePkg, allFunctions.size) {
        mutableStateOf(!isDiscoveryEnabled || (allFunctions.isEmpty() && isSymbolsForActivePkg))
    }
    val effectiveBlutterMode = (searchBlutterMode || !isDiscoveryEnabled || allFunctions.isEmpty()) && isSymbolsForActivePkg
    var isBatchMode by remember { mutableStateOf(false) }

    LaunchedEffect(activePkg) {
        if (activePkg.isNotBlank() && (!isSymbolsLoaded || loadedPkg != activePkg)) {
            SymbolManager.autoLoadForPackage(activePkg)
        }
    }

    val selectedBatchFunctions = remember { mutableStateMapOf<String, FunctionEntry>() }

    val toggleSelection: (FunctionEntry) -> Unit = { func ->
        if (selectedBatchFunctions.containsKey(func.offset)) {
            selectedBatchFunctions.remove(func.offset)
        } else {
            if (selectedBatchFunctions.size >= 20) {
                Toast.makeText(context, "Maximum 20 functions can be selected for batch hook", Toast.LENGTH_SHORT).show()
            } else {
                selectedBatchFunctions[func.offset] = func
            }
        }
    }
    val filteredFunctions = remember(searchQuery, allFunctions, effectiveBlutterMode, isSymbolsLoaded) {
        if (effectiveBlutterMode) {
            val list = if (searchQuery.isBlank()) {
                SymbolManager.getAllFunctions(200)
            } else {
                SymbolManager.searchFunctions(searchQuery, limit = 200)
            }
            list.map { sf ->
                FunctionEntry(
                    name = sf.name.ifBlank { if (sf.className.isNotBlank()) "${sf.className}.${sf.method}" else sf.offset },
                    absAddr = "libapp.so+${sf.offset}",
                    offset = sf.offset,
                    xrefAddr = "Blutter Symbol"
                )
            }
        } else {
            if (searchQuery.isBlank()) allFunctions
            else allFunctions.filter { fn ->
                val resolvedSym = SymbolManager.resolveFunction(fn.offset)
                fn.matches(searchQuery) || (resolvedSym != null && resolvedSym.contains(searchQuery, ignoreCase = true))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Summary Header Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val displayTotalCount = if (effectiveBlutterMode) {
                    SymbolManager.functionsCount
                } else {
                    allFunctions.size
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = scanResult?.process?.ifBlank {
                                activePkg.ifBlank { "No Process Selected" }
                            } ?: activePkg.ifBlank { "No Process Selected" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = TextPrimary
                        )
                        Text(
                            text = "${scanResult?.lib ?: "libapp.so"} (Base: ${scanResult?.libBase?.ifBlank { "0x0" } ?: "0x0"})",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                            color = CyanPrimary
                        )
                    }

                    // Count Badge

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(EmeraldSuccess.copy(alpha = 0.15f))
                            .border(1.dp, EmeraldSuccess.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "$displayTotalCount FUNCS",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = EmeraldSuccess
                        )
                    }
                }

                val sourceText = if (effectiveBlutterMode) {
                    "Source: Blutter Symbols ($displayTotalCount loaded)"
                } else if (scanResult != null && scanResult.sourcePath.isNotBlank()) {
                    "Source: ${scanResult.sourcePath}"
                } else null

                if (sourceText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = sourceText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                        color = TextMuted
                    )
                }
            }
        }

        // Snapshot Engine Card (Point 1: VM Snapshot Cluster Walker)
        val managedSnap by SymbolManager.activeSnapshot.collectAsState()
        val snap = scanResult?.snapshot ?: managedSnap
        var showSymbolsDialog by remember { mutableStateOf(false) }

        if (snap != null && snap.valid) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Dart VM Snapshot (${snap.kind})",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                ),
                                color = TextPrimary
                            )
                        }

                        // Magic badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyanPrimary.copy(alpha = 0.12f))
                                .border(1.dp, CyanPrimary.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = snap.magic,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = CyanPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Hash row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SDK Hash: ${snap.hash}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            color = AmberWarning,
                            maxLines = 1
                        )
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Snapshot Hash", snap.hash))
                                Toast.makeText(context, "Copied hash!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy Hash",
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // Metrics Row: Clusters, Objects, Base Objects, Code Offset
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SnapshotMetricChip("Clusters", "${snap.numClusters}", Modifier.weight(1f))
                        SnapshotMetricChip("Objects", "${snap.numObjects}", Modifier.weight(1f))
                        SnapshotMetricChip("Base Objs", "${snap.numBaseObjects}", Modifier.weight(1f))
                        if (snap.instructionsOffset.isNotBlank()) {
                            SnapshotMetricChip("Code Off", snap.instructionsOffset, Modifier.weight(1f))
                        }
                    }

                    // VM Features flags
                    if (snap.features.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Flags: ${snap.features}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = TextSecondary
                        )
                    }

                    // Discovered snapshot library symbols
                    if (snap.symbols.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SlateBorder.copy(alpha = 0.4f))
                                .clickable { showSymbolsDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📦 ${snap.symbols.size} Snapshot Packages Discovered",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                ),
                                color = CyanPrimary
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Symbols Dialog
            if (showSymbolsDialog) {
                var packageSearchQuery by remember { mutableStateOf("") }
                val filteredSymbols = remember(packageSearchQuery, snap.symbols) {
                    if (packageSearchQuery.isBlank()) snap.symbols
                    else snap.symbols.filter { it.contains(packageSearchQuery, ignoreCase = true) }
                }

                AlertDialog(
                    onDismissRequest = { showSymbolsDialog = false },
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Snapshot Packages", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CyanPrimary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${filteredSymbols.size} / ${snap.symbols.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = CyanPrimary
                                )
                            }
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Search bar for packages
                            OutlinedTextField(
                                value = packageSearchQuery,
                                onValueChange = { packageSearchQuery = it },
                                placeholder = { Text("Search packages...", color = TextMuted, fontSize = 12.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                },
                                trailingIcon = {
                                    if (packageSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { packageSearchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = SlateBorder,
                                    focusedContainerColor = SlateSurfaceVariant,
                                    unfocusedContainerColor = SlateSurfaceVariant,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )

                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(filteredSymbols) { sym ->
                                    val cleanName = sym.substringAfterLast('/').removeSuffix(".dart")
                                    val tokens = cleanName.split('_').filter { it.length >= 3 }
                                    val allTokens = (listOf(cleanName, cleanName.replace("_", "")) + tokens).distinct()
                                    val matchCount = allFunctions.count { fn -> allTokens.any { t -> fn.matches(t) } }
                                    val primaryFilter = tokens.firstOrNull { it.length >= 4 } ?: cleanName.replace("_", "")

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SlateSurface)
                                            .border(
                                                1.dp,
                                                if (matchCount > 0) EmeraldSuccess.copy(alpha = 0.3f) else SlateBorder,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        searchQuery = primaryFilter
                                                        showSymbolsDialog = false
                                                        Toast.makeText(context, "Filtered by '$primaryFilter'", Toast.LENGTH_SHORT).show()
                                                    }
                                            ) {
                                                Text(
                                                    text = cleanName,
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    ),
                                                    color = if (matchCount > 0) EmeraldSuccess else CyanPrimary
                                                )
                                                Text(
                                                    text = sym,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                                    color = TextMuted,
                                                    maxLines = 1
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            if (matchCount > 0) {
                                                // Already scanned and matches exist -> Filter Chip
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(EmeraldSuccess.copy(alpha = 0.15f))
                                                        .clickable {
                                                            searchQuery = primaryFilter
                                                            showSymbolsDialog = false
                                                            Toast.makeText(context, "Showing $matchCount functions for '$cleanName'", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = EmeraldSuccess,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "$matchCount funcs",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                            color = EmeraldSuccess
                                                        )
                                                    }
                                                }
                                            } else {
                                                // Not yet scanned -> Scan Action Button
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(CyanPrimary.copy(alpha = 0.2f))
                                                        .border(1.dp, CyanPrimary, RoundedCornerShape(6.dp))
                                                        .clickable {
                                                            showSymbolsDialog = false
                                                            searchQuery = primaryFilter
                                                            onAddKeywordAndRescan(allTokens)
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            Icons.Default.FlashOn,
                                                            contentDescription = null,
                                                            tint = CyanPrimary,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "SCAN",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
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
                    },
                    confirmButton = {
                        TextButton(onClick = { showSymbolsDialog = false }) {
                            Text("CLOSE", color = CyanPrimary)
                        }
                    },
                    containerColor = SlateSurface,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        if (isSymbolsLoaded && isDiscoveryEnabled && allFunctions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !searchBlutterMode,
                    onClick = { searchBlutterMode = false },
                    label = { Text("Discovered (${allFunctions.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                        selectedLabelColor = CyanPrimary
                    )
                )

                FilterChip(
                    selected = searchBlutterMode,
                    onClick = { searchBlutterMode = true },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = if (searchBlutterMode) EmeraldSuccess else TextMuted, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Blutter Symbols (${SymbolManager.functionsCount})", fontSize = 11.sp)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EmeraldSuccess.copy(alpha = 0.2f),
                        selectedLabelColor = EmeraldSuccess
                    )
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else if (isSymbolsLoaded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Blutter Symbols (${SymbolManager.functionsCount} loaded)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                    color = EmeraldSuccess
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Search Bar and Quick Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        if (searchBlutterMode) "Search symbols (e.g. encrypt, SecurityService)..."
                        else "Filter functions or offsets...",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanPrimary) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = SlateBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SlateSurface,
                    unfocusedContainerColor = SlateSurface
                ),
                singleLine = true
            )

            // Batch Mode Toggle / Inspect Button
            Button(
                onClick = {
                    if (!isBatchMode) {
                        isBatchMode = true
                    } else {
                        if (selectedBatchFunctions.isNotEmpty()) {
                            onBatchInspectInFrida(selectedBatchFunctions.values.toList())
                        } else if (filteredFunctions.isNotEmpty()) {
                            Toast.makeText(context, "Select up to 20 functions below or tap 'Top 20'", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "No functions available to hook", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBatchMode && selectedBatchFunctions.isNotEmpty()) PurpleHighlight else if (isBatchMode) SlateSurfaceVariant else SlateSurfaceVariant
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = if (isBatchMode && selectedBatchFunctions.isNotEmpty()) Icons.Default.ElectricBolt else Icons.Default.Checklist,
                    contentDescription = "Batch Mode",
                    tint = if (isBatchMode && selectedBatchFunctions.isNotEmpty()) SlateBackground else if (isBatchMode) PurpleHighlight else TextSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isBatchMode && selectedBatchFunctions.isNotEmpty()) "BATCH (${selectedBatchFunctions.size})" else if (isBatchMode) "BATCH (0)" else "BATCH",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isBatchMode && selectedBatchFunctions.isNotEmpty()) SlateBackground else if (isBatchMode) PurpleHighlight else TextSecondary
                )
            }
        }

        // Selection Control Bar (Active ONLY in Batch Mode)
        if (isBatchMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateSurface.copy(alpha = 0.6f))
                    .border(
                        1.dp,
                        if (selectedBatchFunctions.isNotEmpty()) PurpleHighlight.copy(alpha = 0.5f) else SlateBorder,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (selectedBatchFunctions.isNotEmpty()) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selectedBatchFunctions.isNotEmpty()) PurpleHighlight else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${selectedBatchFunctions.size} / 20 Selected for Batch Hook",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        ),
                        color = if (selectedBatchFunctions.isNotEmpty()) PurpleHighlight else TextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Quick select top 20
                    TextButton(
                        onClick = {
                            val toAdd = filteredFunctions.take(20)
                            selectedBatchFunctions.clear()
                            toAdd.forEach { f -> selectedBatchFunctions[f.offset] = f }
                            Toast.makeText(context, "Selected ${toAdd.size} functions", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Top 20", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    if (selectedBatchFunctions.isNotEmpty()) {
                        TextButton(
                            onClick = { selectedBatchFunctions.clear() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Clear", fontSize = 11.sp, color = TextMuted)
                        }
                    }

                    TextButton(
                        onClick = {
                            isBatchMode = false
                            selectedBatchFunctions.clear()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Exit", fontSize = 11.sp, color = RoseError)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Functions List or Empty State
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = CyanPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Reading resolution results...", color = TextSecondary, fontSize = 13.sp)
                }
            }
        } else if (filteredFunctions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (allFunctions.isEmpty()) "No Discovered Functions Yet" else "No Matching Functions Found",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (searchBlutterMode)
                            "Type a class or method name in the search box above to find symbols."
                        else if (allFunctions.isEmpty())
                            "Ensure the target app has run once with live discovery enabled, or use Blutter symbols."
                        else "Try a different search query.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onReload,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = SlateBackground)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("RELOAD RESULTS", fontWeight = FontWeight.Bold, color = SlateBackground)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredFunctions, key = { it.offset + it.name }) { func ->
                    FunctionCard(
                        function = func,
                        isSelected = selectedBatchFunctions.containsKey(func.offset),
                        onToggleSelect = if (isBatchMode) toggleSelection else null,
                        onInspectInFrida = { onInspectInFrida(func) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotMetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SlateBackground.copy(alpha = 0.7f))
            .border(1.dp, SlateBorder, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = TextMuted
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = TextPrimary
            )
        }
    }
}
