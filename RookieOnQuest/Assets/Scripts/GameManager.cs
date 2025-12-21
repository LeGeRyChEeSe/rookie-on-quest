using UnityEngine;
using UnityEngine.Networking;
using RookieOnQuest.UI;
using RookieOnQuest.Logic;
using RookieOnQuest.Android;
using RookieOnQuest.Data;
using System.Collections.Generic;
using System.Collections;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System;
using System.Threading.Tasks;
using System.Linq;
using SharpCompress.Archives;
using SharpCompress.Archives.SevenZip;
using SharpCompress.Common;

namespace RookieOnQuest
{
    [System.Serializable]
    public class PublicConfig
    {
        public string baseUri;
        public string password;
    }

    public class GameManager : MonoBehaviour
    {
        public static GameManager Instance;
        
        private const string ConfigUrl = "https://vrpirates.wiki/downloads/vrp-public.json";
        
        private string _baseMirrorUrl;
        private string _mirrorPassword; 
        private List<GameData> _cachedGames;
        private string _metaDownloadPath;
        private string _metaExtractPath;

        private void Awake()
        {
            Instance = this;
            _metaDownloadPath = Path.Combine(Application.persistentDataPath, "meta.7z");
            _metaExtractPath = Path.Combine(Application.persistentDataPath, "meta_extracted");
            
            var dispatcher = UnityMainThreadDispatcher.Instance();
        }

        private bool _isAppQuitting = false;
        private void OnApplicationQuit()
        {
            _isAppQuitting = true;
            Debug.Log("Application quitting, stopping all background tasks.");
        }

        private void Start()
        {
            StartCoroutine(InitializeRoutine());
        }

        private IEnumerator InitializeRoutine()
        {
            UIManager.Instance.ShowProgress("Rookie On Quest\nChecking for updates...", 0f);
            
            #if UNITY_ANDROID && !UNITY_EDITOR
            yield return StartCoroutine(RequestPermissions());
            #endif

            yield return StartCoroutine(FetchPublicConfig());

            if (string.IsNullOrEmpty(_baseMirrorUrl))
            {
                UIManager.Instance.ShowProgress("Mirror Connection Failed!", 0f);
                yield break;
            }

            string metaUrl = _baseMirrorUrl + "meta.7z";
            string remoteDate = "";
            
            using (UnityWebRequest headReq = UnityWebRequest.Head(metaUrl))
            {
                headReq.SetRequestHeader("User-Agent", "rclone/v1.72.1");
                yield return headReq.SendWebRequest();
                if (headReq.result == UnityWebRequest.Result.Success)
                {
                    remoteDate = headReq.GetResponseHeader("Last-Modified");
                }
            }

            string localDate = PlayerPrefs.GetString("LastMetaDate", "");
            string catalogPath = Path.Combine(_metaExtractPath, "VRP-GameList.txt");
            bool needsUpdate = string.IsNullOrEmpty(localDate) || localDate != remoteDate || !File.Exists(catalogPath);

            if (needsUpdate)
            {
                UIManager.Instance.ShowProgress("Downloading Game Database...\n(This happens once per update)", 0.1f);
                yield return StartCoroutine(DownloadMetadata());

                if (File.Exists(_metaDownloadPath))
                {
                    UIManager.Instance.ShowProgress("Unpacking Game List...\n(Building entry database)", 0.99f);
                    var priorityTask = Task.Run(() => Logic.ArchiveManager.ExtractPriorityFile(_metaDownloadPath, _metaExtractPath, _mirrorPassword, "VRP-GameList.txt"));
                    while (!priorityTask.IsCompleted) yield return null;

                    if (priorityTask.Result)
                    {
                        PlayerPrefs.SetString("LastMetaDate", remoteDate);
                        PlayerPrefs.Save();
                    }
                }
            }
            else {
                Debug.Log("Metadata is up to date and archive is present.");
            }
            
            // Always ensure icons are being extracted/verified in the background
            Debug.Log("Starting ExtractIconsRoutine coroutine...");
            StartCoroutine(ExtractIconsRoutine());
            
            // Load from existing or new file
            if (File.Exists(catalogPath))
            {
                UIManager.Instance.ShowProgress("Loading Catalog...\n(Preparing 2400+ games)", 0.99f);
                LoadCatalogFromFile(catalogPath);
            }
            else
            {
                UIManager.Instance.ShowProgress("Database Error!", 0f);
                yield break;
            }

            UIManager.Instance.HideProgress();
        }

