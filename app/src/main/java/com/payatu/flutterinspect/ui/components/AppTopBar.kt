package com.payatu.flutterinspect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.payatu.flutterinspect.R
import com.payatu.flutterinspect.ui.theme.*

@Composable
fun AppTopBar(
    isRootAvailable: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        color = SlateSurface,
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyanPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_deflutter_logo),
                        contentDescription = "Deflutter Logo",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "DEFLUTTER",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = "Dart AOT Runtime Dissector",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = TextSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Root status pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isRootAvailable) EmeraldSuccess.copy(alpha = 0.15f)
                            else RoseError.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isRootAvailable) "ROOT: ACTIVE" else "ROOT: OFF",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                        color = if (isRootAvailable) EmeraldSuccess else RoseError
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
