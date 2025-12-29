package com.vrpirates.rookieonquest

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.vrpirates.rookieonquest.ui.*
import com.vrpirates.rookieonquest.ui.theme.RookieOnQuestTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RookieOnQuestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val games by viewModel.games.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val installState by viewModel.installState.collectAsState()
    val error by viewModel.error.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val missingPermissions by viewModel.missingPermissions.collectAsState()
    val alphabetInfo by viewModel.alphabetInfo.collectAsState()
    val keepApks by viewModel.keepApks.collectAsState()
    
    val isUpdateCheckInProgress by viewModel.isUpdateCheckInProgress.collectAsState()
    val isUpdateDialogShowing by viewModel.isUpdateDialogShowing.collectAsState()
    val isUpdateDownloading by viewModel.isUpdateDownloading.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    
    val listState = rememberLazyListState()
    val staggeredGridState = rememberLazyStaggeredGridState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    var showUpdateDialogState by remember { mutableStateOf<com.vrpirates.rookieonquest.network.GitHubRelease?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Update visible indices for priority fetching
    LaunchedEffect(listState, staggeredGridState) {
        snapshotFlow { 
            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) listState.layoutInfo.visibleItemsInfo.map { it.index }
            else staggeredGridState.layoutInfo.visibleItemsInfo.map { it.index }
        }
        .collect { indices ->
            viewModel.setVisibleIndices(indices)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is MainEvent.Uninstall -> {
                    try {
                        val intent = Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.fromParts("package", event.packageName, null)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to uninstall: ${e.message}")
                    }
                }
                is MainEvent.InstallApk -> {
                    try {
                        val authority = "${context.packageName}.fileprovider"
                        val uri = FileProvider.getUriForFile(context, authority, event.apkFile)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to launch installer: ${e.message}")
                    }
                }
                is MainEvent.RequestInstallPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                }
                is MainEvent.RequestStoragePermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        }
                    }
                }
                is MainEvent.ShowUpdatePopup -> {
                    showUpdateDialogState = event.release
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.checkPermissions()
                    viewModel.setAppVisibility(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.setAppVisibility(false)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val release = showUpdateDialogState
    if (release != null) {
        UpdateDialog(
            release = release,
            onDismiss = { 
                showUpdateDialogState = null 
                viewModel.onUpdateDialogDismissed()
            },
            onConfirm = {
                viewModel.downloadAndInstallUpdate(release)
                showUpdateDialogState = null
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            keepApks = keepApks,
            onToggleKeepApks = { viewModel.toggleKeepApks() },
            onDismiss = { showSettingsDialog = false }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth > 800.dp
        
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CustomTopBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onSettingsClick = { showSettingsDialog = true },
                    onRefreshClick = { viewModel.refreshData() },
                    isRefreshing = isRefreshing,
                    isInstalling = installState.isInstalling || isUpdateDownloading,
                    permissionsMissing = missingPermissions?.isNotEmpty() == true
                )
            },
            containerColor = Color.Black
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when {
                    isUpdateCheckInProgress -> LoadingScreen("Checking for updates...")
                    isUpdateDialogShowing -> LoadingScreen("Update available!")
                    isUpdateDownloading -> InstallationOverlay(
                        installState = InstallState(isInstalling = true, message = updateProgress, progress = -1f),
                        onCancel = {}
                    )
                    missingPermissions == null -> LoadingScreen("Checking permissions...")
                    missingPermissions!!.isNotEmpty() -> {
                        PermissionOverlay(
                            missingPermissions = missingPermissions!!,
                            onGrantClick = { viewModel.startPermissionFlow() }
                        )
                    }
                    else -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (games.isNotEmpty() && searchQuery.isEmpty()) {
                                AlphabetIndexer(
                                    alphabetInfo = alphabetInfo,
                                    isInstalling = installState.isInstalling,
                                    onLetterClick = { index ->
                                        coroutineScope.launch { 
                                            if (isWide) staggeredGridState.scrollToItem(index)
                                            else listState.scrollToItem(index) 
                                        }
                                    }
                                )
                                Box(
                                    modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.1f))
                                )
                            }

                            if (isRefreshing && games.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    LoadingScreen("Loading Catalog...")
                                }
                            } else if (error != null && games.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    ErrorScreen(error!!, onRetry = { viewModel.refreshData() })
                                }
                            } else if (isWide) {
                                LazyVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Fixed(3),
                                    state = staggeredGridState,
                                    contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalItemSpacing = 8.dp,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    items(games, key = { it.packageName + it.releaseName }) { game ->
                                        GameListItem(
                                            game = game,
                                            onInstallClick = { if (!installState.isInstalling) viewModel.installGame(game.packageName) },
                                            onUninstallClick = { viewModel.uninstallGame(game.packageName) },
                                            onDownloadOnlyClick = { if (!installState.isInstalling) viewModel.installGame(game.packageName, downloadOnly = true) },
                                            isGridItem = true
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    items(games, key = { it.packageName + it.releaseName }) { game ->
                                        GameListItem(
                                            game = game,
                                            onInstallClick = { if (!installState.isInstalling) viewModel.installGame(game.packageName) },
                                            onUninstallClick = { viewModel.uninstallGame(game.packageName) },
                                            onDownloadOnlyClick = { if (!installState.isInstalling) viewModel.installGame(game.packageName, downloadOnly = true) }
                                        )
                                    }
                                }
                            }
                        }
                        
                        if (installState.isInstalling) {
                            InstallationOverlay(
                                installState = installState,
                                onCancel = { viewModel.cancelInstall() }
                            )
                        }

                        if (isRefreshing && games.isNotEmpty()) {
                            SyncingOverlay()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionOverlay(
    missingPermissions: List<RequiredPermission>,
    onGrantClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black, Color(0xFF1A1A1A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(100.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Action Required",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Rookie On Quest needs some permissions to sideload games directly to your headset.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                missingPermissions.forEach { permission ->
                    val (title, icon) = when (permission) {
                        RequiredPermission.INSTALL_UNKNOWN_APPS -> "Install Unknown Apps" to Icons.Default.SystemUpdate
                        RequiredPermission.MANAGE_EXTERNAL_STORAGE -> "Manage External Storage" to Icons.Default.Storage
                    }
                    
                    Surface(
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, contentDescription = null, tint = Color.Gray)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onGrantClick,
                modifier = Modifier.height(64.dp).fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("GRANT PERMISSIONS", fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun CustomTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onRefreshClick: () -> Unit,
    isRefreshing: Boolean,
    isInstalling: Boolean,
    permissionsMissing: Boolean
) {
    Surface(
        color = Color(0xFF121212),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ROOKIE ON QUEST",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    ),
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                IconButton(onClick = onRefreshClick, enabled = !isInstalling && !permissionsMissing) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = if (isRefreshing) MaterialTheme.colorScheme.secondary else Color.White
                    )
                }
                
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
            
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                placeholder = { Text("Search VR games...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.secondary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }
    }
}

@Composable
fun SyncingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Syncing catalog...", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.secondary,
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFCF6679), modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Try Again", color = Color.White)
        }
    }
}

