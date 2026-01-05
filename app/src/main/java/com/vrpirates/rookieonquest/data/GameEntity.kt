package com.vrpirates.rookieonquest.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey
    val releaseName: String,
    val gameName: String,
    val packageName: String,
    val versionCode: String,
    val sizeBytes: Long? = null,
    val description: String? = null,
    val screenshotUrlsJson: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)

fun GameEntity.toData() = GameData(
    gameName = gameName,
    packageName = packageName,
    versionCode = versionCode,
    releaseName = releaseName,
    sizeBytes = sizeBytes,
    description = description,
    screenshotUrls = screenshotUrlsJson?.split("|")?.filter { it.isNotEmpty() },
    isFavorite = isFavorite
)

fun GameData.toEntity() = GameEntity(
    releaseName = releaseName,
    gameName = gameName,
    packageName = packageName,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    description = description,
    screenshotUrlsJson = screenshotUrls?.joinToString("|"),
    isFavorite = isFavorite
)
