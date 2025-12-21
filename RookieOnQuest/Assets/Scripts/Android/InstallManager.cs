using UnityEngine;
using System.IO;

namespace RookieOnQuest.Android
{
    public class InstallManager : MonoBehaviour
    {
        public static InstallManager Instance;
        private const string AUTHORITY = "com.vrpirates.rookieonquest.fileprovider";

        private void Awake()
        {
            Instance = this;
        }

        public void InstallAPK(string filePath)
        {
            if (Application.platform != RuntimePlatform.Android)
            {
                Debug.LogWarning("InstallAPK called on non-Android platform. Mock success.");
                return;
            }

            if (!File.Exists(filePath))
            {
                Debug.LogError($"APK file not found at: {filePath}");
                return;
            }

            try
            {
                // 1. Get Context
                AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                AndroidJavaObject currentActivity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
                AndroidJavaObject context = currentActivity.Call<AndroidJavaObject>("getApplicationContext");

                // 2. Create File object
                AndroidJavaObject fileObj = new AndroidJavaObject("java.io.File", filePath);

                // 3. Get URI via FileProvider
                AndroidJavaClass fileProvider = new AndroidJavaClass("androidx.core.content.FileProvider");
                AndroidJavaObject uriObj = fileProvider.CallStatic<AndroidJavaObject>("getUriForFile", context, AUTHORITY, fileObj);

                // 4. Create Intent
                AndroidJavaObject intent = new AndroidJavaObject("android.content.Intent", "android.intent.action.VIEW");
                intent.Call<AndroidJavaObject>("setDataAndType", uriObj, "application/vnd.android.package-archive");
                intent.Call<AndroidJavaObject>("addFlags", 1); // FLAG_GRANT_READ_URI_PERMISSION
                intent.Call<AndroidJavaObject>("addFlags", 268435456); // FLAG_ACTIVITY_NEW_TASK

                // 5. Start Activity
                currentActivity.Call("startActivity", intent);
                Debug.Log("Installation Intent launched.");
            }
            catch (System.Exception e)
            {
                Debug.LogError($"Failed to launch installer: {e.Message}\n{e.StackTrace}");
            }
        }
    }
}
