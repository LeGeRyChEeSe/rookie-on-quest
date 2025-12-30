package com.vrpirates.rookieonquest

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val installQueue by viewModel.installQueue.collectAsState()
    val showInstallOverlay by viewModel.showInstallOverlay.collectAsState()
    val viewedReleaseName by viewModel.viewedReleaseName.collectAsState()
    val error by viewModel.error.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val filterCounts by viewModel.filterCounts.collectAsState()
    val missingPermissions by viewModel.missingPermissions.collectAsState()
    val alphabetInfo by viewModel.alphabetInfo.collectAsState()
    val keepApks by viewModel.keepApks.collectAsState()
    
    val isUpdateCheckInProgress by viewModel.isUpdateCheckInProgress.collectAsState()
    val isUpdateDownloading by viewModel.isUpdateDownloading.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    
    val listState = rememberLazyListState()
    val staggeredGridState = rememberLazyStaggeredGridState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    var showUpdateDialogState by remember { mutableStateOf<com.vrpirates.rookieonquest.network.GitHubRelease?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var gameToDelete by remember { mutableStateOf<GameItemState?>(null) }

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
                is MainEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth > 800.dp
        
        when {
            isUpdateCheckInProgress -> LoadingScreen("Checking for updates...")
            isUpdateDownloading -> {
                BackHandler(enabled = true) { /* Block back button during update */ }
                InstallationOverlay(
                    activeTask = InstallTaskState(releaseName = "update", gameName = "Rookie Update", packageName = "", status = InstallTaskStatus.DOWNLOADING, message = updateProgress, progress = -1f),
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onBackground = {}
                )
            }
            showUpdateDialogState != null -> {
                BackHandler(enabled = true) { /* Block back button during update dialog */ }
                UpdateOverlay(
                    release = showUpdateDialogState!!,
                    onDismiss = { 
                        showUpdateDialogState = null 
                        viewModel.onUpdateDialogDismissed()
                    },
                    onConfirm = {
                        viewModel.downloadAndInstallUpdate(showUpdateDialogState!!)
                        showUpdateDialogState = null
                    }
                )
            }
            missingPermissions == null -> LoadingScreen("Checking permissions...")
            missingPermissions!!.isNotEmpty() -> {
                PermissionOverlay(
                    missingPermissions = missingPermissions!!,
                    onGrantClick = { viewModel.startPermissionFlow() }
                )
            }
            else -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        CustomTopBar(
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            selectedFilter = selectedFilter,
                            onFilterChange = { viewModel.setFilter(it) },
                            filterCounts = filterCounts,
                            onSettingsClick = { showSettingsDialog = true },
                            onRefreshClick = { viewModel.refreshData() },
                            isRefreshing = isRefreshing,
                            isInstalling = installQueue.any { it.status != InstallTaskStatus.COMPLETED && it.status != InstallTaskStatus.FAILED && it.status != InstallTaskStatus.PAUSED } || isUpdateDownloading,
                            permissionsMissing = false 
                        )
                    },
                    containerColor = Color.Black,
                    bottomBar = {
                        AnimatedVisibility(
                            visible = !showInstallOverlay && installQueue.isNotEmpty(),
                            enter = slideInVertically(initialOffsetY = { it }),
                            exit = slideOutVertically(targetOffsetY = { it })
                        ) {
                            BottomQueueBar(
                                queue = installQueue,
                                onClick = { viewModel.showOverlay() }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (games.isNotEmpty() && searchQuery.isEmpty() && selectedFilter == FilterStatus.ALL) {
                                AlphabetIndexer(
                                    alphabetInfo = alphabetInfo,
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
                                            onInstallClick = { viewModel.installGame(game.releaseName) },
                                            onUninstallClick = { viewModel.uninstallGame(game.packageName) },
                                            onDownloadOnlyClick = { viewModel.installGame(game.releaseName, downloadOnly = true) },
                                            onDeleteDownloadClick = { gameToDelete = game },
                                            onResumeClick = { viewModel.resumeInstall(game.releaseName) },
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
                                            onInstallClick = { viewModel.installGame(game.releaseName) },
                                            onUninstallClick = { viewModel.uninstallGame(game.packageName) },
                                            onDownloadOnlyClick = { viewModel.installGame(game.releaseName, downloadOnly = true) },
                                            onDeleteDownloadClick = { gameToDelete = game },
                                            onResumeClick = { viewModel.resumeInstall(game.releaseName) }
                                        )
                                    }
                                }
                            }
                        }

                        if (isRefreshing && games.isNotEmpty()) {
                            SyncingOverlay()
                        }
                    }
                }
            }
        }

        if (showInstallOverlay) {
            val taskToShow = installQueue.find { it.releaseName == viewedReleaseName }
                ?: installQueue.find { it.status.isProcessing() }
                ?: installQueue.firstOrNull()
            
            if (taskToShow != null) {
                QueueManagerOverlay(
                    queue = installQueue,
                    viewedReleaseName = taskToShow.releaseName,
                    onTaskClick = { viewModel.setFocusedTask(it) },
                    onCancel = { viewModel.cancelInstall(it) },
                    onPause = { viewModel.pauseInstall(it) },
                    onResume = { viewModel.resumeInstall(it) },
                    onPromote = { viewModel.promoteTask(it) },
                    onClose = { viewModel.hideInstallOverlay() }
                )
            }
        }

        if (showSettingsDialog) {
            SettingsDialog(
                keepApks = keepApks,
                onToggleKeepApks = { viewModel.toggleKeepApks() },
                onDismiss = { showSettingsDialog = false }
            )
        }

        if (gameToDelete != null) {
            AlertDialog(
                onDismissRequest = { gameToDelete = null },
                title = { Text("Delete Download?") },
                text = { Text("Are you sure you want to delete the downloaded files for ${gameToDelete?.name}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            gameToDelete?.let { viewModel.deleteDownloadedGame(it.releaseName) }
                            gameToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCF6679))
                    ) {
                        Text("DELETE", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { gameToDelete = null }) {
                        Text("CANCEL")
                    }
                },
                containerColor = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
fun SetupLayout(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.secondary,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    isScrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = false) { }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(iconColor.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .then(if (isScrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(80.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.9f)
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            content()
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onPrimaryClick,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = iconColor)
            ) {
                Text(primaryButtonText, fontWeight = FontWeight.Black, fontSize = 18.sp, color = if (iconColor == Color.White) Color.Black else Color.White)
            }
            
            if (secondaryButtonText != null && onSecondaryClick != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = onSecondaryClick,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(secondaryButtonText, color = Color.Gray, fontWeight = FontWeight.Bold)
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
    SetupLayout(
        title = "Action Required",
        subtitle = "Rookie On Quest needs some permissions to sideload games directly to your headset library.",
        icon = Icons.Default.Security,
        iconColor = MaterialTheme.colorScheme.secondary,
        primaryButtonText = "GRANT PERMISSIONS",
        onPrimaryClick = onGrantClick
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            missingPermissions.forEach { permission ->
                val (title, description, icon) = when (permission) {
                    RequiredPermission.INSTALL_UNKNOWN_APPS -> Triple(
                        "Install Unknown Apps",
                        "Allows Rookie to install the games you download.",
                        Icons.Default.SystemUpdate
                    )
                    RequiredPermission.MANAGE_EXTERNAL_STORAGE -> Triple(
                        "File Access",
                        "Required to copy OBB and game files to storage.",
                        Icons.Default.Storage
                    )
                }
                
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        Column {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(description, color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateOverlay(
    release: com.vrpirates.rookieonquest.network.GitHubRelease,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SetupLayout(
        title = "Update Available",
        subtitle = "A new version of Rookie is available. Keeping the app up to date ensures compatibility with the latest games.",
        icon = Icons.Default.NewReleases,
        iconColor = Color(0xFF3498db),
        primaryButtonText = "UPDATE NOW",
        onPrimaryClick = onConfirm,
        isScrollable = false
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF3498db), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Version ${release.tagName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = parseMarkdown(release.body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilter: FilterStatus,
    onFilterChange: (FilterStatus) -> Unit,
    filterCounts: Map<FilterStatus, Int>,
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
                    .padding(horizontal = 16.dp),
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

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == FilterStatus.ALL,
                        onClick = { onFilterChange(FilterStatus.ALL) },
                        label = { Text("All (${filterCounts[FilterStatus.ALL] ?: 0})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            selectedLabelColor = Color.Black,
                            labelColor = Color.Gray,
                            containerColor = Color.White.copy(alpha = 0.05f)
                        ),
                        border = null
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == FilterStatus.INSTALLED,
                        onClick = { onFilterChange(FilterStatus.INSTALLED) },
                        label = { Text("Installed (${filterCounts[FilterStatus.INSTALLED] ?: 0})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF3498db),
                            selectedLabelColor = Color.White,
                            labelColor = Color.Gray,
                            containerColor = Color.White.copy(alpha = 0.05f)
                        ),
                        border = null
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == FilterStatus.DOWNLOADED,
                        onClick = { onFilterChange(FilterStatus.DOWNLOADED) },
                        label = { Text("Downloaded (${filterCounts[FilterStatus.DOWNLOADED] ?: 0})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2ecc71),
                            selectedLabelColor = Color.White,
                            labelColor = Color.Gray,
                            containerColor = Color.White.copy(alpha = 0.05f)
                        ),
                        border = null
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == FilterStatus.UPDATE_AVAILABLE,
                        onClick = { onFilterChange(FilterStatus.UPDATE_AVAILABLE) },
                        label = { Text("Updates (${filterCounts[FilterStatus.UPDATE_AVAILABLE] ?: 0})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFf1c40f),
                            selectedLabelColor = Color.Black,
                            labelColor = Color.Gray,
                            containerColor = Color.White.copy(alpha = 0.05f)
                        ),
                        border = null
                    )
                }
            }
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
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
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
fun InstallationOverlay(
    activeTask: InstallTaskState, 
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onBackground: () -> Unit
) {
    val isAppUpdate = activeTask.releaseName == "update"
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val progress = if (activeTask.progress >= 0f) activeTask.progress else null
            
            Text(
                text = activeTask.gameName,
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
                        color = if (activeTask.status == InstallTaskStatus.PAUSED) Color.Gray else MaterialTheme.colorScheme.secondary,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Black
                        )
                        if (activeTask.currentSize != null) {
                            Text(
                                text = "${activeTask.currentSize} / ${activeTask.totalSize}",
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
                text = activeTask.message ?: activeTask.status.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (activeTask.error != null) {
                Text(
                    text = activeTask.error,
                    color = Color(0xFFCF6679),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(64.dp))
            
            if (!isAppUpdate) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    if (activeTask.status == InstallTaskStatus.PAUSED || activeTask.status == InstallTaskStatus.FAILED) {
                        Button(
                            onClick = onResume,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ecc71)),
                            modifier = Modifier.height(56.dp).weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RESUME", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else if (activeTask.status.isProcessing()) {
                        OutlinedButton(
                            onClick = onPause,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(56.dp).weight(1f)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("PAUSE", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = { onCancel() },
                        border = BorderStroke(1.dp, Color(0xFFCF6679).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(56.dp).weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFCF6679))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("CANCEL", color = Color(0xFFCF6679), fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onBackground,
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Text("Run in background", color = Color.Gray, fontWeight = FontWeight.Medium)
                }
            } else {
                Text(
                    text = "Application update is mandatory. Please wait.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BottomQueueBar(queue: List<InstallTaskState>, onClick: () -> Unit) {
    val activeTask = queue.find { it.status.isProcessing() }
        ?: queue.firstOrNull() ?: return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = Color(0xFF1A1A1A),
        tonalElevation = 8.dp
    ) {
        Column {
            LinearProgressIndicator(
                progress = activeTask.progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = if (activeTask.status == InstallTaskStatus.PAUSED) Color.Gray else MaterialTheme.colorScheme.secondary,
                trackColor = Color.Transparent
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (activeTask.status == InstallTaskStatus.PAUSED) Icons.Default.Pause else Icons.Default.Download,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = activeTask.gameName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(activeTask.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                if (queue.size > 1) {
                    Text(
                        text = " (+${queue.size - 1})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
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
                            indication = null
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
                        color = if (isHovered) MaterialTheme.colorScheme.secondary else Color.Gray,
                        modifier = Modifier.scale(scale)
                    )
                }
            }
        }
    }
}

@Composable
fun QueueManagerOverlay(
    queue: List<InstallTaskState>,
    viewedReleaseName: String?,
    onTaskClick: (String) -> Unit,
    onCancel: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPromote: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.95f)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "INSTALLATION QUEUE (${queue.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(queue, key = { it.releaseName }) { task ->
                    val isViewed = task.releaseName == viewedReleaseName
                    val isProcessing = task.status.isProcessing()
                    val isPaused = task.status == InstallTaskStatus.PAUSED
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTaskClick(task.releaseName) },
                        color = if (isViewed) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(16.dp),
                        border = if (isViewed) BorderStroke(1.dp, MaterialTheme.colorScheme.secondary) else null
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.gameName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = task.message ?: task.status.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (task.status == InstallTaskStatus.FAILED) Color(0xFFCF6679) else Color.Gray
                                    )
                                }
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isProcessing && (task.status == InstallTaskStatus.QUEUED || isPaused || task.status == InstallTaskStatus.FAILED)) {
                                        IconButton(onClick = { onPromote(task.releaseName) }) {
                                            Icon(Icons.Default.VerticalAlignTop, contentDescription = "Prioritize", tint = MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                    
                                    if (isProcessing) {
                                        IconButton(onClick = { onPause(task.releaseName) }) {
                                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White)
                                        }
                                    } else if (isPaused || task.status == InstallTaskStatus.FAILED) {
                                        IconButton(onClick = { onResume(task.releaseName) }) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color(0xFF2ecc71))
                                        }
                                    }
                                    
                                    IconButton(onClick = { onCancel(task.releaseName) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Cancel", tint = Color(0xFFCF6679))
                                    }
                                }
                            }
                            
                            if (isProcessing || (task.progress > 0 && task.status != InstallTaskStatus.COMPLETED)) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = task.progress.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = if (isPaused) Color.Gray else MaterialTheme.colorScheme.secondary,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                                if (task.currentSize != null) {
                                    Text(
                                        text = "${task.currentSize} / ${task.totalSize}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray,
                                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("BACK TO CATALOG", fontWeight = FontWeight.Black)
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
