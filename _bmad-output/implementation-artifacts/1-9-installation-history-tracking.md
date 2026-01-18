# Story 1.9: Installation History Tracking

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a user,
I want to view a complete history of all my game downloads and installations,
So that I can track what I've installed, when, and troubleshoot any issues.

## Acceptance Criteria

**Given** user has completed several game installations
**When** user opens the "Installation History" screen
**Then** displays chronological list of all completed installations with date, game name, size, and status
**And** history persists across app restarts and device reboots
**And** user can search/filter history by game name
**And** user can view detailed information for each installation (download duration, installation date, file size)
**And** user can delete individual history entries
**And** user can export history as text file for troubleshooting
**And** history automatically archives completed installations from queue

## Tasks / Subtasks

- [ ] Create Room Database table for installation history (AC: 1, 2)
  - [ ] Define `InstallHistoryEntity` with columns: id (PK, auto-increment), releaseName, gameName, packageName, installedAt, downloadDurationMs, fileSizeBytes, status (SUCCESS/FAILED), errorMessage
  - [ ] Create `InstallHistoryDao` with CRUD operations
  - [ ] Add indexes on `installedAt` (for chronological sorting) and `releaseName` (for search)
  - [ ] Implement Room migration 4→5 to add new table

- [ ] Implement auto-archiving from queue to history (AC: 2, 6)
  - [ ] When queue task reaches COMPLETED status → insert into history table
  - [ ] Calculate download duration (createdAt - completedAt)
  - [ ] Extract file size from queue entity
  - [ ] When queue task reaches FAILED status → insert into history with error message
  - [ ] Remove completed/failed tasks from queue after archiving

- [ ] Create Installation History UI screen (AC: 1, 3, 4)
  - [ ] New Compose screen "Installation History" accessible from navigation drawer
  - [ ] LazyColumn displaying all history entries (newest first)
  - [ ] Each entry shows: game name, date (relative: "2 hours ago", "Yesterday"), size, status icon (✓ or ✗)
  - [ ] Tap entry to expand details: full timestamp, download duration, package name, error message (if failed)
  - [ ] Search bar at top for filtering by game name
  - [ ] Empty state: "No installations yet - Start downloading games!"

- [ ] Implement history search and filtering (AC: 3)
  - [ ] Real-time search filtering (debounced 300ms)
  - [ ] Filter by status: All, Success, Failed
  - [ ] Sort options: Newest first (default), Oldest first, Name A-Z, Size

- [ ] Add history management actions (AC: 5)
  - [ ] Long-press entry → "Delete" option
  - [ ] Confirmation dialog: "Delete this history entry?"
  - [ ] Delete removes entry from database (not from actual installed game)
  - [ ] Settings option: "Clear all history" with confirmation

- [ ] Implement history export (AC: 6)
  - [ ] "Export History" button in History screen
  - [ ] Generate text file: `RookieOnQuest_InstallHistory_YYYY-MM-DD.txt`
  - [ ] Format: CSV-like (releaseName, installedAt ISO8601, status, size, duration)
  - [ ] Save to `/sdcard/Download/RookieOnQuest/logs/`
  - [ ] Toast notification with file path

- [ ] Write unit tests for history tracking (AC: 2, 6)
  - [ ] Test InstallHistoryEntity creation and validation
  - [ ] Test DAO insert, query, delete operations
  - [ ] Test auto-archiving logic (queue COMPLETED → history insert)
  - [ ] Test search filtering logic
  - [ ] Test history export file generation

## Dev Notes

### Architecture Context

**Purpose of Installation History:**

This feature addresses the user's need to **audit and troubleshoot installations**. Unlike the installation queue (which tracks **active/pending** tasks), the history tracks **completed/failed** installations permanently.

**Key Differences: Queue vs History**

| Aspect | Queue (`install_queue`) | History (`install_history`) |
|--------|------------------------|----------------------------|
| **Purpose** | Track active downloads/installations | Audit log of completed installations |
| **Lifecycle** | Temporary (deleted on completion) | Permanent (until user deletes) |
| **Status** | QUEUED, DOWNLOADING, EXTRACTING, etc. | SUCCESS or FAILED (terminal states) |
| **Updates** | Frequent (progress updates 1Hz) | Write-once (on completion) |
| **Size** | Small (1-10 entries typically) | Unbounded (grows over time) |
| **UI** | Queue overlay (modal) | Dedicated History screen |

