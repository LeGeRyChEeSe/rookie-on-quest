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
            // According to Unity source:
            // parts[0] = GameName
            // parts[1] = ReleaseName
            // parts[2] = PackageName
            // parts[3] = VersionCode
            if (parts.size >= 4) {
                val releaseName = parts[1].trim()
                if (!games.containsKey(releaseName)) {
                    games[releaseName] = GameData(
                        gameName = parts[0].trim(),
                        releaseName = releaseName,
                        packageName = parts[2].trim(),
                        versionCode = parts[3].trim()
                    )
                }
            }
        }
        return games.values.toList()
    }
}
