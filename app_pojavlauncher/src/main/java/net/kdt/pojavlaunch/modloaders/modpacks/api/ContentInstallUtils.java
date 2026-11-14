package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ContentInstallUtils {
    private static final String TAG = "ContentInstallUtils";

    public static void installSingleContent(ModDetail modDetail, int selectedVersion, @Nullable MinecraftProfile targetProfile) throws IOException {
        MinecraftProfile profile = targetProfile;
        if (profile == null) {
            profile = LauncherProfiles.getCurrentProfile();
        }
        File gameDir = Tools.getGameDirPath(profile);

        String type = modDetail.projectType != null ? modDetail.projectType : SearchFilters.TYPE_MOD;
        boolean isMap = SearchFilters.TYPE_MAP.equals(type);
        File targetDir;
        if (SearchFilters.TYPE_RESOURCEPACK.equals(type)) {
            targetDir = new File(gameDir, "resourcepacks");
        } else if (SearchFilters.TYPE_SHADER.equals(type)) {
            targetDir = new File(gameDir, "shaderpacks");
        } else if (isMap) {
            targetDir = new File(gameDir, "saves");
        } else {
            targetDir = new File(gameDir, "mods");
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        String downloadUrl = (modDetail.versionUrls != null && selectedVersion < modDetail.versionUrls.length)
                ? modDetail.versionUrls[selectedVersion] : null;
        if (downloadUrl == null) {
            throw new IOException("No download URL found for selected version.");
        }

        String defaultExt = SearchFilters.TYPE_MOD.equals(type) ? ".jar" : ".zip";
        String rawFileName = null;
        if (modDetail.fileNames != null && selectedVersion < modDetail.fileNames.length) {
            rawFileName = modDetail.fileNames[selectedVersion];
        }
        if (rawFileName == null || rawFileName.isEmpty()) {
            try {
                rawFileName = Uri.parse(downloadUrl).getLastPathSegment();
            } catch (Exception ignored) {}
        }
        if (rawFileName == null || rawFileName.isEmpty()) {
            rawFileName = modDetail.title + defaultExt;
        }

        // Issue 5: Sanitize file name to prevent directory traversal
        String fileName = sanitizeFileName(rawFileName, defaultExt);

        // Safe atomic download with .part file (Issue 6)
        File finalFile = new File(targetDir, fileName);
        File partFile = new File(targetDir, fileName + ".part");
        if (partFile.exists()) {
            partFile.delete();
        }

        byte[] downloadBuffer = new byte[8192];
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_downloading_mods, 0.0f, 0.0f);

        String installedWorldName = null;
        try {
            DownloadUtils.downloadFileMonitored(downloadUrl, partFile, downloadBuffer, (bytesRead, totalBytes) -> {
                if (totalBytes > 0) {
                    int percent = (int) ((bytesRead * 100) / totalBytes);
                    float readMB = (float) bytesRead / (1024f * 1024f);
                    float totalMB = (float) totalBytes / (1024f * 1024f);
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, percent, R.string.modpack_download_downloading_mods, readMB, totalMB);
                }
            });

            if (!partFile.exists() || partFile.length() == 0) {
                throw new IOException("Downloaded file is empty or missing: " + fileName);
            }

            if (isMap) {
                // Intelligent saves extraction (locates level.dat and unzips cleanly)
                installedWorldName = unpackSaveZip(partFile, targetDir, modDetail.title);
            } else {
                // Old version detection: if mod, check if there's an older jar for this mod
                if (SearchFilters.TYPE_MOD.equals(type)) {
                    disableOrWarnOlderVersions(targetDir, fileName);
                }

                // Atomic rename
                if (finalFile.exists()) {
                    finalFile.delete();
                }
                if (!partFile.renameTo(finalFile)) {
                    Files.copy(partFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    partFile.delete();
                }

                // Automatic required dependency resolution for mods
                if (SearchFilters.TYPE_MOD.equals(type)) {
                    installRequiredDependencies(modDetail, selectedVersion, targetDir, profile);
                }
            }
        } catch (Exception e) {
            if (partFile.exists()) {
                partFile.delete();
            }
            throw new IOException("Download failed for " + fileName + ": " + e.getMessage(), e);
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }

        final String finalResultName = isMap && installedWorldName != null ? installedWorldName : fileName;
        final boolean finalIsMap = isMap;
        ContextExecutor.execute(new ContextExecutorTask() {
            @Override
            public void executeWithActivity(Activity activity) {
                int strRes = finalIsMap ? R.string.manager_map_installed : R.string.search_mod_installed_toast;
                Toast.makeText(activity, activity.getString(strRes, finalResultName), Toast.LENGTH_LONG).show();
            }

            @Override
            public void executeWithApplication(Context context) {
                int strRes = finalIsMap ? R.string.manager_map_installed : R.string.search_mod_installed_toast;
                Toast.makeText(context, context.getString(strRes, finalResultName), Toast.LENGTH_LONG).show();
            }
        });
    }

    public static String sanitizeFileName(String fileName) {
        return sanitizeFileName(fileName, "");
    }

    public static String sanitizeFileName(String fileName, String defaultExt) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "content_" + System.currentTimeMillis() + defaultExt;
        }
        String clean = new File(fileName).getName().trim();
        clean = clean.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
            return "content_" + System.currentTimeMillis() + defaultExt;
        }
        return clean;
    }

    private static void disableOrWarnOlderVersions(File targetDir, String newFileName) {
        try {
            String basePrefix = extractModPrefix(newFileName);
            if (basePrefix.length() < 3) return;

            File[] files = targetDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (name.equalsIgnoreCase(newFileName) || name.equalsIgnoreCase(newFileName + ".part")) continue;
                if (name.toLowerCase().endsWith(".jar") && extractModPrefix(name).equalsIgnoreCase(basePrefix)) {
                    Log.i(TAG, "Disabling older mod version: " + name);
                    File disabled = new File(targetDir, name + ".disabled");
                    f.renameTo(disabled);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed checking older mod versions", e);
        }
    }

    private static String extractModPrefix(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".jar")) name = name.substring(0, name.length() - 4);
        else if (name.endsWith(".jar.disabled")) name = name.substring(0, name.length() - 13);

        int dashIdx = name.indexOf('-');
        if (dashIdx > 2) return name.substring(0, dashIdx);
        int underscoreIdx = name.indexOf('_');
        if (underscoreIdx > 2) return name.substring(0, underscoreIdx);
        return name;
    }

    public static String unpackSaveZip(File zipFile, File savesDir, String preferredName) throws IOException {
        String worldFolderName = sanitizeFileName(preferredName != null && !preferredName.trim().isEmpty() ? preferredName : zipFile.getName());
        if (worldFolderName.toLowerCase().endsWith(".zip")) {
            worldFolderName = worldFolderName.substring(0, worldFolderName.length() - 4);
        }
        if (worldFolderName.toLowerCase().endsWith(".part")) {
            worldFolderName = worldFolderName.substring(0, worldFolderName.length() - 5);
        }
        if (worldFolderName.isEmpty()) {
            worldFolderName = "world_" + System.currentTimeMillis();
        }

        try (ZipFile zip = new ZipFile(zipFile)) {
            String levelPrefix = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.endsWith("level.dat")) {
                    int idx = name.lastIndexOf("level.dat");
                    levelPrefix = name.substring(0, idx);
                    break;
                }
            }

            if (levelPrefix == null) {
                levelPrefix = "";
            }

            File targetWorldDir = getUniqueDirectory(savesDir, worldFolderName);
            if (!targetWorldDir.exists()) {
                targetWorldDir.mkdirs();
            }

            entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(levelPrefix)) continue;

                String relativePath = name.substring(levelPrefix.length());
                if (relativePath.isEmpty() || relativePath.equals("/")) continue;

                File outFile = new File(targetWorldDir, relativePath);
                if (!outFile.getCanonicalPath().startsWith(targetWorldDir.getCanonicalPath())) {
                    continue;
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (InputStream is = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
            return targetWorldDir.getName();
        } finally {
            if (zipFile.exists()) {
                zipFile.delete();
            }
        }
    }

    public static File getUniqueDirectory(File parentDir, String name) {
        File dir = new File(parentDir, name);
        if (!dir.exists()) return dir;
        int counter = 1;
        while (dir.exists()) {
            dir = new File(parentDir, name + " (" + counter + ")");
            counter++;
        }
        return dir;
    }

    private static void installRequiredDependencies(ModDetail modDetail, int selectedVersion, File targetDir, MinecraftProfile profile) {
        if (modDetail.dependencies == null || selectedVersion >= modDetail.dependencies.length) return;
        ModDetail.Dependencies[] deps = modDetail.dependencies[selectedVersion];
        if (deps == null || deps.length == 0) return;

        for (ModDetail.Dependencies dep : deps) {
            if (dep == null) continue;
            boolean isRequired = "required".equalsIgnoreCase(dep.dependency_type) || "3".equals(dep.dependency_type);
            if (!isRequired) continue;

            String depId = dep.project_id;
            String depVer = dep.version_id;
            String depFile = dep.file_name;

            if (isDependencyInstalled(targetDir, depId, depFile)) {
                Log.i(TAG, "Dependency already present: " + depId);
                continue;
            }

            try {
                if (modDetail.apiSource == Constants.SOURCE_MODRINTH && depVer != null && !depVer.isEmpty()) {
                    installModrinthDependency(depVer, depId, targetDir);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed installing required dependency: " + depId, e);
            }
        }
    }

    private static boolean isDependencyInstalled(File targetDir, String depId, String depFile) {
        if (!targetDir.exists()) return false;
        File[] files = targetDir.listFiles();
        if (files == null) return false;

        String idLower = depId != null ? depId.toLowerCase() : "";
        String fileLower = depFile != null ? depFile.toLowerCase() : "";

        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase();
            if (!fileLower.isEmpty() && name.equals(fileLower)) return true;
            if (!idLower.isEmpty() && name.contains(idLower)) return true;
        }
        return false;
    }

    private static void installModrinthDependency(String versionId, String projectId, File targetDir) {
        if (versionId == null || versionId.isEmpty()) return;
        try {
            URL url = new URL("https://api.modrinth.com/v2/version/" + versionId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "DeepslateLauncher/1.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return;

            String json;
            try (InputStream is = conn.getInputStream()) {
                json = Tools.read(is);
            }

            JSONObject obj = new JSONObject(json);
            JSONArray filesArray = obj.optJSONArray("files");
            if (filesArray == null || filesArray.length() == 0) return;

            JSONObject primaryFile = null;
            for (int i = 0; i < filesArray.length(); i++) {
                JSONObject f = filesArray.getJSONObject(i);
                if (f.optBoolean("primary", false)) {
                    primaryFile = f;
                    break;
                }
            }
            if (primaryFile == null) primaryFile = filesArray.getJSONObject(0);

            String fileUrl = primaryFile.getString("url");
            String fileName = sanitizeFileName(primaryFile.getString("filename"), ".jar");
            File finalFile = new File(targetDir, fileName);
            if (finalFile.exists()) return;

            File partFile = new File(targetDir, fileName + ".part");
            DownloadUtils.downloadFile(fileUrl, partFile);
            if (partFile.exists() && partFile.length() > 0) {
                partFile.renameTo(finalFile);
                Log.i(TAG, "Successfully auto-installed dependency: " + fileName);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error fetching Modrinth dependency version: " + versionId, e);
        }
    }
}
