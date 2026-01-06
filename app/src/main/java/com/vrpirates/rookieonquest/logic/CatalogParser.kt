package com.vrpirates.rookieonquest.logic

import com.vrpirates.rookieonquest.data.GameData

object CatalogParser {
    fun parse(content: String): List<GameData> {
        val games = mutableMapOf<String, GameData>()
        if (content.isBlank()) return emptyList()

        val lines = content.split("\r\n", "\n", "\r")
        
        // Skip header if present
        val startIdx = if (lines.isNotEmpty() && lines[0].contains("Game Name")) 1 else 0

        for (i in startIdx until lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            
            val parts = line.split(";")
            // Standard VRP Catalog format:
            // 0: Game Name
            // 1: Release Name
            // 2: Package Name
            // 3: Version Code
            // Optional/Extended fields:
            // 4: Size (Long)
            // 5: Popularity (Int)
            
            if (parts.size >= 4) {
                val releaseName = parts[1].trim()
                if (!games.containsKey(releaseName)) {
                    val sizeBytes = parts.getOrNull(4)?.trim()?.toLongOrNull()
                    val popularity = parts.getOrNull(5)?.trim()?.toIntOrNull() ?: 0
                    
                    games[releaseName] = GameData(
                        gameName = parts[0].trim(),
                        releaseName = releaseName,
                        packageName = parts[2].trim(),
                        versionCode = parts[3].trim(),
                        sizeBytes = sizeBytes,
                        popularity = popularity
                    )
                }
            }
        }
        return games.values.toList()
    }
}
