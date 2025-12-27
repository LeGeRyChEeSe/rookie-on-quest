package com.vrpirates.rookieonquest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File
import java.util.Locale

enum class InstallStatus {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE
}

@Immutable
data class GameItemState(
    val name: String,
    val version: String,
    val installedVersion: String? = null,
    val packageName: String,
    val releaseName: String,
    val iconFile: File?,
    val installStatus: InstallStatus = InstallStatus.NOT_INSTALLED,
    val size: String? = null
)

@Composable
fun GameListItem(
    game: GameItemState,
    onInstallClick: () -> Unit,
    onUninstallClick: () -> Unit
) {
    val buttonColor = when (game.installStatus) {
        InstallStatus.NOT_INSTALLED -> Color.Transparent
        InstallStatus.INSTALLED -> Color(0xFF3498db) // Blue
        InstallStatus.UPDATE_AVAILABLE -> Color(0xFF2ecc71) // Green
    }
    
    val buttonText = when (game.installStatus) {
        InstallStatus.UPDATE_AVAILABLE -> "UPDATE"
        InstallStatus.INSTALLED -> "INSTALLED"
        else -> "INSTALL"
    }
    
    val isEnabled = game.installStatus != InstallStatus.INSTALLED

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp)),
        color = Color(0xFF121212),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black)
                    .border(0.5.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = game.iconFile,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit
                )
            }
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp)
            ) {
                Text(
                    text = game.name, 
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (game.installStatus == InstallStatus.UPDATE_AVAILABLE && game.installedVersion != null) {
                        Text(
                            text = "v${game.installedVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textDecoration = TextDecoration.LineThrough,
                            fontWeight = FontWeight.Normal
                        )
                        Text(
                            text = " → ",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "v${game.version}", 
                        style = MaterialTheme.typography.bodySmall,
                        color = if (game.installStatus == InstallStatus.UPDATE_AVAILABLE) Color(0xFF2ecc71) else Color(0xFF3498db),
                        fontWeight = FontWeight.Medium
                    )
                    
                    if (game.size != null) {
                        Text(
                            text = " • ${game.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Text(
                    text = game.packageName, 
                    style = MaterialTheme.typography.bodySmall, 
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (game.installStatus == InstallStatus.INSTALLED || game.installStatus == InstallStatus.UPDATE_AVAILABLE) {
                    IconButton(
                        onClick = onUninstallClick,
                        modifier = Modifier.size(36.dp).padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Uninstall",
                            tint = Color(0xFFCF6679),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Button(
                    onClick = onInstallClick,
                    enabled = isEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = Color.White,
                        disabledContainerColor = buttonColor,
                        disabledContentColor = Color.White.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(4.dp),
                    border = if (game.installStatus == InstallStatus.NOT_INSTALLED) BorderStroke(1.dp, Color.Gray) else null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        buttonText, 
                        fontSize = 10.sp, 
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
