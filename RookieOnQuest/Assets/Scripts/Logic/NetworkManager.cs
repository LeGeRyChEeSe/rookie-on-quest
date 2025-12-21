using UnityEngine;
using UnityEngine.Networking;
using System.Collections;
using System;

namespace RookieOnQuest.Logic
{
    public class NetworkManager : MonoBehaviour
    {
        public static NetworkManager Instance;

        private void Awake()
        {
            Instance = this;
        }

        public void DownloadFile(string url, string savePath, Action<float> onProgress, Action<bool, string> onComplete)
        {
            StartCoroutine(DownloadRoutine(url, savePath, onProgress, onComplete));
        }

        private IEnumerator DownloadRoutine(string url, string savePath, Action<float> onProgress, Action<bool, string> onComplete)
        {
            int maxRetries = 3;
            int currentRetry = 0;
            string lastError = "";

            while (currentRetry < maxRetries)
            {
                using (UnityWebRequest uwr = new UnityWebRequest(url, UnityWebRequest.kHttpVerbGET))
                {
                    uwr.downloadHandler = new DownloadHandlerFile(savePath) { removeFileOnAbort = true };
                    uwr.SetRequestHeader("User-Agent", "rclone/v1.72.1");
                    uwr.timeout = 120; // 2 minutes timeout per part

                    var operation = uwr.SendWebRequest();
                    float lastReportTime = Time.time;

                    while (!operation.isDone)
                    {
                        onProgress?.Invoke(uwr.downloadProgress);
                        
                        // Heartbeat log every 5 seconds to see if it's still alive
                        if (Time.time - lastReportTime > 5f)
                        {
                            Debug.Log($"Download Heartbeat: {uwr.downloadProgress * 100:F1}% (Part URL: {url})");
                            lastReportTime = Time.time;
                        }
                        yield return null;
                    }

                    if (uwr.result == UnityWebRequest.Result.Success)
                    {
                        Debug.Log($"Download Complete: {savePath}");
                        onComplete?.Invoke(true, null);
                        yield break;
                    }
                    else
                    {
                        lastError = uwr.error;
                        currentRetry++;
                        Debug.LogWarning($"Download failed (Attempt {currentRetry}/{maxRetries}): {lastError}");
                        if (currentRetry < maxRetries) yield return new WaitForSeconds(2);
                    }
                }
            }

            Debug.LogError($"Download Final Error after {maxRetries} attempts: {lastError}");
            onComplete?.Invoke(false, lastError);
        }
    }
}