@Composable
fun InstallationOverlay(installState: InstallState, onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.9f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val progress = if (installState.progress >= 0f) installState.progress else null
            
            Text(
                text = installState.gameName ?: "Processing",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            if (progress != null) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = progress,
                        modifier = Modifier.size(200.dp),
                        strokeWidth = 8.dp,
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Black
                        )
                        if (installState.currentSize != null) {
                            Text(
                                text = "${installState.currentSize} / ${installState.totalSize}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(100.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    strokeWidth = 6.dp
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                text = installState.message ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(64.dp))
            
            if (installState.message?.contains("update", ignoreCase = true) == false) {
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(56.dp).fillMaxWidth(0.5f)
                ) {
                    Text("CANCEL", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(
    release: com.vrpirates.rookieonquest.network.GitHubRelease,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text("New Update Available")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Version ${release.tagName} is now available.",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(parseMarkdown(release.body), style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("UPDATE NOW")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("LATER", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun SettingsDialog(
    keepApks: Boolean,
    onToggleKeepApks: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Settings") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Keep APKs after install") },
                    supportingContent = { Text("Saved to Download/RookieOnQuest") },
                    trailingContent = { Switch(checked = keepApks, onCheckedChange = { onToggleKeepApks() }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        },
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun AlphabetIndexer(
    alphabetInfo: Pair<List<Char>, Map<Char, Int>>,
    isInstalling: Boolean,
    onLetterClick: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .width(40.dp)
            .background(Color.Black.copy(alpha = 0.5f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(alphabetInfo.first) { char ->
            key(char) {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val scale by animateFloatAsState(if (isHovered) 1.8f else 1f, label = "magnify")
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = !isInstalling
                        ) {
                            alphabetInfo.second[char]?.let { index ->
                                onLetterClick(index)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char.toString(),
                        fontSize = 12.sp,
                        fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Normal,
                        color = if (isHovered) MaterialTheme.colorScheme.secondary else if (isInstalling) Color.DarkGray else Color.Gray,
                        modifier = Modifier.scale(scale)
                    )
                }
            }
        }
    }
}

fun parseMarkdown(text: String) = buildAnnotatedString {
    val cleanText = text.replace(Regex("""^[\uFEFF\u200B\u200C\u200D\u200E\u200F]+"""), "").trim()
    val lines = cleanText.split(Regex("\\r?\\n"))
    
    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            append("\n")
            return@forEach
        }
        
        val headerMatch = Regex("""^#+\s*(.*)$""").find(trimmed)
        if (headerMatch != null) {
            val title = headerMatch.groupValues[1].replace("*", "").trim()
            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)) {
                append(title)
            }
            append("\n\n") 
            return@forEach
        }
        
        var content = trimmed
        if (trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("·") || trimmed.startsWith("•")) {
            append("  • ")
            content = trimmed.replace(Regex("^[-*·•]\\s*"), "")
        }

        val parts = content.split("**")
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                    append(part)
                }
            } else {
                append(part)
            }
        }
        append("\n")
    }
}
