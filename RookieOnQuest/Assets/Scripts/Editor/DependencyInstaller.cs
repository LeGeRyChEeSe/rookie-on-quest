using UnityEngine;
using UnityEditor;
using UnityEngine.Networking;
using System.IO;
using System.IO.Compression;
using System.Collections;

namespace RookieOnQuest.Editor
{
    public class DependencyInstaller
    {
        private const string NuGetUrl = "https://www.nuget.org/api/v2/package/SharpCompress/0.32.2";
        private const string PluginFolder = "Assets/Plugins";
        private const string TempZip = "TempSharpCompress.zip";

        public static void InstallSharpCompress()
        {
            // We use a coroutine-like approach in Editor via EditorApplication.update
            Debug.Log("Starting SharpCompress installation...");
            DownloadDLL();
        }

        private static void DownloadDLL()
        {
            var www = UnityWebRequest.Get(NuGetUrl);
            www.SendWebRequest();

            EditorApplication.CallbackFunction checkProgress = null;
            checkProgress = () =>
            {
                if (!www.isDone) return;

                EditorApplication.update -= checkProgress;

                if (www.result == UnityWebRequest.Result.Success)
                {
                    byte[] results = www.downloadHandler.data;
                    ProcessPackage(results);
                }
                else
                {
                    Debug.LogError("Failed to download SharpCompress: " + www.error);
                }
                www.Dispose();
            };

            EditorApplication.update += checkProgress;
        }

        private static void ProcessPackage(byte[] data)
        {
            try
            {
                if (!Directory.Exists(PluginFolder)) Directory.CreateDirectory(PluginFolder);

                string tempPath = Path.Combine(Application.dataPath, "..", TempZip);
                File.WriteAllBytes(tempPath, data);

                using (ZipArchive archive = ZipFile.OpenRead(tempPath))
                {
                    foreach (ZipArchiveEntry entry in archive.Entries)
                    {
                        // We want the netstandard2.0 version for best compatibility with Unity
                        if (entry.FullName == "lib/netstandard2.0/SharpCompress.dll")
                        {
                            string targetPath = Path.Combine(PluginFolder, "SharpCompress.dll");
                            entry.ExtractToFile(targetPath, true);
                            Debug.Log("Successfully installed SharpCompress.dll to Assets/Plugins");
                        }
                    }
                }

                File.Delete(tempPath);
                AssetDatabase.Refresh();
            }
            catch (System.Exception e)
            {
                Debug.LogError("Error processing NuGet package: " + e.Message);
            }
        }
    }
}
