# Story 1.1: Room Database Queue Table Setup

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want to create the Room Database table for persistent queue storage,
So that installation queue state survives app restarts and device reboots.

## Acceptance Criteria

**Given** the app is launched for the first time on v2.5.0
**When** Room Database initializes
**Then** table `install_queue` is created with columns: releaseName (PK), status, progress, downloadedBytes, totalBytes, queuePosition, createdAt, lastUpdatedAt
**And** appropriate indexes are created for query performance
**And** DAO methods support CRUD operations (insert, update, delete, query)

## Tasks / Subtasks

- [ ] Create `QueuedInstallEntity` data class with Room annotations (AC: 1)
  - [ ] Define all columns as specified in acceptance criteria
  - [ ] Add primary key annotation to releaseName
  - [ ] Add appropriate type converters if needed
  - [ ] Ensure data class is immutable (@Immutable annotation for Compose)

- [ ] Create `QueuedInstallDao` interface with CRUD operations (AC: 1)
  - [ ] Add @Insert method with conflict strategy (OnConflictStrategy.REPLACE)
  - [ ] Add @Update method for updating existing queue items
  - [ ] Add @Delete method for removing completed/cancelled items
  - [ ] Add @Query method to get all queue items: `Flow<List<QueuedInstallEntity>>` ordered by queuePosition
  - [ ] Add @Query method to get single item by releaseName
  - [ ] Add @Query method to update status of specific item
  - [ ] Add @Query method to update progress fields (progress, downloadedBytes, totalBytes)

