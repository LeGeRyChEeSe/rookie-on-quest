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

                UIManager.Instance.ShowProgress("Extracting APK...", 0.99f);
                string finalApk = null;

                var extractTask = Task.Run(() => {
                    try {
                        if (localPaths.Count == 1 && localPaths[0].EndsWith(".apk")) return localPaths[0];

                        string mergedPath = Path.Combine(tempFolder, "combined.7z");
                        using (var outStream = File.Create(mergedPath))
                        {
                            foreach (var part in localPaths.OrderBy(p => p))
                            {
                                using (var inStream = File.OpenRead(part)) { inStream.CopyTo(outStream); }
                            }
                        }

                        string outDir = Path.Combine(tempFolder, "extracted");
                        if (Directory.Exists(outDir)) Directory.Delete(outDir, true);
                        Directory.CreateDirectory(outDir);

                        using (var archive = SevenZipArchive.Open(mergedPath, new SharpCompress.Readers.ReaderOptions { Password = _mirrorPassword }))
                        {
                            var entry = archive.Entries.FirstOrDefault(e => e.Key.EndsWith(".apk", StringComparison.OrdinalIgnoreCase));
                            if (entry != null)
                            {
                                string outPath = Path.Combine(outDir, Path.GetFileName(entry.Key));
                                long totalSize = entry.Size;
                                long extractedSize = 0;

                                using (var entryStream = entry.OpenEntryStream())
                                using (var outFs = File.Create(outPath))
                                {
                                    byte[] buffer = new byte[81920]; 
                                    int bytesRead;
                                    float lastUIUpdateProgress = -1f;

                                    while ((bytesRead = entryStream.Read(buffer, 0, buffer.Length)) > 0)
                                    {
                                        outFs.Write(buffer, 0, bytesRead);
                                        extractedSize += bytesRead;
                                        
                                        float currentProgress = (float)extractedSize / totalSize;
                                        // Only update UI if progress increased by at least 1%
                                        if (currentProgress - lastUIUpdateProgress >= 0.01f) 
                                        {
                                            lastUIUpdateProgress = currentProgress;
                                            UnityMainThreadDispatcher.Instance().Enqueue(() => {
                                                UIManager.Instance.ShowProgress($"Extracting APK...", currentProgress);
                                            });
                                        }
                                    }
                                }
                                return outPath;
                            }
                        }
                    } catch (Exception ex) { Debug.LogError($"Extraction error: {ex.Message}"); }
                    return null;
                });

                while (!extractTask.IsCompleted) yield return null;
                finalApk = extractTask.Result;

                UIManager.Instance.HideProgress();
                _isInstalling = false; // Resume background extraction
                
                if (!string.IsNullOrEmpty(finalApk) && File.Exists(finalApk))
                {
                    try {
                        // Use a specific name to avoid locking issues with "install.apk"
                        string safePath = Path.Combine(Application.persistentDataPath, packageName + ".apk");
                        
                        if (File.Exists(safePath)) { 
                            Debug.Log("Old APK found, deleting...");
                            File.Delete(safePath);
                        }
                        
                        File.Move(finalApk, safePath);

                        long size = new FileInfo(safePath).Length;
                        Debug.Log($"SUCCESS: APK ready for {packageName} ({size} bytes). Launching install...");
                        
                        UIManager.Instance.PlayNotificationSound();
                        InstallManager.Instance.InstallAPK(safePath);

                        // Cleanup segments, merged archive AND the final APK
                        Task.Run(() => {
                            try {
                                // Small delay to ensure the Android Package Installer has opened the file
                                System.Threading.Thread.Sleep(2000);
                                
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
                        // Fallback: try to install from extraction path directly if move fails
                        InstallManager.Instance.InstallAPK(finalApk);
                    }
                }
                else
                {
                    Debug.LogError("Failed to extract APK.");
                }
            }
        }

        public void RefreshCatalog()
        {
            StopAllCoroutines();
            StartCoroutine(InitializeRoutine());
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
