using UnityEditor;
using UnityEngine;

namespace RookieOnQuest.Editor
{
    public static class PluginConfigurator
    {
        public static void Configure()
        {
            string path = "Assets/Plugins/SharpCompress.dll";
            PluginImporter importer = AssetImporter.GetAtPath(path) as PluginImporter;
            if (importer != null)
            {
                bool changed = false;
                if (importer.GetCompatibleWithAnyPlatform()) { importer.SetCompatibleWithAnyPlatform(false); changed = true; }
                if (!importer.GetCompatibleWithEditor()) { importer.SetCompatibleWithEditor(true); changed = true; }
                if (!importer.GetCompatibleWithPlatform(BuildTarget.Android)) { importer.SetCompatibleWithPlatform(BuildTarget.Android, true); changed = true; }

                if (changed)
                {
                    importer.SaveAndReimport();
                    Debug.Log("SharpCompress.dll configured for Android.");
                }
            }
            else
            {
                Debug.LogError("SharpCompress.dll not found at " + path);
            }
        }
    }
}