- [ ] Update `AppDatabase` class to include new table (AC: 1)
  - [ ] Increment database version from 2 to 3
  - [ ] Add QueuedInstallEntity to @Database entities array
  - [ ] Add abstract function for QueuedInstallDao
  - [ ] Implement migration from version 2 to 3 (or use fallbackToDestructiveMigration since it's cache)

- [ ] Create database indexes for performance (AC: 1)
  - [ ] Add @Index on queuePosition column for ordering queries
  - [ ] Add @Index on status column for filtering by status
  - [ ] Consider composite index on (status, queuePosition) for queue processor query optimization

- [ ] Write unit tests for DAO operations (AC: 1)
  - [ ] Test insert operation with valid entity
  - [ ] Test query all returns Flow that emits on insert/update
  - [ ] Test update operations modify correct fields
  - [ ] Test delete operation removes entity
  - [ ] Test query by releaseName returns correct entity
  - [ ] Test ordering by queuePosition works correctly

## Dev Notes

### Architecture Context

**Current Database Structure:**
- Location: `app/src/main/java/com/vrpirates/rookieonquest/data/AppDatabase.kt`
- Current version: 2 (as of v2.4.0)
- Existing tables: `games` (GameEntity with GameDao)
- Migration strategy: `fallbackToDestructiveMigration()` (acceptable for catalog cache)

**MVVM Pattern:**
- Room Database → Repository → ViewModel → UI
- All database operations are suspend functions (coroutine-based)
- StateFlow used for reactive state management
- Data flows through Room's `Flow<T>` for automatic UI updates

**Key Architectural Decisions from Architecture.md:**
1. Repository Pattern - MainRepository abstracts all data sources
2. No DI Framework - Manual dependency injection (simple for single-module)
3. Single-Activity Compose - All UI in MainActivity
4. Reactive State - StateFlow for multi-value state, SharedFlow for one-time events

### Technical Requirements

**Room Version:**
- Current: `androidx.room:room-runtime:2.6.1`
- KSP processor: `androidx.room:room-compiler:2.6.1`
- Kotlin extensions: `androidx.room:room-ktx:2.6.1`

**Entity Design Guidelines:**
- Use `@Entity(tableName = "install_queue")` to explicitly name table
- Primary key should be `releaseName: String` (matches existing game identification pattern)
- All timestamp fields should be `Long` (Unix epoch millis)
- Nullable fields only where truly optional (downloadedBytes, totalBytes can be null initially)

**DAO Design Guidelines:**
- All query methods that observe changes should return `Flow<T>`
- Single-result queries can return nullable types or throw
- Update/delete operations should return `Int` (number of rows affected) for validation
- Use `@Transaction` for atomic multi-step operations

**Status Field Values:**
- Recommended enum or sealed class: QUEUED, DOWNLOADING, EXTRACTING, COPYING_OBB, INSTALLING, PAUSED, COMPLETED, FAILED
- Store as String in database for flexibility
- Consider adding status transition validation logic

### Library/Framework Requirements

**Room Database:**
```kotlin
// Entity example structure
@Entity(tableName = "install_queue")
data class QueuedInstallEntity(
    @PrimaryKey val releaseName: String,
    val status: String,
    val progress: Float, // 0.0 to 1.0
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val queuePosition: Int,
    val createdAt: Long,
    val lastUpdatedAt: Long
)

// DAO example structure
@Dao
interface QueuedInstallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QueuedInstallEntity)

    @Query("SELECT * FROM install_queue ORDER BY queuePosition ASC")
    fun getAllFlow(): Flow<List<QueuedInstallEntity>>

    @Query("UPDATE install_queue SET status = :status, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateStatus(releaseName: String, status: String, timestamp: Long): Int

    @Query("UPDATE install_queue SET progress = :progress, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateProgress(releaseName: String, progress: Float, downloadedBytes: Long?, totalBytes: Long?, timestamp: Long): Int
}

// Database update
@Database(
    entities = [GameEntity::class, QueuedInstallEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun queuedInstallDao(): QueuedInstallDao

    // Keep existing singleton pattern with fallbackToDestructiveMigration()
}
```

**Migration Strategy:**
Since this is a new table (not modifying existing `games` table), migration can be simple:
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create new install_queue table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS install_queue (
                releaseName TEXT PRIMARY KEY NOT NULL,
                status TEXT NOT NULL,
                progress REAL NOT NULL,
                downloadedBytes INTEGER,
                totalBytes INTEGER,
                queuePosition INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                lastUpdatedAt INTEGER NOT NULL
            )
        """.trimIndent())

        // Create indexes
        database.execSQL("CREATE INDEX IF NOT EXISTS index_install_queue_queuePosition ON install_queue(queuePosition)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_install_queue_status ON install_queue(status)")
    }
}
```

**However**, since existing database uses `fallbackToDestructiveMigration()`, you can continue with that pattern (acceptable for catalog cache). If providing explicit migration, add to Room.databaseBuilder():
```kotlin
.addMigrations(MIGRATION_2_3)
```

### File Structure Requirements

**Files to Create:**
1. `app/src/main/java/com/vrpirates/rookieonquest/data/QueuedInstallEntity.kt` - Entity class
2. `app/src/main/java/com/vrpirates/rookieonquest/data/QueuedInstallDao.kt` - DAO interface

**Files to Modify:**
1. `app/src/main/java/com/vrpirates/rookieonquest/data/AppDatabase.kt` - Add entity, DAO, increment version
2. `app/build.gradle.kts` - Verify Room dependencies are present (should already be there)

**Test Files to Create:**
1. `app/src/test/java/com/vrpirates/rookieonquest/data/QueuedInstallDaoTest.kt` - Unit tests for DAO

### Testing Requirements

**Unit Tests (Room DAO):**
- Use in-memory database for testing (Room provides testing support)
- Verify reactive Flow emissions trigger on data changes
- Test all CRUD operations
- Test concurrent access patterns (multiple coroutines)

**Test Dependencies:**
```kotlin
testImplementation("androidx.room:room-testing:2.6.1")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
testImplementation("junit:junit:4.13.2")
```

**Example Test Structure:**
```kotlin
@RunWith(AndroidJUnit4::class)
class QueuedInstallDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: QueuedInstallDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.queuedInstallDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndRetrieve_returnsCorrectEntity() = runBlocking {
        val entity = QueuedInstallEntity(...)
        dao.insert(entity)

        val result = dao.getAllFlow().first()
        assertEquals(1, result.size)
        assertEquals(entity.releaseName, result[0].releaseName)
    }
}
```

### Previous Story Intelligence

**Context:** This is Story 1.1 - the FIRST story in Epic 1 and the entire sprint. There are no previous stories to learn from yet.

**Implications:**
- This story establishes the foundation for the entire Epic 1 (Persistent Installation Queue System)
- Future stories (1.2 through 1.8) will build upon this database structure
- Quality and design decisions here will impact all subsequent stories
- Take extra care with entity design - changing it later will require migrations

**Critical Success Factors:**
- Entity schema must be complete and well-designed (avoid future schema changes)
- DAO operations must be efficient (queue processor will query frequently)
- Reactive Flow must work correctly (UI depends on automatic updates)
- Migration strategy must be clear for v2.4.0 → v2.5.0 users

### Git Intelligence Summary

**Recent Commits Analysis:**
1. **be230a7 - feat: add favorites system** (v2.4.0)
   - Added `isFavorite` column to GameEntity
   - Demonstrates pattern for adding new columns to Room entities
   - GameDao.updateFavorite() method shows update pattern
   - UI integration in GameListItem composable shows reactive Flow usage

2. **d637d95 - chore: release v2.4.0**
   - Special install.txt handling added to MainRepository
   - Battery optimization permission added (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
   - Shows pattern for adding new permissions to manifest

**Patterns to Follow:**
- **Room Entity Changes:** Add fields with appropriate annotations, increment DB version
- **DAO Methods:** Use suspend functions for write operations, Flow for reactive reads
- **Repository Integration:** Expose DAO operations through repository methods
- **ViewModel Usage:** Collect Flow in ViewModel, transform to StateFlow for UI

**Code Style Observations:**
- Kotlin coroutines used extensively (suspend functions)
- StateFlow for multi-value state management
- Compose UI observes state via `.collectAsState()`
- Clean separation: Entity → DAO → Repository → ViewModel → UI

### Latest Technical Information

**Room 2.6.1 (Current Version - Latest Stable):**
- Released: December 2023
- Key features relevant to this story:
  - Type converters for complex types (if needed for status enum)
  - Flow support fully stable
  - KSP (Kotlin Symbol Processing) is recommended processor
  - Multi-module database support (not needed for single-module app)

**Best Practices (2024-2026):**
- Use KSP instead of KAPT for Room annotation processing (faster builds)
- Return `Flow<T>` for all queries that need reactive updates
- Use `@Transaction` for atomic multi-step operations
- Avoid `runBlocking` in production code - use suspend functions
- Use `Room.inMemoryDatabaseBuilder()` for tests (faster, no side effects)

**Performance Optimizations:**
- Index frequently queried columns (status, queuePosition)
- Use composite indexes for multi-column queries
- Avoid N+1 query problems with @Relation if needed
- Consider @RawQuery for dynamic filtering (not needed for this story)

**Security Considerations:**
- Room databases stored in private app storage (protected by Android sandboxing)
- No sensitive data in queue table (just installation metadata)
- No encryption needed for this use case

### Project Context Reference

**CLAUDE.md Key Points:**
- **Architecture:** MVVM with Kotlin + Jetpack Compose
- **State Management:** StateFlow for state, MutableSharedFlow for events
- **Data Flow:** UI → ViewModel → Repository → Room/Network
- **Queue Pattern:** Single coroutine job processes queue sequentially
- **Cancellation Safety:** All long-running ops use `currentCoroutineContext().ensureActive()`

**Critical Implementation Rules:**
1. Never use `runBlocking` in production code
2. All database operations are suspend functions
3. Use `withContext(Dispatchers.IO)` for blocking I/O
4. StateFlow updates must be atomic
5. Prefer reactive Flow over one-time queries

**Storage Locations (for reference):**
- App internal storage: `context.filesDir`
- Cache directory: `context.cacheDir`
- Downloads: `/sdcard/Download/RookieOnQuest/`
- Database location: Managed by Room (private app storage)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.5 (claude-sonnet-4-5-20250929)

### Debug Log References

### Completion Notes List

### File List