**Auto-Archiving Flow:**

```
Queue Task Completes (COMPLETED or FAILED)
  ↓
Extract data: releaseName, gameName, fileSizeBytes, duration
  ↓
Insert into install_history table
  ↓
Remove from install_queue table
  ↓
UI updates: Queue overlay clears, History screen shows new entry
```

**Data Flow:**

```
MainRepository.installGame() completes
  ↓
ViewModel updates queue status → COMPLETED
  ↓
Repository.archiveToHistory(queueEntity)
  ↓
InstallHistoryDao.insert(historyEntity)
  ↓
QueuedInstallDao.delete(queueEntity)
  ↓
Flow<List<InstallHistoryEntity>> emits to UI
```

### Technical Requirements

**Database Schema Design**

**Table: `install_history`**

```sql
CREATE TABLE install_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    releaseName TEXT NOT NULL,
    gameName TEXT NOT NULL,
    packageName TEXT NOT NULL,
    installedAt INTEGER NOT NULL,          -- Unix epoch millis
    downloadDurationMs INTEGER NOT NULL,   -- Time from queue creation to completion
    fileSizeBytes INTEGER NOT NULL,        -- Total download size
    status TEXT NOT NULL,                   -- 'SUCCESS' or 'FAILED'
    errorMessage TEXT,                      -- Null if success, error details if failed
    createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
    FOREIGN KEY (releaseName) REFERENCES games(releaseName) ON DELETE CASCADE
);

CREATE INDEX index_install_history_installedAt ON install_history(installedAt DESC);
CREATE INDEX index_install_history_releaseName ON install_history(releaseName);
CREATE INDEX index_install_history_status ON install_history(status);
```

**Entity Design:**

```kotlin
@Entity(
    tableName = "install_history",
    indices = [
        Index(value = ["installedAt"], orders = [Index.Order.DESC]),
        Index(value = ["releaseName"]),
        Index(value = ["status"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["releaseName"],
            childColumns = ["releaseName"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
@Immutable
data class InstallHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val releaseName: String,
    val gameName: String,
    val packageName: String,

    val installedAt: Long,              // Completion timestamp
    val downloadDurationMs: Long,       // Total duration (ms)
    val fileSizeBytes: Long,            // Total file size

    val status: String,                 // "SUCCESS" or "FAILED"
    val errorMessage: String? = null,   // Error details if failed

    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(releaseName.isNotBlank()) { "releaseName cannot be blank" }
        require(gameName.isNotBlank()) { "gameName cannot be blank" }
        require(installedAt > 0) { "installedAt must be positive" }
        require(downloadDurationMs >= 0) { "downloadDurationMs cannot be negative" }
        require(fileSizeBytes > 0) { "fileSizeBytes must be positive" }
        require(status in listOf("SUCCESS", "FAILED")) { "status must be SUCCESS or FAILED" }
        if (status == "FAILED") {
            require(!errorMessage.isNullOrBlank()) { "errorMessage required for FAILED status" }
        }
    }

    companion object {
        fun fromQueueEntity(
            queueEntity: QueuedInstallEntity,
            gameEntity: GameEntity,
            status: String,
            errorMessage: String? = null
        ): InstallHistoryEntity {
            val duration = System.currentTimeMillis() - queueEntity.createdAt
            return InstallHistoryEntity(
                releaseName = queueEntity.releaseName,
                gameName = gameEntity.gameName,
                packageName = gameEntity.packageName,
                installedAt = System.currentTimeMillis(),
                downloadDurationMs = duration,
                fileSizeBytes = queueEntity.totalBytes ?: 0L,
                status = status,
                errorMessage = errorMessage
            )
        }
    }
}
```

**DAO Interface:**

