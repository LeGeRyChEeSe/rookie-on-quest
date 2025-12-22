package com.vrpirates.rookieonquest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vrpirates.rookieonquest.ui.GameListItem
import com.vrpirates.rookieonquest.ui.MainEvent
import com.vrpirates.rookieonquest.ui.MainViewModel
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
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Handle events from ViewModel (like Uninstall)
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is MainEvent.Uninstall -> {
                    try {
                        val intent = Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.fromParts("package", event.packageName, null)
                            // DO NOT use NEW_TASK when calling from Activity context
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to uninstall: ${e.message}")
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

    // Refresh data when returning to the app (e.g. after uninstall/install)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val alphabetInfo = remember(games) {
        val chars = mutableSetOf<Char>()
        val charToIndex = mutableMapOf<Char, Int>()
        games.forEachIndexed { index, game ->
            val firstChar = game.name.firstOrNull()?.uppercaseChar() ?: '_'
            if (!charToIndex.containsKey(firstChar)) {
                chars.add(firstChar)
                charToIndex[firstChar] = index
            }
        }
        val sortedList = chars.sorted().toMutableList()
        if (sortedList.contains('_')) {
            sortedList.remove('_')
            sortedList.add(0, '_')
        }
        sortedList to charToIndex
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF0F0F0F))) {
                TopAppBar(
                    title = { Text("ROOKIE ON QUEST", color = Color.White, style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(
                            onClick = { if (!isInstalling) viewModel.refreshData() },
                            enabled = !isInstalling
                        ) {
                            Icon(
                                Icons.Default.Refresh, 
                                contentDescription = "Refresh", 
                                tint = if (isInstalling) Color.DarkGray else Color.White
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
                    enabled = !isInstalling,
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
            Image(
                painter = painterResource(id = R.drawable.pattern_cubes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Inside,
                alpha = 0.5f
            )

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isRefreshing && games.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF3498db))
                        Text(text = "Loading Catalog...", modifier = Modifier.padding(top = 16.dp), color = Color.White)
                    }
                } else if (error != null && games.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = error!!, color = Color.Red)
                        Button(onClick = { viewModel.refreshData() }, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Retry")
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (games.isNotEmpty() && searchQuery.isEmpty()) {
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
                                        var isHovered by remember { mutableStateOf(false) }
                                        val scale by animateFloatAsState(if (isHovered) 2.5f else 1f, label = "magnify")
                                        
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .pointerInput(isInstalling) {
                                                    if (isInstalling) return@pointerInput
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            when (event.type) {
                                                                PointerEventType.Enter -> isHovered = true
                                                                PointerEventType.Exit -> isHovered = false
                                                            }
                                                        }
                                                    }
                                                }
                                                .clickable(enabled = !isInstalling) {
                                                    alphabetInfo.second[char]?.let { index ->
                                                        coroutineScope.launch { listState.scrollToItem(index) }
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
                            Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = Color.DarkGray)
                        }

                        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                            items(
                                items = games,
                                key = { it.releaseName }
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
                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.9f)) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(64.dp), color = Color(0xFF3498db))
                            Spacer(modifier = Modifier.height(24.dp))
                            progressMessage?.let {
                                Text(text = it, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(
                                onClick = { viewModel.cancelInstall() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679)),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                            ) {
                                Text("CANCEL INSTALLATION", color = Color.White)
                            }
                        }
                    }
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
