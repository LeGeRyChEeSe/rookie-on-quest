package com.vrpirates.rookieonquest

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.vrpirates.rookieonquest.ui.GameListItem
import com.vrpirates.rookieonquest.ui.MainEvent
import com.vrpirates.rookieonquest.ui.MainViewModel
import com.vrpirates.rookieonquest.ui.RequiredPermission
import com.vrpirates.rookieonquest.ui.theme.RookieOnQuestTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RookieOnQuestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
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
    val isInstalling by viewModel.isInstalling.collectAsState()
    val error by viewModel.error.collectAsState()
    val progressMessage by viewModel.progressMessage.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val missingPermissions by viewModel.missingPermissions.collectAsState()
    val alphabetInfo by viewModel.alphabetInfo.collectAsState()
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Handle events from ViewModel
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
            }
        }
    }

    // Show errors in a snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    // Refresh data when returning to the app
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF0F0F0F))) {
                TopAppBar(
                    title = { Text("ROOKIE ON QUEST", color = Color.White, style = MaterialTheme.typography.titleSmall) },
                    actions = {
                        IconButton(
                            onClick = { if (!isInstalling && missingPermissions?.isEmpty() == true) viewModel.refreshData() },
                            enabled = !isInstalling && missingPermissions?.isEmpty() == true
                        ) {
                            Icon(
                                Icons.Default.Refresh, 
                                contentDescription = "Refresh", 
                                tint = if (isInstalling || missingPermissions?.isNotEmpty() == true) Color.DarkGray else Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1A1A1A)
                    )
                )
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    placeholder = { Text("Search games...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    singleLine = true,
                    enabled = !isInstalling && missingPermissions?.isEmpty() == true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0A0A0A),
                        unfocusedContainerColor = Color(0xFF0A0A0A),
                        disabledContainerColor = Color(0xFF050505),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        disabledTextColor = Color.Gray,
                        cursorColor = Color(0xFF3498db)
                    )
                )
                Divider(color = Color.DarkGray)
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                missingPermissions == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingScreen("Checking permissions...")
                    }
                }
                missingPermissions!!.isNotEmpty() -> {
                    PermissionOverlay(
                        missingPermissions = missingPermissions!!,
                        onGrantClick = { viewModel.startPermissionFlow() }
                    )
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isRefreshing && games.isEmpty()) {
                            LoadingScreen("Loading Catalog...")
                        } else if (error != null && games.isEmpty()) {
                            ErrorScreen(error!!, onRetry = { viewModel.refreshData() })
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (games.isNotEmpty() && searchQuery.isEmpty()) {
                                    AlphabetIndexer(
                                        alphabetInfo = alphabetInfo,
                                        isInstalling = isInstalling,
                                        onLetterClick = { index ->
                                            coroutineScope.launch { listState.scrollToItem(index) }
                                        }
                                    )
                                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = Color.DarkGray)
                                }

                                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    items(
                                        items = games,
                                        key = { it.packageName + it.releaseName }, // Fix: Ensure unique key
                                        contentType = { "game_item" }
                                    ) { game ->
                                        GameListItem(
                                            game = game,
                                            onInstallClick = { if (!isInstalling) viewModel.installGame(game.packageName) },
                                            onUninstallClick = { viewModel.uninstallGame(game.packageName) }
                                        )
                                    }
                                }
                            }
                        }
                        
                        if (isInstalling) {
                            InstallationOverlay(
                                progressMessage = progressMessage,
                                onCancel = { viewModel.cancelInstall() }
                            )
                        }

                        if (isRefreshing && games.isNotEmpty()) {
                            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.7f)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(color = Color(0xFF3498db))
                                    Text(text = "Checking for updates...", modifier = Modifier.padding(top = 16.dp), color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color(0xFF3498db))
        Text(text = message, modifier = Modifier.padding(top = 16.dp), color = Color.White)
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = message, color = Color.Red)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text("Retry")
        }
    }
}

@Composable
fun InstallationOverlay(progressMessage: String?, onCancel: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.9f)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(64.dp), color = Color(0xFF3498db))
            Spacer(modifier = Modifier.height(24.dp))
            progressMessage?.let {
                Text(text = it, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("CANCEL INSTALLATION", color = Color.White)
            }
        }
    }
}

@Composable
fun AlphabetIndexer(
    alphabetInfo: Pair<List<Char>, Map<Char, Int>>,
    isInstalling: Boolean,
    onLetterClick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(32.dp)
            .background(Color(0xFF0A0A0A).copy(alpha = 0.8f))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        alphabetInfo.first.forEach { char ->
            key(char) {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val scale by animateFloatAsState(if (isHovered) 2.5f else 1f, label = "magnify")
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                        fontSize = 10.sp,
                        color = if (isHovered) Color(0xFF3498db) else if (isInstalling) Color.DarkGray else Color.Gray,
                        modifier = Modifier.scale(scale)
                    )
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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = Color(0xFF3498db),
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "To provide a seamless experience on your Quest device, Rookie On Quest requires the following permissions:",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                missingPermissions.forEach { permission ->
                    PermissionItem(permission)
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onGrantClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498db)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (missingPermissions.size > 1) "GRANT ALL PERMISSIONS" else "GRANT PERMISSION",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Rookie On Quest will not be able to display the game catalog until these permissions are granted.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PermissionItem(permission: RequiredPermission) {
    val (title, description) = when (permission) {
        RequiredPermission.INSTALL_UNKNOWN_APPS -> 
            "Install Unknown Apps" to "Allows the app to launch the Android installer to install your games after they are downloaded."
        RequiredPermission.MANAGE_EXTERNAL_STORAGE -> 
            "Manage External Storage" to "Required to move large game data files (OBB) to the appropriate system folders on your Quest."
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = Color.Gray, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