```kotlin
@Dao
interface InstallHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: InstallHistoryEntity)

    @Query("SELECT * FROM install_history ORDER BY installedAt DESC")
    fun getAllFlow(): Flow<List<InstallHistoryEntity>>

    @Query("SELECT * FROM install_history WHERE releaseName LIKE '%' || :query || '%' OR gameName LIKE '%' || :query || '%' ORDER BY installedAt DESC")
    fun searchFlow(query: String): Flow<List<InstallHistoryEntity>>

    @Query("SELECT * FROM install_history WHERE status = :status ORDER BY installedAt DESC")
    fun filterByStatusFlow(status: String): Flow<List<InstallHistoryEntity>>

    @Query("DELETE FROM install_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM install_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM install_history")
    suspend fun getCount(): Int
}
```

**Auto-Archiving Logic (MainRepository):**

```kotlin
// MainRepository.kt

suspend fun archiveCompletedTask(queueEntity: QueuedInstallEntity, status: String, errorMessage: String? = null) {
    val gameEntity = db.gameDao().getByReleaseName(queueEntity.releaseName)
    if (gameEntity == null) {
        Log.w(TAG, "Cannot archive task: game not found in catalog: ${queueEntity.releaseName}")
        return
    }

    val historyEntity = InstallHistoryEntity.fromQueueEntity(
        queueEntity = queueEntity,
        gameEntity = gameEntity,
        status = status,
        errorMessage = errorMessage
    )

    // Insert into history
    db.installHistoryDao().insert(historyEntity)

    // Remove from queue
    db.queuedInstallDao().delete(queueEntity)

    Log.i(TAG, "Archived installation: ${gameEntity.gameName} (status: $status)")
}

// Called from queue processor after task completion
private suspend fun runTask(task: QueuedInstallEntity) {
    try {
        // ... existing installGame() logic ...

        // On success
        archiveCompletedTask(task, status = "SUCCESS")

    } catch (e: Exception) {
        Log.e(TAG, "Installation failed", e)

        // On failure
        archiveCompletedTask(
            task,
            status = "FAILED",
            errorMessage = e.message ?: "Unknown error"
        )
    }
}
```

### Library/Framework Requirements

**No new dependencies required:**

- Room Database (already in use)
- Kotlin Coroutines + Flow (already in use)
- Jetpack Compose (already in use)

