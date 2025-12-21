using System;

namespace RookieOnQuest.Data
{
    [Serializable]
    public class GameData
    {
        public string GameName;
        public string ReleaseName;
        public string PackageName;
        public string VersionCode;
        public string VersionName; // Often derived or same as VersionCode in some lists
        public string SizeMB;
        
        // Parsed from VRP-GameList.txt columns:
        // Game Name;Release Name;Package Name;Version Code;Last Updated;Size (MB);Downloads;Rating;Rating Count
    }
}
