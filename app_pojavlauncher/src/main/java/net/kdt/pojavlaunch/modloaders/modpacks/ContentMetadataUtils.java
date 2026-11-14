package net.kdt.pojavlaunch.modloaders.modpacks;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.modloaders.modpacks.models.ManagedItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ContentMetadataUtils {
    // 10MB memory cache for icons
    private static final int MAX_CACHE_SIZE = 10 * 1024 * 1024;
    private static final LruCache<String, Bitmap> sBitmapCache = new LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    public static ManagedItem parseItem(@NonNull File file, int type) {
        String fileName = file.getName();
        boolean isEnabled;
        if (type == ManagedItem.TYPE_MOD) {
            isEnabled = fileName.toLowerCase().endsWith(".jar");
        } else {
            isEnabled = !fileName.toLowerCase().endsWith(".disabled");
        }

        long fileSize = file.isDirectory() ? getFolderSize(file) : file.length();
        String defaultName = cleanName(fileName);

        ManagedItem item = new ManagedItem(type, file, defaultName, fileName, null, null, fileSize, isEnabled);

        // Check icon cache
        Bitmap cached = sBitmapCache.get(file.getAbsolutePath());
        if (cached != null && !cached.isRecycled()) {
            item.iconBitmap = cached;
        }

        try {
            if (type == ManagedItem.TYPE_MOD) {
                parseModMetadata(file, item);
            } else if (type == ManagedItem.TYPE_RESOURCEPACK) {
                parseResourcePackMetadata(file, item);
            } else if (type == ManagedItem.TYPE_SHADER) {
                parseShaderMetadata(file, item);
            }
        } catch (Throwable ignored) {
            // Safe fallback to basic file info
        }

        if (item.iconBitmap != null) {
            sBitmapCache.put(file.getAbsolutePath(), item.iconBitmap);
        }

        return item;
    }

    private static void parseModMetadata(File file, ManagedItem item) {
        if (!file.isFile()) return;

        try (ZipFile zip = new ZipFile(file)) {
            // 1. Try Fabric / Quilt: fabric.mod.json
            ZipEntry fabricEntry = zip.getEntry("fabric.mod.json");
            if (fabricEntry == null) fabricEntry = zip.getEntry("quilt.mod.json");
            if (fabricEntry != null) {
                String jsonStr = readEntryAsString(zip, fabricEntry);
                if (jsonStr != null) {
                    JSONObject obj = new JSONObject(jsonStr);
                    if (obj.has("name")) item.name = obj.getString("name");
                    if (obj.has("version")) item.version = obj.getString("version");
                    if (obj.has("description")) item.description = obj.getString("description");

                    String iconPath = obj.optString("icon");
                    if (!iconPath.isEmpty()) {
                        item.iconBitmap = decodeEntryBitmap(zip, iconPath);
                    }
                    return;
                }
            }

            // 2. Try NeoForge / Forge: META-INF/neoforge.mods.toml or META-INF/mods.toml
            ZipEntry tomlEntry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (tomlEntry == null) tomlEntry = zip.getEntry("META-INF/mods.toml");
            if (tomlEntry != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(tomlEntry), StandardCharsets.UTF_8))) {
                    String line;
                    String logoFile = null;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("displayName") && line.contains("=")) {
                            item.name = extractTomlValue(line);
                        } else if (line.startsWith("version") && line.contains("=") && item.version == null) {
                            item.version = extractTomlValue(line);
                        } else if (line.startsWith("description") && line.contains("=") && item.description == null) {
                            item.description = extractTomlValue(line);
                        } else if (line.startsWith("logoFile") && line.contains("=")) {
                            logoFile = extractTomlValue(line);
                        }
                    }
                    if (logoFile != null && !logoFile.isEmpty()) {
                        item.iconBitmap = decodeEntryBitmap(zip, logoFile);
                        if (item.iconBitmap == null) {
                            item.iconBitmap = decodeEntryBitmap(zip, "assets/" + logoFile);
                        }
                    }
                    return;
                }
            }

            // 3. Try legacy Forge: mcmod.info
            ZipEntry mcmodEntry = zip.getEntry("mcmod.info");
            if (mcmodEntry != null) {
                String jsonStr = readEntryAsString(zip, mcmodEntry);
                if (jsonStr != null) {
                    jsonStr = jsonStr.trim();
                    JSONObject firstMod = null;
                    if (jsonStr.startsWith("[")) {
                        JSONArray arr = new JSONArray(jsonStr);
                        if (arr.length() > 0) firstMod = arr.getJSONObject(0);
                    } else if (jsonStr.startsWith("{")) {
                        firstMod = new JSONObject(jsonStr);
                        if (firstMod.has("modList")) {
                            JSONArray arr = firstMod.getJSONArray("modList");
                            if (arr.length() > 0) firstMod = arr.getJSONObject(0);
                        }
                    }
                    if (firstMod != null) {
                        if (firstMod.has("name")) item.name = firstMod.getString("name");
                        if (firstMod.has("version")) item.version = firstMod.getString("version");
                        if (firstMod.has("description")) item.description = firstMod.getString("description");
                        String logoFile = firstMod.optString("logoFile");
                        if (!logoFile.isEmpty()) {
                            item.iconBitmap = decodeEntryBitmap(zip, logoFile);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void parseResourcePackMetadata(File file, ManagedItem item) {
        if (file.isDirectory()) {
            File metaFile = new File(file, "pack.mcmeta");
            if (metaFile.exists()) {
                try (InputStream is = new FileInputStream(metaFile)) {
                    String str = readStreamAsString(is);
                    JSONObject json = new JSONObject(str).optJSONObject("pack");
                    if (json != null) {
                        Object desc = json.opt("description");
                        if (desc instanceof String) item.description = (String) desc;
                        else if (desc instanceof JSONObject) item.description = ((JSONObject) desc).optString("text");
                    }
                } catch (Throwable ignored) {}
            }
            File iconFile = new File(file, "pack.png");
            if (iconFile.exists()) {
                item.iconBitmap = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
            }
        } else if (file.isFile()) {
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry metaEntry = zip.getEntry("pack.mcmeta");
                if (metaEntry != null) {
                    String str = readEntryAsString(zip, metaEntry);
                    if (str != null) {
                        JSONObject json = new JSONObject(str).optJSONObject("pack");
                        if (json != null) {
                            Object desc = json.opt("description");
                            if (desc instanceof String) item.description = (String) desc;
                            else if (desc instanceof JSONObject) item.description = ((JSONObject) desc).optString("text");
                        }
                    }
                }
                item.iconBitmap = decodeEntryBitmap(zip, "pack.png");
            } catch (Throwable ignored) {}
        }
    }

    private static void parseShaderMetadata(File file, ManagedItem item) {
        if (file.isDirectory()) {
            File iconFile = new File(file, "pack.png");
            if (iconFile.exists()) {
                item.iconBitmap = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
            }
        } else if (file.isFile()) {
            try (ZipFile zip = new ZipFile(file)) {
                item.iconBitmap = decodeEntryBitmap(zip, "pack.png");
            } catch (Throwable ignored) {}
        }
    }

    private static String readEntryAsString(ZipFile zip, ZipEntry entry) {
        try (InputStream is = zip.getInputStream(entry)) {
            return readStreamAsString(is);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStreamAsString(InputStream is) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return baos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeEntryBitmap(ZipFile zip, String entryPath) {
        if (entryPath == null || entryPath.isEmpty()) return null;
        try {
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null && entryPath.startsWith("/")) {
                entry = zip.getEntry(entryPath.substring(1));
            }
            if (entry == null) {
                entry = zip.getEntry("assets/" + entryPath);
            }
            if (entry != null) {
                try (InputStream is = zip.getInputStream(entry)) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    return BitmapFactory.decodeStream(is, null, opts);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractTomlValue(String line) {
        int idx = line.indexOf('=');
        if (idx == -1) return null;
        String val = line.substring(idx + 1).trim();
        if (val.startsWith("\"\"\"") && val.endsWith("\"\"\"") && val.length() >= 6) {
            return val.substring(3, val.length() - 3).trim();
        }
        if (val.startsWith("'''") && val.endsWith("'''") && val.length() >= 6) {
            return val.substring(3, val.length() - 3).trim();
        }
        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
            return val.substring(1, val.length() - 1).trim();
        }
        return val;
    }

    private static String cleanName(String fileName) {
        String name = fileName;
        if (name.toLowerCase().endsWith(".disabled")) {
            name = name.substring(0, name.length() - 9);
        }
        if (name.toLowerCase().endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        } else if (name.toLowerCase().endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.replace('_', ' ').replace('-', ' ').trim();
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    public static long getFolderSize(File directory) {
        long length = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) length += file.length();
                else length += getFolderSize(file);
            }
        }
        return length;
    }
}
