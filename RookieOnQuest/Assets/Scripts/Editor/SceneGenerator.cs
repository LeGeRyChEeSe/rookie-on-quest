using UnityEngine;
using UnityEditor;
using UnityEditor.SceneManagement;
using RookieOnQuest.UI;
using RookieOnQuest.Logic;
using RookieOnQuest.Android;

namespace RookieOnQuest.Editor
{
    public class SceneGenerator : EditorWindow
    {
        public static void GenerateScene()
        {
            // Create a new scene
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            
            // Create Main Camera
            GameObject cameraObj = new GameObject("Main Camera");
            Camera cam = cameraObj.AddComponent<Camera>();
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = new Color(0.1f, 0.1f, 0.1f);
            cam.orthographic = true; // 2D Mode
            cameraObj.tag = "MainCamera";

            // Create AppManager (The Brain)
            GameObject appManager = new GameObject("AppManager");
            appManager.AddComponent<GameManager>();
            appManager.AddComponent<UIManager>();
            appManager.AddComponent<NetworkManager>();
            appManager.AddComponent<InstallManager>();

            // Save Scene
            string scenePath = "Assets/Scenes/MainScene.unity";
            EditorSceneManager.SaveScene(scene, scenePath);
            Debug.Log($"Scene generated at {scenePath}");

            // Add to Build Settings
            AddSceneToBuildSettings(scenePath);
        }

        private static void AddSceneToBuildSettings(string path)
        {
            EditorBuildSettingsScene[] original = EditorBuildSettings.scenes;
            EditorBuildSettingsScene[] newSettings = new EditorBuildSettingsScene[original.Length + 1];
            System.Array.Copy(original, newSettings, original.Length);
            newSettings[newSettings.Length - 1] = new EditorBuildSettingsScene(path, true);
            EditorBuildSettings.scenes = newSettings;
            Debug.Log("Scene added to Build Settings.");
        }
    }
}
