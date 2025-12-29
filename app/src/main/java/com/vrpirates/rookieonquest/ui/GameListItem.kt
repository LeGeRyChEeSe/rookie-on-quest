package com.vrpirates.rookieonquest.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import java.io.File

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
    val size: String? = null,
    val description: String? = null,
    val screenshotUrls: List<String>? = null
)

@Composable
fun GameListItem(
    game: GameItemState,
    onInstallClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onDownloadOnlyClick: () -> Unit,
    isGridItem: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val context = LocalContext.current
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else if (isHovered) 1.02f else 1f,
        label = "scale"
    )
    
    val buttonColor = when (game.installStatus) {
        InstallStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.secondary
        InstallStatus.INSTALLED -> Color(0xFF3498db)
        InstallStatus.UPDATE_AVAILABLE -> Color(0xFF2ecc71)
    }
    
    val buttonText = when (game.installStatus) {
        InstallStatus.UPDATE_AVAILABLE -> "UPDATE"
        InstallStatus.INSTALLED -> "INSTALLED"
        else -> "INSTALL"
    }
    
    val isEnabled = game.installStatus != InstallStatus.INSTALLED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .scale(scale)
            .shadow(
                elevation = if (isHovered) 8.dp else 2.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (isHovered) Color(0xFF1E1E1E) else Color(0xFF121212)
        ),
        border = if (isHovered) BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)) else null
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isGridItem) 56.dp else 60.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(game.iconFile)
                            .crossfade(true)
                            .size(120, 120) // Redimensionnement pour libérer de la RAM
                            .precision(Precision.EXACT)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp)
                ) {
                    Text(
                        text = game.name, 
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "v${game.version}", 
                            style = MaterialTheme.typography.bodySmall,
                            color = if (game.installStatus == InstallStatus.UPDATE_AVAILABLE) Color(0xFF2ecc71) else Color(0xFF3498db),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        
                        if (game.size != null) {
                            Text(
                                text = " • ${game.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(start = 4.dp),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                
                if (!isGridItem) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val actionIcon = if (game.installStatus == InstallStatus.INSTALLED || game.installStatus == InstallStatus.UPDATE_AVAILABLE) Icons.Default.Delete else Icons.Default.Download
                        val actionTint = if (game.installStatus == InstallStatus.INSTALLED || game.installStatus == InstallStatus.UPDATE_AVAILABLE) Color(0xFFCF6679) else Color.Gray
                        
                        IconButton(
                            onClick = { if (game.installStatus == InstallStatus.NOT_INSTALLED) onDownloadOnlyClick() else onUninstallClick() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(actionIcon, contentDescription = null, tint = actionTint, modifier = Modifier.size(18.dp))
                        }
                        
                        Spacer(modifier = Modifier.width(4.dp))

                        Button(
                            onClick = onInstallClick,
                            enabled = isEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor,
                                disabledContainerColor = if (game.installStatus == InstallStatus.INSTALLED) buttonColor.copy(alpha = 0.5f) else Color.DarkGray
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp).widthIn(min = 60.dp)
                        ) {
                            Text(buttonText, fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                        .fillMaxWidth()
                ) {
                    Divider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (!game.screenshotUrls.isNullOrEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().height(140.dp)
                        ) {
                            items(game.screenshotUrls) { url ->
                                Card(shape = RoundedCornerShape(6.dp)) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(url)
                                            .size(480, 270) // Taille réduite pour les screenshots
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier.aspectRatio(16/9f).fillMaxHeight(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    
                    if (!game.description.isNullOrEmpty()) {
                        Surface(
                            color = Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = game.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                lineHeight = 16.sp,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    
                    if (isGridItem) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { if (game.installStatus == InstallStatus.NOT_INSTALLED) onDownloadOnlyClick() else onUninstallClick() }) {
                                Text(if (game.installStatus == InstallStatus.NOT_INSTALLED) "Download" else "Uninstall", color = if (game.installStatus == InstallStatus.NOT_INSTALLED) Color.Gray else Color(0xFFCF6679), fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onInstallClick, 
                                enabled = isEnabled, 
                                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(buttonText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
