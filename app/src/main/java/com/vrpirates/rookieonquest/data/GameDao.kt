package com.vrpirates.rookieonquest.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY gameName ASC")
    fun getAllGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games")
    suspend fun getAllGamesList(): List<GameEntity>

    @Query("SELECT * FROM games WHERE gameName LIKE :query OR packageName LIKE :query ORDER BY gameName ASC")
    fun searchGames(query: String): Flow<List<GameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<GameEntity>)

    @Query("UPDATE games SET sizeBytes = :size WHERE releaseName = :releaseName")
    suspend fun updateSize(releaseName: String, size: Long)

    @Query("UPDATE games SET description = :description, screenshotUrlsJson = :screenshots WHERE releaseName = :releaseName")
    suspend fun updateMetadata(releaseName: String, description: String?, screenshots: String?)

    @Query("UPDATE games SET isFavorite = :isFavorite WHERE releaseName = :releaseName")
    suspend fun updateFavorite(releaseName: String, isFavorite: Boolean)

    @Query("DELETE FROM games")
    suspend fun clearAll()
    
    @Query("SELECT COUNT(*) FROM games")
    suspend fun getCount(): Int
}