        private bool _isExtractionRunning = false;
        private bool _isInstalling = false;

        private IEnumerator ExtractIconsRoutine()
        {
            while (true)
            {
                // Pause extraction if we are currently installing a game
                if (_isInstalling)
                {
                    Debug.Log("[Background] Extraction paused for installation priority...");
                    while (_isInstalling) yield return new WaitForSeconds(2.0f);
                    Debug.Log("[Background] Resuming extraction...");
                }

                if (UIManager.Instance.IsIconLoadingEnabled && !_isExtractionRunning)
                {
                    Debug.Log("[Background] Starting extraction...");
                    _isExtractionRunning = true;
                    
                    var task = Task.Run(() => {
                        try {
                            Logic.ArchiveManager.ExtractEverythingElse(
                                _metaDownloadPath, 
                                _metaExtractPath, 
                                _mirrorPassword, 
                                "VRP-GameList.txt",
                                () => UIManager.Instance.IsIconLoadingEnabled && !_isAppQuitting
                            );
                        } finally {
                            _isExtractionRunning = false;
                        }
                    });
                }

                // Periodically update the icon index and UI status
                UIManager.Instance.UpdateIconList();
                
                yield return new WaitForSeconds(5.0f);
            }
        }

        private void LoadCatalogFromFile(string fullPath)
        {
            if (!File.Exists(fullPath))
            {
                string[] files = Directory.GetFiles(_metaExtractPath, "VRP-GameList.txt", SearchOption.AllDirectories);
                if (files.Length > 0) fullPath = files[0];
            }

            if (File.Exists(fullPath))
            {
                Debug.Log($"Loading catalog from: {fullPath}");
                string content = "";
                int retries = 10;
                while (retries > 0)
                {
                    try
                    {
                        using (var fs = new FileStream(fullPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                        using (var sr = new StreamReader(fs)) { content = sr.ReadToEnd(); }
                        break;
                    }
                    catch (IOException)
                    {
                        retries--;
                        if (retries <= 0) return;
                        System.Threading.Thread.Sleep(500);
                    }
                }

                Debug.Log($"Catalog file size: {content.Length} characters.");
                _cachedGames = CatalogParser.Parse(content);
                
                if (_cachedGames == null || _cachedGames.Count == 0)
                {
                    Debug.LogWarning("CatalogParser returned 0 games. Check file format.");
                }
                else
                {
                    Debug.Log($"SUCCESS: Loaded {_cachedGames.Count} games.");
                    UIManager.Instance.PopulateList(_cachedGames, OnInstallRequested);
                }
            }
            else
            {
                Debug.LogError($"Catalog file NOT FOUND at: {fullPath}");
            }
        }

        private IEnumerator FetchPublicConfig()
        {
            Debug.Log($"Fetching config from: {ConfigUrl}");
            using (UnityWebRequest uwr = UnityWebRequest.Get(ConfigUrl))
            {
                yield return uwr.SendWebRequest();
                if (uwr.result == UnityWebRequest.Result.Success)
                {
                    Debug.Log("Config downloaded successfully.");
                    PublicConfig config = JsonUtility.FromJson<PublicConfig>(uwr.downloadHandler.text);
                    _baseMirrorUrl = config.baseUri;
                    
                    try {
                        byte[] passBytes = Convert.FromBase64String(config.password);
                        _mirrorPassword = Encoding.UTF8.GetString(passBytes);
                        Debug.Log($"Password loaded (Length: {_mirrorPassword.Length})");
                    } catch (Exception e) {
                        Debug.LogError("Password decoding failed: " + e.Message);
                    }

                    if (!_baseMirrorUrl.EndsWith("/")) _baseMirrorUrl += "/";
                    Debug.Log($"Mirror URL: {_baseMirrorUrl}");
                }
                else
                {
                    Debug.LogError($"Config download failed: {uwr.error}");
                }
            }
        }

        private IEnumerator DownloadMetadata()
        {
            string metaUrl = _baseMirrorUrl + "meta.7z";
            if (File.Exists(_metaDownloadPath)) File.Delete(_metaDownloadPath);

            using (UnityWebRequest uwr = new UnityWebRequest(metaUrl, UnityWebRequest.kHttpVerbGET))
            {
                uwr.downloadHandler = new DownloadHandlerFile(_metaDownloadPath) { removeFileOnAbort = true };
                uwr.SetRequestHeader("User-Agent", "rclone/v1.72.1");
                yield return uwr.SendWebRequest();
            }
        }

        public void OnInstallRequested(string packageName)
        {
            if (string.IsNullOrEmpty(_baseMirrorUrl)) return;
            var gameData = _cachedGames.Find(g => g.PackageName == packageName);
            if (gameData == null) return;

            string hash = CalculateMD5(gameData.ReleaseName + "\n");
            string dirUrl = $"{_baseMirrorUrl}{hash}/";
            
            Debug.Log($"Requesting install for {gameData.GameName}...");
            StartCoroutine(ProcessMultiPartInstall(dirUrl, gameData.GameName, packageName));
        }

        private IEnumerator ProcessMultiPartInstall(string dirUrl, string gameName, string packageName)
        {
            _isInstalling = true;
            UIManager.Instance.ShowProgress("Connecting to mirror...", 0f);

            using (UnityWebRequest uwr = UnityWebRequest.Get(dirUrl))
            {
                uwr.SetRequestHeader("User-Agent", "rclone/v1.72.1");
                yield return uwr.SendWebRequest();

                if (uwr.result != UnityWebRequest.Result.Success)
                {
                    Debug.LogError($"Mirror error: {uwr.error}");
                    UIManager.Instance.HideProgress();
                    _isInstalling = false;
                    yield break;
                }

                var matches = Regex.Matches(uwr.downloadHandler.text, @"href\s*=\s*""([^""]+\.(7z\.\d{3}|apk))""", RegexOptions.IgnoreCase);
                List<string> segments = matches.Cast<Match>().Select(m => m.Groups[1].Value).Distinct().OrderBy(s => s).ToList();

                if (segments.Count == 0)
                {
                    Debug.LogError("No installable files found.");
                    UIManager.Instance.HideProgress();
                    _isInstalling = false;
                    yield break;
                }

                string tempFolder = Path.Combine(Application.persistentDataPath, "temp_install");
                if (Directory.Exists(tempFolder)) Directory.Delete(tempFolder, true);
                Directory.CreateDirectory(tempFolder);

                List<string> localPaths = new List<string>();
                for (int i = 0; i < segments.Count; i++)
                {
                    string seg = segments[i];
                    string localPath = Path.Combine(tempFolder, seg);
                    localPaths.Add(localPath);

                    Debug.Log($"Starting download of part {i + 1}/{segments.Count}: {seg}");
                    bool done = false;
                    string err = null;
                    NetworkManager.Instance.DownloadFile(dirUrl + seg, localPath,
                        (p) => UIManager.Instance.ShowProgress($"Downloading part {i + 1}/{segments.Count}...\nYou can take off your headset, a sound will play when finished.", ((float)i + p) / segments.Count),
                        (s, e) => { done = true; if (!s) err = e; });

                    while (!done) yield return null;
                    if (err != null) { 
                        Debug.LogError($"Failed at part {i+1}: {err}");
                        UIManager.Instance.HideProgress(); 
                        yield break; 
                    } 
                    
                    Debug.Log($"Finished part {i + 1}/{segments.Count}");
                    System.GC.Collect(); // Free memory between parts
                }

                UIManager.Instance.ShowProgress("Extracting files...", 0.99f);
                string extractionDir = null;

                var extractTask = Task.Run(() => {
                    try {
                        string outDir = Path.Combine(tempFolder, "extracted");
                        if (Directory.Exists(outDir)) Directory.Delete(outDir, true);
                        Directory.CreateDirectory(outDir);

                        // If it's a direct APK download, just copy it to the extraction dir
                        if (localPaths.Count == 1 && localPaths[0].EndsWith(".apk")) {
                            File.Copy(localPaths[0], Path.Combine(outDir, Path.GetFileName(localPaths[0])));
                            return outDir;
                        }

                        // Merge 7z parts
                        string mergedPath = Path.Combine(tempFolder, "combined.7z");
                        using (var outStream = File.Create(mergedPath))
                        {
                            foreach (var part in localPaths.OrderBy(p => p))
                            {
                                using (var inStream = File.OpenRead(part)) { inStream.CopyTo(outStream); }
                            }
                        }

                        // Extract APK and OBBs
                        using (var archive = SevenZipArchive.Open(mergedPath, new SharpCompress.Readers.ReaderOptions { Password = _mirrorPassword }))
                        {
                            long totalSize = archive.Entries.Where(e => !e.IsDirectory && (e.Key.ToLower().EndsWith(".apk") || e.Key.ToLower().EndsWith(".obb"))).Sum(e => e.Size);
                            long totalExtracted = 0;

                            foreach (var entry in archive.Entries)
                            {
                                if (entry.IsDirectory) continue;
                                
                                string key = entry.Key.ToLower();
                                if (key.EndsWith(".apk") || key.EndsWith(".obb"))
                                {
                                    string fileName = Path.GetFileName(entry.Key);
                                    long entrySize = entry.Size;
                                    long entryExtracted = 0;

                                    using (var entryStream = entry.OpenEntryStream())
                                    using (var outFs = File.Create(Path.Combine(outDir, fileName)))
                                    {
                                        byte[] buffer = new byte[81920];
                                        int bytesRead;
                                        while ((bytesRead = entryStream.Read(buffer, 0, buffer.Length)) > 0)
                                        {
                                            outFs.Write(buffer, 0, bytesRead);
                                            entryExtracted += bytesRead;
                                            totalExtracted += bytesRead;

                                            float overallProgress = (float)totalExtracted / totalSize;
                                            UnityMainThreadDispatcher.Instance().Enqueue(() => {
                                                UIManager.Instance.ShowProgress($"Extracting {fileName}...", overallProgress);
                                            });
                                        }
                                    }
                                }
                            }
                        }
                        return outDir;
                    } catch (Exception ex) { Debug.LogError($"Extraction error: {ex.Message}"); }
                    return null;
                });

                while (!extractTask.IsCompleted) yield return null;
                extractionDir = extractTask.Result;

                if (!string.IsNullOrEmpty(extractionDir) && Directory.Exists(extractionDir))
                {
                    string[] apks = Directory.GetFiles(extractionDir, "*.apk");
                    if (apks.Length > 0)
                    {
                        string finalApk = apks[0];
                        try {
                            // 1. Move OBB files FIRST
                            UIManager.Instance.ShowProgress("Installing OBB files...", 0.5f);
                            MoveObbFiles(extractionDir, packageName);

                            // 2. Prepare and install APK
                            string sanitizedName = Regex.Replace(gameName, @"[^a-zA-Z0-9_\-\.]", "_");
                            string safePath = Path.Combine(Application.persistentDataPath, sanitizedName + ".apk");
                            
                            if (File.Exists(safePath)) { 
                                Debug.Log($"Old APK found for {gameName}, deleting...");
                                File.Delete(safePath);
                            }
                            
                            File.Move(finalApk, safePath);

                            long size = new FileInfo(safePath).Length;
                            Debug.Log($"SUCCESS: APK ready for {packageName} ({size} bytes). Launching install...");
                            
                            UIManager.Instance.ShowProgress("Launching Installer...", 0.9f);
                            UIManager.Instance.PlayNotificationSound();
                            InstallManager.Instance.InstallAPK(safePath);

                            // Hide progress immediately as the Android Installer takes over the screen
                            UIManager.Instance.HideProgress();
                            _isInstalling = false;

                            // Cleanup segments, merged archive AND the final APK
                            Task.Run(() => {
                                try {
                                    // Small delay to ensure the Android Package Installer has opened the file
                                    System.Threading.Thread.Sleep(5000);
                                    
                                    if (File.Exists(safePath)) {
                                        File.Delete(safePath);
                                        Debug.Log($"Cleaned up final APK: {safePath}");
                                    }

                                    if (Directory.Exists(tempFolder)) {
                                        Directory.Delete(tempFolder, true);
                                        Debug.Log("Temporary download folder cleaned up.");
                                    }
                                } catch (Exception ex) { Debug.LogWarning("Cleanup error: " + ex.Message); }
                            });
                        }
                        catch (Exception ex) {
                            Debug.LogError($"File operation error: {ex.Message}");
                            UIManager.Instance.HideProgress();
                            _isInstalling = false;
                            InstallManager.Instance.InstallAPK(finalApk);
                        }
                    }
                    else
                    {
                        Debug.LogError("No APK found in the extracted files.");
                        UIManager.Instance.HideProgress();
                        _isInstalling = false;
                    }
                }
                else
                {
                    Debug.LogError("Failed to extract files.");
                    UIManager.Instance.HideProgress();
                    _isInstalling = false;
                }
            }
        }

        public void RefreshCatalog()
        {
            StopAllCoroutines();
            StartCoroutine(InitializeRoutine());
        }

        private IEnumerator RequestPermissions()
        {
            if (Application.platform != RuntimePlatform.Android) yield break;

            bool needsPermission = false;
            try
            {
                using (var version = new AndroidJavaClass("android.os.Build$VERSION"))
                {
                    int sdkInt = version.GetStatic<int>("SDK_INT");
                    if (sdkInt >= 30) // Android 11 (API 30)
                    {
                        using (var environment = new AndroidJavaClass("android.os.Environment"))
                        {
                            if (!environment.CallStatic<bool>("isExternalStorageManager"))
                            {
                                needsPermission = true;
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.LogError($"Error checking permissions: {ex.Message}");
            }

            if (needsPermission)
            {
                Debug.Log("MANAGE_EXTERNAL_STORAGE not granted. Requesting...");
                UIManager.Instance.ShowProgress("Permission Required\nPlease grant 'All Files Access' to install OBBs.", 0f);

                try
                {
                    using (var uriClass = new AndroidJavaClass("android.net.Uri"))
                    using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                    using (var currentActivity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
                    {
                        string packageName = currentActivity.Call<string>("getPackageName");
                        AndroidJavaObject uri = uriClass.CallStatic<AndroidJavaObject>("fromParts", "package", packageName, null);

                        using (var intent = new AndroidJavaObject("android.content.Intent", "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", uri))
                        {
                            currentActivity.Call("startActivity", intent);
                        }
                    }
                }
                catch (Exception ex)
                {
                    Debug.LogError($"Error launching permission settings: {ex.Message}");
                }

                // Wait for user to return or timeout
                float timeout = 60f;
                bool granted = false;
                while (!granted && timeout > 0)
                {
                    yield return new WaitForSeconds(1.5f);
                    timeout -= 1.5f;

                    try
                    {
                        using (var environment = new AndroidJavaClass("android.os.Environment"))
                        {
                            granted = environment.CallStatic<bool>("isExternalStorageManager");
                        }
                    }
                    catch { /* Ignore errors during polling */ }
                }

                UIManager.Instance.HideProgress();
            }
        }

        private void MoveObbFiles(string sourceDir, string packageName)
        {
            try
            {
                // In a standalone Quest app, we usually target the shared storage
                string obbBaseRoot = "/storage/emulated/0/Android/obb";
                
                // Ensure the base OBB directory exists (it should, but just in case)
                if (!Directory.Exists(obbBaseRoot))
                {
                    Debug.LogWarning("Standard OBB path not found, trying fallback...");
                    // Try to find it relative to persistentDataPath as a fallback
                    // persistentDataPath is usually /storage/emulated/0/Android/data/pkg/files
                    obbBaseRoot = Path.GetFullPath(Path.Combine(Application.persistentDataPath, "../../../obb"));
                }

                string targetDir = Path.Combine(obbBaseRoot, packageName);
                if (!Directory.Exists(targetDir))
                {
                    Debug.Log($"Creating OBB directory: {targetDir}");
                    Directory.CreateDirectory(targetDir);
                }

                string[] obbFiles = Directory.GetFiles(sourceDir, "*.obb", SearchOption.AllDirectories);
                if (obbFiles.Length == 0)
                {
                    Debug.Log("No OBB files found to move.");
                    return;
                }

                foreach (var obb in obbFiles)
                {
                    string fileName = Path.GetFileName(obb);
                    string destPath = Path.Combine(targetDir, fileName);
                    
                    if (File.Exists(destPath))
                    {
                        Debug.Log($"Deleting existing OBB: {fileName}");
                        File.Delete(destPath);
                    }

                    Debug.Log($"Moving OBB to: {destPath}");
                    File.Move(obb, destPath);
                }
            }
            catch (Exception ex)
            {
                Debug.LogError($"Error moving OBB: {ex.Message}");
            }
        }

        private string CalculateMD5(string input)
        {
            using (MD5 md5 = MD5.Create())
            {
                byte[] hash = md5.ComputeHash(Encoding.UTF8.GetBytes(input));
                StringBuilder sb = new StringBuilder();
                foreach (byte b in hash) sb.Append(b.ToString("x2"));
                return sb.ToString();
            }
        }
    }
}
