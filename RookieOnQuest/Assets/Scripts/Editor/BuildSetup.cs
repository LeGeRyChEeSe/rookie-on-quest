using UnityEngine;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.SceneManagement;
using System.Linq;
using System.IO;

namespace RookieOnQuest.Editor
{
    [InitializeOnLoad]
    public class BuildSetup
    {
        static BuildSetup()
        {
            ConfigurePlayerSettings();
            // Delay execution to ensure Unity is fully loaded
            EditorApplication.delayCall += () => {
                EnsureMainSceneConfigured();
                EnsureDependenciesInstalled();
            };
        }

        private static void EnsureDependenciesInstalled()
        {
            string dllPath = "Assets/Plugins/SharpCompress.dll";
            if (!File.Exists(Path.Combine(Application.dataPath, "..", dllPath)))
            {
                Debug.Log("[BuildSetup] SharpCompress.dll missing. Starting automatic installation...");
                DependencyInstaller.InstallSharpCompress();
            }
            else
            {
                PluginConfigurator.Configure();
            }
        }

        private static void EnsureMainSceneConfigured()
        {
            string scenePath = "Assets/Scenes/MainScene.unity";
            
            // 1. Generate scene if missing
            if (!File.Exists(Path.Combine(Application.dataPath, "..", scenePath)))
            {
                Debug.Log("[BuildSetup] Main Scene not found. Generating...");
                SceneGenerator.GenerateScene();
            }

            // 2. Ensure it's the first scene in Build Settings
            var scenes = EditorBuildSettings.scenes.ToList();
            if (scenes.Count == 0 || scenes[0].path != scenePath)
            {
                var mainSceneEntry = scenes.FirstOrDefault(s => s.path == scenePath);
                if (mainSceneEntry != null) scenes.Remove(mainSceneEntry);
                
                scenes.Insert(0, new EditorBuildSettingsScene(scenePath, true));
                EditorBuildSettings.scenes = scenes.ToArray();
                Debug.Log("[BuildSetup] Main Scene set as default build scene.");
            }

            // 3. Set as default scene to load when entering Play Mode
            var sceneAsset = AssetDatabase.LoadAssetAtPath<SceneAsset>(scenePath);
            if (sceneAsset != null && EditorSceneManager.playModeStartScene != sceneAsset)
            {
                EditorSceneManager.playModeStartScene = sceneAsset;
            }
        }

        public static void ConfigurePlayerSettings()
        {
            // Ensure Build Target is Android
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
            {
                Debug.Log("[BuildSetup] Switching Build Target to Android...");
                EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
            }

            bool changed = false;
            if (PlayerSettings.companyName != "VRPirates") { PlayerSettings.companyName = "VRPirates"; changed = true; }
            if (PlayerSettings.productName != "Rookie On Quest") { PlayerSettings.productName = "Rookie On Quest"; changed = true; }
            
            var androidTarget = UnityEditor.Build.NamedBuildTarget.Android;
            if (PlayerSettings.GetApplicationIdentifier(androidTarget) != "com.vrpirates.rookieonquest")
            {
                PlayerSettings.SetApplicationIdentifier(androidTarget, "com.vrpirates.rookieonquest");
                changed = true;
            }
            
            // Set App Icon - Simple version
            string iconPath = "Assets/Icons/app_icon.png";
            Texture2D icon = AssetDatabase.LoadAssetAtPath<Texture2D>(iconPath);
            if (icon != null)
            {
                var currentIcons = PlayerSettings.GetIcons(androidTarget, IconKind.Any);
                if (currentIcons == null || currentIcons.Length == 0 || currentIcons[0] != icon)
                {
                    PlayerSettings.SetIcons(NamedBuildTarget.Unknown, new Texture2D[] { icon }, IconKind.Any);
                    PlayerSettings.SetIcons(androidTarget, new Texture2D[] { icon }, IconKind.Any);
                    Debug.Log("Basic Application Icon assigned.");
                    changed = true;
                }
            }
            
            // Quest Settings
            if (PlayerSettings.Android.minSdkVersion != AndroidSdkVersions.AndroidApiLevel29) { PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel29; changed = true; }
            if (PlayerSettings.Android.targetArchitectures != AndroidArchitecture.ARM64) { PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64; changed = true; }
            
            // Scripting
            if (PlayerSettings.GetScriptingBackend(androidTarget) != ScriptingImplementation.IL2CPP)
            {
                PlayerSettings.SetScriptingBackend(androidTarget, ScriptingImplementation.IL2CPP);
                changed = true;
            }

            if (changed) Debug.Log("[BuildSetup] Player Settings updated automatically.");
        }
    }
}