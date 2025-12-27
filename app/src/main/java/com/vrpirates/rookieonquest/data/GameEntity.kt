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
    val lastUpdated: Long = System.currentTimeMillis()
)

fun GameEntity.toData() = GameData(
    gameName = gameName,
    packageName = packageName,
    versionCode = versionCode,
    releaseName = releaseName,
    sizeBytes = sizeBytes
)

fun GameData.toEntity() = GameEntity(
    releaseName = releaseName,
    gameName = gameName,
    packageName = packageName,
    versionCode = versionCode,
    sizeBytes = sizeBytes
)