**Database Migration 4 → 5:**

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create install_history table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS install_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                releaseName TEXT NOT NULL,
                gameName TEXT NOT NULL,
                packageName TEXT NOT NULL,
                installedAt INTEGER NOT NULL,
                downloadDurationMs INTEGER NOT NULL,
                fileSizeBytes INTEGER NOT NULL,
                status TEXT NOT NULL,
                errorMessage TEXT,
                createdAt INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
                FOREIGN KEY (releaseName) REFERENCES games(releaseName) ON DELETE CASCADE
            )
        """.trimIndent())

        // Create indexes
        database.execSQL("CREATE INDEX IF NOT EXISTS index_install_history_installedAt ON install_history(installedAt DESC)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_install_history_releaseName ON install_history(releaseName)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_install_history_status ON install_history(status)")
    }
}

@Database(
    entities = [GameEntity::class, QueuedInstallEntity::class, InstallHistoryEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun queuedInstallDao(): QueuedInstallDao
    abstract fun installHistoryDao(): InstallHistoryDao

    companion object {
        // ... existing singleton code ...
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
```

**UI Components (Compose):**

```kotlin
// InstallHistoryScreen.kt

@Composable
fun InstallHistoryScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val historyItems by viewModel.installHistory.collectAsState()
    val searchQuery by viewModel.historySearchQuery.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Installation History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.exportHistory() }) {
                        Icon(Icons.Default.Share, "Export")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateHistorySearch(it) },
                placeholder = { Text("Search games...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            // History list
            if (historyItems.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn {
                    items(historyItems, key = { it.id }) { item ->
                        HistoryListItem(
                            item = item,
                            onDelete = { viewModel.deleteHistoryEntry(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryListItem(
    item: InstallHistoryEntity,
    onDelete: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.gameName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = formatRelativeTime(item.installedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status icon
                Icon(
                    imageVector = if (item.status == "SUCCESS") Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = item.status,
                    tint = if (item.status == "SUCCESS") Color.Green else Color.Red
                )
            }

            // Expanded details
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Text("Size: ${formatSize(item.fileSizeBytes)}")
                Text("Duration: ${formatDuration(item.downloadDurationMs)}")
                Text("Installed: ${formatTimestamp(item.installedAt)}")

                if (item.status == "FAILED" && item.errorMessage != null) {
                    Text(
                        text = "Error: ${item.errorMessage}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Delete button
                TextButton(
                    onClick = { onDelete(item.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}
```

**ViewModel Integration:**

```kotlin
// MainViewModel.kt

private val _historySearchQuery = MutableStateFlow("")
val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

val installHistory: StateFlow<List<InstallHistoryEntity>> = _historySearchQuery
    .debounce(300) // Debounce search
    .flatMapLatest { query ->
        if (query.isBlank()) {
            repository.getAllHistory()
        } else {
            repository.searchHistory(query)
        }
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

fun updateHistorySearch(query: String) {
    _historySearchQuery.value = query
}

fun deleteHistoryEntry(id: Long) {
    viewModelScope.launch {
        repository.deleteHistoryEntry(id)
    }
}

fun exportHistory() {
    viewModelScope.launch {
        val path = repository.exportHistoryToFile()
        _events.emit(MainEvent.ShowToast("History exported to: $path"))
    }
}
```

**Export Functionality:**

```kotlin
// MainRepository.kt

suspend fun exportHistoryToFile(): String = withContext(Dispatchers.IO) {
    val history = db.installHistoryDao().getAllFlow().first()

    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    val fileName = "RookieOnQuest_InstallHistory_$timestamp.txt"
    val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RookieOnQuest/logs")
    outputDir.mkdirs()

    val outputFile = File(outputDir, fileName)

    outputFile.bufferedWriter().use { writer ->
        writer.write("# Rookie On Quest - Installation History\n")
        writer.write("# Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
        writer.write("ReleaseName,GameName,InstalledAt,Status,SizeBytes,DurationMs,Error\n")

        history.forEach { entry ->
            val line = "${entry.releaseName},${entry.gameName},${entry.installedAt},${entry.status},${entry.fileSizeBytes},${entry.downloadDurationMs},${entry.errorMessage ?: ""}\n"
            writer.write(line)
        }
    }

    outputFile.absolutePath
}
```

### File Structure Requirements

**Files to Create:**
1. `app/src/main/java/com/vrpirates/rookieonquest/data/InstallHistoryEntity.kt`
2. `app/src/main/java/com/vrpirates/rookieonquest/data/InstallHistoryDao.kt`
3. `app/src/main/java/com/vrpirates/rookieonquest/ui/InstallHistoryScreen.kt`
4. `app/src/test/java/com/vrpirates/rookieonquest/data/InstallHistoryDaoTest.kt`

**Files to Modify:**
1. `app/src/main/java/com/vrpirates/rookieonquest/data/AppDatabase.kt` - Add entity, DAO, migration 4→5
2. `app/src/main/java/com/vrpirates/rookieonquest/MainRepository.kt` - Add archiving logic, export function
3. `app/src/main/java/com/vrpirates/rookieonquest/MainViewModel.kt` - Add history StateFlow, search, delete
4. `app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt` - Add navigation to History screen

### Testing Requirements

**Unit Tests (InstallHistoryDaoTest.kt):**

```kotlin
@RunWith(AndroidJUnit4::class)
class InstallHistoryDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: InstallHistoryDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.installHistoryDao()
    }

    @Test
    fun insertAndRetrieve_returnsCorrectEntry() = runBlocking {
        val entry = InstallHistoryEntity(
            releaseName = "BeatSaber-1.34.5",
            gameName = "Beat Saber",
            packageName = "com.beatgames.beatsaber",
            installedAt = System.currentTimeMillis(),
            downloadDurationMs = 120000,
            fileSizeBytes = 3221225472,
            status = "SUCCESS"
        )

        dao.insert(entry)

        val retrieved = dao.getAllFlow().first()
        assertEquals(1, retrieved.size)
        assertEquals("Beat Saber", retrieved[0].gameName)
        assertEquals("SUCCESS", retrieved[0].status)
    }

    @Test
    fun search_filtersCorrectly() = runBlocking {
        dao.insert(createEntry("Game1", "First Game"))
        dao.insert(createEntry("Game2", "Second Game"))
        dao.insert(createEntry("Game3", "Beat Saber"))

        val results = dao.searchFlow("Beat").first()
        assertEquals(1, results.size)
        assertEquals("Beat Saber", results[0].gameName)
    }

    @Test
    fun filterByStatus_returnsOnlyFailed() = runBlocking {
        dao.insert(createEntry("Game1", status = "SUCCESS"))
        dao.insert(createEntry("Game2", status = "FAILED", errorMessage = "Network error"))
        dao.insert(createEntry("Game3", status = "SUCCESS"))

        val failed = dao.filterByStatusFlow("FAILED").first()
        assertEquals(1, failed.size)
        assertEquals("FAILED", failed[0].status)
    }

    @Test
    fun deleteById_removesEntry() = runBlocking {
        val entry = createEntry("Game1")
        dao.insert(entry)

        val inserted = dao.getAllFlow().first()
        val id = inserted[0].id

        dao.deleteById(id)

        val remaining = dao.getAllFlow().first()
        assertTrue(remaining.isEmpty())
    }

    private fun createEntry(
        releaseName: String,
        gameName: String = releaseName,
        status: String = "SUCCESS",
        errorMessage: String? = null
    ) = InstallHistoryEntity(
        releaseName = releaseName,
        gameName = gameName,
        packageName = "com.test.$releaseName",
        installedAt = System.currentTimeMillis(),
        downloadDurationMs = 60000,
        fileSizeBytes = 1000000,
        status = status,
        errorMessage = errorMessage
    )
}
```

### Previous Story Intelligence

**Story 1.1 & 1.2 Learnings:**

From previous stories in Epic 1:

**✅ Patterns to Reuse:**
- Room entity with comprehensive validation in `init` block
- Foreign key relationship to `GameEntity` for referential integrity
- Indexes on frequently queried columns (installedAt DESC for chronological sort)
- Flow-based reactive queries for automatic UI updates
- Explicit migrations with SQL CREATE TABLE statements

**⚠️ Considerations:**
- **Storage Growth:** History table grows unbounded - consider adding auto-cleanup (delete entries older than 90 days) in future
- **Performance:** With 1000+ entries, LazyColumn pagination recommended
- **Foreign Key Cascade:** If user deletes game from catalog, history entries also deleted (acceptable trade-off)

### Git Intelligence Summary

**Recent Patterns:**
- Favorites system (be230a7) added similar persistent tracking feature
- Used Room entity + DAO + Flow pattern
- UI integration via StateFlow in ViewModel
- Similar search/filter requirements

**Code Style:**
- Use `@Immutable` for Compose entities
- Validation in entity `init` blocks
- Companion object factory methods (e.g., `fromQueueEntity()`)
- Explicit error logging with `Log.e(TAG, message, exception)`

### Latest Technical Information

**Room Foreign Keys Best Practices (2024-2026):**

- Use `ForeignKey.CASCADE` for dependent data (history depends on game existence)
- Index foreign key columns for query performance
- Test cascade delete behavior explicitly

**Compose LazyColumn Performance:**

- Use `key` parameter in `items()` for stable identity
- Avoid expensive operations in item composition
- Consider `rememberLazyListState()` for scroll position persistence

**Date Formatting:**

```kotlin
fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(timestamp))
    }
}
```

### Project Context Reference

**CLAUDE.md Key Points:**

From [CLAUDE.md](../../CLAUDE.md:1):

**Data Flow Pattern:**
- Room Database → Flow → Repository → ViewModel → StateFlow → UI
- All database queries return `Flow<T>` for reactive updates
- UI observes via `.collectAsState()` in Compose

**Storage Guidelines:**
- App internal storage: `context.filesDir` (for database, cache)
- Public downloads: `/sdcard/Download/RookieOnQuest/` (for logs, exports)
- Use `Environment.getExternalStoragePublicDirectory()` for user-accessible files

**Testing Requirements:**
- Unit tests for DAO operations (in-memory database)
- Integration tests for archiving flow (instrumented tests)
- UI tests for History screen (Compose Testing)

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-01-09: Story 1.9 created to track complete installation history with search, filtering, export, and auto-archiving from queue
