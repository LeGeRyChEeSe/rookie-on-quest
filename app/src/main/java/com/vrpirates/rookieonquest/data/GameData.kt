package com.vrpirates.rookieonquest.data

data class GameData(
    val gameName: String,
    val packageName: String,
    val versionCode: String,
    val releaseName: String,
    val sizeBytes: Long? = null,
    val description: String? = null,
    val screenshotUrls: List<String>? = null
)
