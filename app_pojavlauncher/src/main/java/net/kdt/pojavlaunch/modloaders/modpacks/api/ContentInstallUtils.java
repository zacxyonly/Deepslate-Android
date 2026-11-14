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
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class ContentInstallUtils {
    private static final String TAG = "ContentInstallUtils";

    public static void installSingleContent(ModDetail modDetail, int selectedVersion, @Nullable MinecraftProfile targetProfile) throws IOException {
        MinecraftProfile profile = targetProfile;
        if (profile == null) {
            profile = LauncherProfiles.getCurrentProfile();
        }
        File gameDir = Tools.getGameDirPath(profile);

        String type = modDetail.projectType != null ? modDetail.projectType : SearchFilters.TYPE_MOD;
        File targetDir;
        if (SearchFilters.TYPE_RESOURCEPACK.equals(type)) {
            targetDir = new File(gameDir, "resourcepacks");
        } else if (SearchFilters.TYPE_SHADER.equals(type)) {
            targetDir = new File(gameDir, "shaderpacks");
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
        } catch (Exception e) {
            if (partFile.exists()) {
                partFile.delete();
            }
            throw new IOException("Download failed for " + fileName + ": " + e.getMessage(), e);
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }

        final String finalFileName = fileName;
        ContextExecutor.execute(new ContextExecutorTask() {
            @Override
            public void executeWithActivity(Activity activity) {
                Toast.makeText(activity, activity.getString(R.string.search_mod_installed_toast, finalFileName), Toast.LENGTH_LONG).show();
            }

            @Override
            public void executeWithApplication(Context context) {
                Toast.makeText(context, context.getString(R.string.search_mod_installed_toast, finalFileName), Toast.LENGTH_LONG).show();
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
}
