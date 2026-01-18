package com.vrpirates.rookieonquest.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [GameEntity::class, QueuedInstallEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun queuedInstallDao(): QueuedInstallDao

    companion object {
        private const val TAG = "AppDatabase"
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    Log.i(TAG, "Starting migration 2 -> 3: Adding install_queue table")

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

                    // Create indexes for performance (removing redundant status-only index)
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_install_queue_queuePosition ON install_queue(queuePosition)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_install_queue_status_queuePosition ON install_queue(status, queuePosition)")

                    Log.i(TAG, "Migration 2 -> 3 completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Migration 2 -> 3 FAILED - User data (favorites) may be lost on fallback", e)
                    throw e // Re-throw to trigger fallback
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    Log.i(TAG, "Starting migration 3 -> 4: Adding isDownloadOnly column to install_queue")

                    // Add isDownloadOnly column with default value false (0)
                    database.execSQL("ALTER TABLE install_queue ADD COLUMN isDownloadOnly INTEGER NOT NULL DEFAULT 0")

                    Log.i(TAG, "Migration 3 -> 4 completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Migration 3 -> 4 FAILED - Queue data may be lost on fallback", e)
                    throw e // Re-throw to trigger fallback
                }
            }
        }

        /**
         * Direct migration from v2 to v4 for multi-version jumps.
         * Creates the complete install_queue table schema in one step,
         * avoiding unnecessary ALTER TABLE operations.
         */
        private val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    Log.i(TAG, "Starting migration 2 -> 4: Adding install_queue table with complete schema")

                    // Create install_queue table with ALL columns including isDownloadOnly
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS install_queue (
                            releaseName TEXT PRIMARY KEY NOT NULL,
                            status TEXT NOT NULL,
                            progress REAL NOT NULL,
                            downloadedBytes INTEGER,
                            totalBytes INTEGER,
                            queuePosition INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL,
                            lastUpdatedAt INTEGER NOT NULL,
                            isDownloadOnly INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())

                    // Create indexes for performance
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_install_queue_queuePosition ON install_queue(queuePosition)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_install_queue_status_queuePosition ON install_queue(status, queuePosition)")

                    Log.i(TAG, "Migration 2 -> 4 completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Migration 2 -> 4 FAILED - User data (favorites) may be lost on fallback", e)
                    throw e // Re-throw to trigger fallback
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rookie_database"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_2_4)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
