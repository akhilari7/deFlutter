package com.akhilari.deflutter.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akhilari.deflutter.data.models.FunctionEntry
import com.akhilari.deflutter.ui.theme.*

@Composable
fun FunctionCard(
    function: FunctionEntry,
    isSelected: Boolean = false,
    onToggleSelect: ((FunctionEntry) -> Unit)? = null,
    onInspectInFrida: (FunctionEntry) -> Unit
) {
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CyanPrimary.copy(alpha = 0.08f) else SlateSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) CyanPrimary else SlateBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable {
                if (onToggleSelect != null) {
                    onToggleSelect(function)
                } else {
                    onInspectInFrida(function)
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Selection toggle + Offset Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onToggleSelect != null) {
                        IconButton(
                            onClick = { onToggleSelect(function) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = if (isSelected) "Deselect" else "Select for batch",
                                tint = if (isSelected) CyanPrimary else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Offset Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CyanPrimary.copy(alpha = 0.15f))
                            .border(1.dp, CyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .clickable {
                                copyToClipboard(context, "Dart Offset", function.offset)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = function.offset,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = CyanPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Offset",
                            tint = CyanPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        }
                    }
                }

                // Inspect button
                IconButton(
                    onClick = { onInspectInFrida(function) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = "Inspect in Frida",
                        tint = PurpleHighlight,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val resolvedSymbol = com.akhilari.deflutter.data.symbols.SymbolManager.resolveFunction(function.offset)

            if (resolvedSymbol != null) {
                Text(
                    text = resolvedSymbol,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    color = EmeraldSuccess
                )
                if (function.name.isNotBlank() && function.name != resolvedSymbol) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Heuristic tag: ${function.name}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = TextMuted
                    )
                }
            } else {
                Text(
                    text = function.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "ASLR ADDR",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextMuted
                    )
                    Text(
                        text = function.absAddr,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = TextSecondary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "XREF ADDR",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextMuted
                    )
                    Text(
                        text = function.xrefAddr,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
}
