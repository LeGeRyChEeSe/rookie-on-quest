using System.Collections.Generic;
using RookieOnQuest.Data;

namespace RookieOnQuest.Logic
{
    public static class CatalogParser
    {
        public static List<GameData> Parse(string rawContent)
        {
            List<GameData> results = new List<GameData>();
            
            if (string.IsNullOrEmpty(rawContent)) {
                UnityEngine.Debug.LogWarning("CatalogParser: rawContent is empty!");
                return results;
            }

            string[] lines = rawContent.Split(new[] { '\r', '\n' }, System.StringSplitOptions.RemoveEmptyEntries);
            UnityEngine.Debug.Log($"CatalogParser: Found {lines.Length} total lines.");

            int startIdx = 0;
            if (lines.Length > 0 && (lines[0].Contains("Game Name") || lines[0].Contains(";")))
            {
                // Simple heuristic to skip header if it looks like column names
                if (lines[0].Contains("Game Name")) startIdx = 1;
            }

            for (int i = startIdx; i < lines.Length; i++)
            {
                string line = lines[i].Trim();
                string[] parts = line.Split(';');
                if (parts.Length >= 4)
                {
                    GameData data = new GameData();
                    data.GameName = parts[0].Trim();
                    data.ReleaseName = parts[1].Trim();
                    data.PackageName = parts[2].Trim();
                    data.VersionCode = parts[3].Trim();
                    results.Add(data);
                }
            }

            UnityEngine.Debug.Log($"CatalogParser: Successfully parsed {results.Count} games.");
            return results;
        }
    }
}