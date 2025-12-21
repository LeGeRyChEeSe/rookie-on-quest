using UnityEditor;
using System.Linq;
using UnityEngine;

namespace RookieOnQuest.Editor
{
    public class BuildScript
    {
        public static void Build()
        {
            // Ensure settings are configured
            BuildSetup.ConfigurePlayerSettings();

            string[] scenes = EditorBuildSettings.scenes
                .Where(s => s.enabled)
                .Select(s => s.path)
                .ToArray();

            if (scenes.Length == 0)
            {
                Debug.LogError("No scenes enabled in build settings!");
                return;
            }

            BuildPlayerOptions buildPlayerOptions = new BuildPlayerOptions();
            buildPlayerOptions.scenes = scenes;
            buildPlayerOptions.locationPathName = "Builds/RookieOnQuest.apk";
            buildPlayerOptions.target = BuildTarget.Android;
            buildPlayerOptions.options = BuildOptions.None;

            var report = BuildPipeline.BuildPlayer(buildPlayerOptions);
            var summary = report.summary;

            if (summary.result == UnityEditor.Build.Reporting.BuildResult.Succeeded)
            {
                Debug.Log("Build succeeded: " + summary.totalSize + " bytes");
            }
            else if (summary.result == UnityEditor.Build.Reporting.BuildResult.Failed)
            {
                Debug.LogError("Build failed");
                EditorApplication.Exit(1);
            }
        }

        public static void BuildAndRun()
        {
            Build();
            // In a real scenario, you'd use ADB to install and run, 
            // but for Unity local dev, this menu entry would trigger the standard build & run.
        }
    }
}
