using UnityEngine;
using System.IO;
using System;
using SharpCompress.Archives;
using SharpCompress.Archives.SevenZip;
using SharpCompress.Common;
using SharpCompress.Readers;
using System.Linq;

namespace RookieOnQuest.Logic
{
    public static class ArchiveManager
    {
        public static bool ExtractPriorityFile(string archivePath, string outputDirectory, string password, string priorityFile)
        {
            try
            {
                if (!Directory.Exists(outputDirectory)) Directory.CreateDirectory(outputDirectory);
                
                var options = new ReaderOptions { Password = password };
                using (var archive = SevenZipArchive.Open(archivePath, options))
                {
                    Debug.Log($"Archive opened. Looking for {priorityFile}...");
                    var entry = archive.Entries.FirstOrDefault(e => e.Key.EndsWith(priorityFile, StringComparison.OrdinalIgnoreCase));
                    if (entry != null)
                    {
                        Debug.Log($"Found {entry.Key} ({entry.Size} bytes). Extracting...");
                        entry.WriteToDirectory(outputDirectory, new ExtractionOptions { ExtractFullPath = false, Overwrite = true });
                        return true;
                    }
                    else
                    {
                        Debug.LogWarning($"Priority file {priorityFile} not found in archive. Keys present: " + string.Join(", ", archive.Entries.Select(e => e.Key).Take(5)));
                    }
                }
            }
            catch (Exception ex) { Debug.LogError($"[Archive] Priority Error: {ex.Message}"); }
            return false;
        }

        public static void ExtractEverythingElse(string archivePath, string outputDirectory, string password, string skipFile, System.Func<bool> shouldContinue)
        {
            try
            {
                var options = new ReaderOptions { Password = password };
                using (var archive = SevenZipArchive.Open(archivePath, options))
                {
                    int totalEntries = archive.Entries.Count();
                    Debug.Log($"[Archive] Archive contains {totalEntries} total entries.");
                    
                    int extracted = 0;
                    int skipped = 0;
                    int errors = 0;

                    foreach (var entry in archive.Entries)
                    {
                        // Check if we should pause/stop
                        if (shouldContinue != null && !shouldContinue()) {
                            Debug.Log("[Archive] Extraction paused by user.");
                            return;
                        }

                        if (entry.IsDirectory || entry.Key.EndsWith(skipFile, StringComparison.OrdinalIgnoreCase)) continue;

                        try {
                            string fileName = Path.GetFileName(entry.Key);
                            if (string.IsNullOrEmpty(fileName)) continue;

                            string targetPath = Path.Combine(outputDirectory, fileName);
                            
                            // Check if file already exists and has correct size to skip it
                            if (File.Exists(targetPath) && new FileInfo(targetPath).Length == entry.Size) {
                                skipped++;
                                continue;
                            }

                            entry.WriteToFile(targetPath, new ExtractionOptions { Overwrite = true });
                            extracted++;
                            
                            // Log every 100 files to show life
                            if ((extracted + skipped) % 100 == 0) {
                                Debug.Log($"[Archive] Progress: {extracted + skipped}/{totalEntries} processed...");
                            }
                        } catch (Exception) { errors++; }
                    }
                    Debug.Log($"[Archive] Extraction complete. Total: {totalEntries}, Extracted: {extracted}, Skipped: {skipped}, Errors: {errors}");
                }
            }
            catch (Exception ex) { Debug.LogError($"[Archive] Fatal Background Error: {ex.Message}"); }
        }
    }
}
