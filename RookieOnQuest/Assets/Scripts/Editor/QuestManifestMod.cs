using System.IO;
using System.Text;
using System.Xml;
using UnityEditor.Android;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;

public class QuestManifestMod : IPostGenerateGradleAndroidProject
{
    public int callbackOrder => 999;

    public void OnPostGenerateGradleAndroidProject(string path)
    {
        string manifestPath = Path.Combine(path, "src/main/AndroidManifest.xml");
        var xmlDoc = new XmlDocument();
        xmlDoc.Load(manifestPath);

        // 1. Add Permissions
        AddPermission(xmlDoc, "android.permission.REQUEST_INSTALL_PACKAGES");
        AddPermission(xmlDoc, "android.permission.READ_EXTERNAL_STORAGE");
        AddPermission(xmlDoc, "android.permission.WRITE_EXTERNAL_STORAGE");

        // 2. Find Main Activity and Add 2D Category
        XmlElement manifest = xmlDoc.SelectSingleNode("manifest") as XmlElement;
            
        XmlElement application = manifest.SelectSingleNode("application") as XmlElement;
        application.SetAttribute("label", "Rookie On Quest", "http://schemas.android.com/apk/res/android");
        
        XmlNode mainActivity = null;
        XmlNodeList activities = application.SelectNodes("activity");
        
        // Find the activity with MAIN and LAUNCHER
        foreach (XmlNode activity in activities)
        {
            var intentFilter = activity.SelectSingleNode("intent-filter");
            if (intentFilter != null)
            {
                var actionMain = intentFilter.SelectSingleNode("action[@android:name='android.intent.action.MAIN']", GetNamespaceManager(xmlDoc));
                var categoryLauncher = intentFilter.SelectSingleNode("category[@android:name='android.intent.category.LAUNCHER']", GetNamespaceManager(xmlDoc));
                
                if (actionMain != null && categoryLauncher != null)
                {
                    mainActivity = activity;
                    break;
                }
            }
        }

        if (mainActivity != null)
        {
            var intentFilter = mainActivity.SelectSingleNode("intent-filter");
            
            // Add Oculus 2D Category
            XmlElement category2D = xmlDoc.CreateElement("category");
            category2D.SetAttribute("name", "http://schemas.android.com/apk/res/android", "com.oculus.intent.category.2D");
            intentFilter.AppendChild(category2D);
            
            Debug.Log("[QuestManifestMod] Added 2D Category to Main Activity.");
        }
        else
        {
            Debug.LogWarning("[QuestManifestMod] Could not find Main Activity to inject 2D tag.");
        }

        // 3. Add FileProvider (if not exists)
        // Note: The FileProvider class reference might change based on AndroidX usage.
        // We assume AndroidX is enabled.
        XmlNode existingProvider = application.SelectSingleNode("provider[@android:authorities='com.vrpirates.rookieonquest.fileprovider']", GetNamespaceManager(xmlDoc));
        if (existingProvider == null)
        {
            XmlElement provider = xmlDoc.CreateElement("provider");
            provider.SetAttribute("name", "http://schemas.android.com/apk/res/android", "androidx.core.content.FileProvider");
            provider.SetAttribute("authorities", "http://schemas.android.com/apk/res/android", "com.vrpirates.rookieonquest.fileprovider");
            provider.SetAttribute("exported", "http://schemas.android.com/apk/res/android", "false");
            provider.SetAttribute("grantUriPermissions", "http://schemas.android.com/apk/res/android", "true");

            XmlElement metaData = xmlDoc.CreateElement("meta-data");
            metaData.SetAttribute("name", "http://schemas.android.com/apk/res/android", "android.support.FILE_PROVIDER_PATHS");
            metaData.SetAttribute("resource", "http://schemas.android.com/apk/res/android", "@xml/file_paths");
            
            provider.AppendChild(metaData);
            application.AppendChild(provider);
            Debug.Log("[QuestManifestMod] Injected FileProvider.");
        }

        xmlDoc.Save(manifestPath);
    }

    private void AddPermission(XmlDocument doc, string permissionName)
    {
        var manifest = doc.SelectSingleNode("manifest");
        XmlNode nsMgr = GetNamespaceManager(doc).LookupNamespace("android") == null ? null : manifest;
        
        // Check if exists
        // Simple check string matching
        if (doc.InnerXml.Contains(permissionName)) return;

        XmlElement elem = doc.CreateElement("uses-permission");
        elem.SetAttribute("name", "http://schemas.android.com/apk/res/android", permissionName);
        manifest.AppendChild(elem);
    }

    private XmlNamespaceManager GetNamespaceManager(XmlDocument doc)
    {
        var ns = new XmlNamespaceManager(doc.NameTable);
        ns.AddNamespace("android", "http://schemas.android.com/apk/res/android");
        return ns;
    }
}
