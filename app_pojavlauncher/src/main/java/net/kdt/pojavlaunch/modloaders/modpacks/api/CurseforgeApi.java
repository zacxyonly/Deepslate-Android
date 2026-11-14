package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public class CurseforgeApi implements ModpackApi{
    private static final Pattern sMcVersionPattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
    private static final int ALGO_SHA_1 = 1;
    // Stolen from
    // https://github.com/AnzhiZhang/CurseForgeModpackDownloader/blob/6cb3f428459f0cc8f444d16e54aea4cd1186fd7b/utils/requester.py#L93
    private static final int CURSEFORGE_MINECRAFT_GAME_ID = 432;
    private static final int CURSEFORGE_MODPACK_CLASS_ID = 4471;
    // https://api.curseforge.com/v1/categories?gameId=432 and search for "Mods" (case-sensitive)
    private static final int CURSEFORGE_MOD_CLASS_ID = 6;
    private static final int CURSEFORGE_SORT_RELEVANCY = 1;
    private static final int CURSEFORGE_PAGINATION_SIZE = 50;
    private static final int CURSEFORGE_PAGINATION_END_REACHED = -1;
    private static final int CURSEFORGE_PAGINATION_ERROR = -2;

    private final ApiHandler mApiHandler;
    public CurseforgeApi(String apiKey) {
        mApiHandler = new ApiHandler("https://api.curseforge.com/v1", apiKey);
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CurseforgeSearchResult curseforgeSearchResult = (CurseforgeSearchResult) previousPageResult;

        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CURSEFORGE_MINECRAFT_GAME_ID);
        String pType = searchFilters.projectType != null ? searchFilters.projectType
                : (searchFilters.isModpack ? SearchFilters.TYPE_MODPACK : SearchFilters.TYPE_MOD);
        int classId = CURSEFORGE_MOD_CLASS_ID;
        if (SearchFilters.TYPE_MODPACK.equals(pType)) {
            classId = CURSEFORGE_MODPACK_CLASS_ID;
        } else if (SearchFilters.TYPE_RESOURCEPACK.equals(pType)) {
            classId = 12; // CurseForge Texture Packs
        }
        params.put("classId", classId);
        params.put("searchFilter", searchFilters.name != null ? searchFilters.name : "");
        params.put("sortField", CURSEFORGE_SORT_RELEVANCY);
        params.put("sortOrder", "desc");
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            params.put("gameVersion", searchFilters.mcVersion);
        if (SearchFilters.TYPE_MOD.equals(pType) && searchFilters.modLoader != null && !searchFilters.modLoader.isEmpty()) {
            String loader = searchFilters.modLoader.toLowerCase();
            if (loader.contains("forge") && !loader.contains("neo")) params.put("modLoaderType", 1);
            else if (loader.contains("fabric")) params.put("modLoaderType", 4);
            else if (loader.contains("quilt")) params.put("modLoaderType", 5);
            else if (loader.contains("neo")) params.put("modLoaderType", 6);
        }
        if(previousPageResult != null)
            params.put("index", curseforgeSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("mods/search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray dataArray = response.getAsJsonArray("data");
        if(dataArray == null) return null;
        JsonObject paginationInfo = response.getAsJsonObject("pagination");
        ArrayList<ModItem> modItemList = new ArrayList<>(dataArray.size());
        for(int i = 0; i < dataArray.size(); i++) {
            JsonObject dataElement = dataArray.get(i).getAsJsonObject();
            JsonElement allowModDistribution = dataElement.get("allowModDistribution");
            // Gson automatically casts null to false, which leans to issues
            // So, only check the distribution flag if it is non-null
            if(!allowModDistribution.isJsonNull() && !allowModDistribution.getAsBoolean()) {
                Log.i("CurseforgeApi", "Skipping modpack "+dataElement.get("name").getAsString() + " because curseforge sucks");
                continue;
            }
            String author = null;
            if (dataElement.has("authors") && dataElement.getAsJsonArray("authors").size() > 0) {
                try {
                    author = dataElement.getAsJsonArray("authors").get(0).getAsJsonObject().get("name").getAsString();
                } catch (Exception ignored) {}
            }
            long downloads = dataElement.has("downloadCount") ? dataElement.get("downloadCount").getAsLong() : 0L;
            String logoUrl = null;
            if (dataElement.has("logo") && !dataElement.get("logo").isJsonNull() && dataElement.getAsJsonObject("logo").has("thumbnailUrl")) {
                logoUrl = dataElement.getAsJsonObject("logo").get("thumbnailUrl").getAsString();
            }
            ModItem modItem = new ModItem(Constants.SOURCE_CURSEFORGE,
                    pType,
                    dataElement.get("id").getAsString(),
                    dataElement.get("name").getAsString(),
                    dataElement.get("summary").getAsString(),
                    logoUrl,
                    author,
                    downloads);
            modItemList.add(modItem);
        }
        if(curseforgeSearchResult == null) curseforgeSearchResult = new CurseforgeSearchResult();
        curseforgeSearchResult.results = modItemList.toArray(new ModItem[0]);
        curseforgeSearchResult.totalResultCount = paginationInfo.get("totalCount").getAsInt();
        curseforgeSearchResult.previousOffset += dataArray.size();
        return curseforgeSearchResult;

    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        fillInMissingModItemData(item);
        ArrayList<JsonObject> allModDetails = new ArrayList<>();
        int index = 0;
        while(index != CURSEFORGE_PAGINATION_END_REACHED &&
                index != CURSEFORGE_PAGINATION_ERROR) {
            index = getPaginatedDetails(allModDetails, index, item.id);
        }
        if(index == CURSEFORGE_PAGINATION_ERROR) return null;
        int length = allModDetails.size();
        String[] versionNames = new String[length];
        String[] versionIds = new String[length];
        String[] mcVersionNames = new String[length];
        String[] versionUrls = new String[length];
        String[] hashes = new String[length];
        ModDetail.Dependencies[][] dependencies = new ModDetail.Dependencies[length][];
        for(int i = 0; i < allModDetails.size(); i++) {
            JsonObject modDetail = allModDetails.get(i);
            versionNames[i] = modDetail.get("displayName").getAsString();
            versionIds[i] = modDetail.get("id").getAsString();
            JsonElement downloadUrl = modDetail.get("downloadUrl");
            versionUrls[i] = downloadUrl.getAsString();

            JsonArray gameVersions = modDetail.getAsJsonArray("gameVersions");
            try {
                JsonArray dependenciesJsonArray = modDetail.getAsJsonArray("dependencies");
                dependencies[i] = new ModDetail.Dependencies[dependenciesJsonArray.size()];
                for (int i1 = 0; i1 < dependenciesJsonArray.size(); ++i1) {
                    JsonObject obj = dependenciesJsonArray.get(i1).getAsJsonObject();
                    dependencies[i][i1] = new ModDetail.Dependencies(
                            GsonJsonUtils.getStringSafe(obj, "modId"),
                            null,
                            null, // These two are only present on modrinth
                            GsonJsonUtils.getStringSafe(obj, "relationType")
                    );
                }
            } catch (Exception ignored) {}
            for(JsonElement jsonElement : gameVersions) {
                String gameVersion = jsonElement.getAsString();
                if(!sMcVersionPattern.matcher(gameVersion).matches()) {
                    continue;
                }
                mcVersionNames[i] = gameVersion;
                break;
            }

            hashes[i] = getSha1FromModData(modDetail);
        }
        return new ModDetail(item, versionNames, versionIds, mcVersionNames, versionUrls, hashes, dependencies);
    }

    private void fillInMissingModItemData(ModItem item) {
        if (!(item.title == null || item.description == null || item.imageUrl == null)) return;
        JsonObject response = mApiHandler.get(String.format("mods/%s", item.id), JsonObject.class);
        JsonObject data = GsonJsonUtils.getJsonObjectSafe(response, "data");
        if (data == null) return;
        if (item.title == null) {
            JsonElement title = data.get("name");
            item.title = title != null ? title.getAsString() : "";
        }
        if (item.description == null) {
            JsonElement description = data.get("summary");
            item.description = description != null ? description.getAsString() : "";
        }
        if (item.imageUrl == null) {
            JsonElement imageUrl = data.getAsJsonObject("logo").get("thumbnailUrl");
            item.imageUrl = imageUrl != null ? imageUrl.getAsString() : null;
        }
    }

    @Override
    public ModLoader installMod(ModDetail modDetail, int selectedVersion) throws IOException{
        if (modDetail.isModpack) {
            return ModpackInstaller.installModpack(modDetail, selectedVersion, this::installCurseforgeZip);
        }
        installSingleContent(modDetail, selectedVersion);
        return null;
    }

    private void installSingleContent(ModDetail modDetail, int selectedVersion) throws IOException {
        net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile currentProfile = net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.getCurrentProfile();
        File gameDir = Tools.getGameDirPath(currentProfile);

        File targetDir;
        String type = modDetail.projectType != null ? modDetail.projectType : SearchFilters.TYPE_MOD;
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

        String fileName = null;
        if (modDetail.fileNames != null && selectedVersion < modDetail.fileNames.length) {
            fileName = modDetail.fileNames[selectedVersion];
        }
        if (fileName == null || fileName.isEmpty()) {
            try {
                fileName = Uri.parse(downloadUrl).getLastPathSegment();
            } catch (Exception ignored) {}
        }
        if (fileName == null || fileName.isEmpty()) {
            String ext = SearchFilters.TYPE_MOD.equals(type) ? ".jar" : ".zip";
            fileName = modDetail.title.replaceAll("[^a-zA-Z0-9.-]", "_") + ext;
        }

        File targetFile = new File(targetDir, fileName);
        byte[] downloadBuffer = new byte[8192];
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_downloading_mods, 0.0f, 0.0f);

        net.kdt.pojavlaunch.utils.DownloadUtils.downloadFileMonitored(downloadUrl, targetFile, downloadBuffer, (bytesRead, totalBytes) -> {
            if (totalBytes > 0) {
                int percent = (int) ((bytesRead * 100) / totalBytes);
                float readMB = (float) bytesRead / (1024f * 1024f);
                float totalMB = (float) totalBytes / (1024f * 1024f);
                ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, percent, R.string.modpack_download_downloading_mods, readMB, totalMB);
            }
        });

        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);

        final String finalFileName = fileName;
        net.kdt.pojavlaunch.lifecycle.ContextExecutor.execute(new net.kdt.pojavlaunch.lifecycle.ContextExecutorTask() {
            @Override
            public void executeWithActivity(Activity activity) {
                android.widget.Toast.makeText(activity, "Installed: " + finalFileName, android.widget.Toast.LENGTH_LONG).show();
            }

            @Override
            public void executeWithApplication(android.content.Context context) {
                android.widget.Toast.makeText(context, "Installed: " + finalFileName, android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public ModLoader importModpack(File modpackFile) throws IOException, NoSuchAlgorithmException {
        return ModpackInstaller.importModpack(modpackFile, Constants.SOURCE_CURSEFORGE, this::installCurseforgeZip);
    }


    private int getPaginatedDetails(ArrayList<JsonObject> objectList, int index, String modId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("index", index);
        params.put("pageSize", CURSEFORGE_PAGINATION_SIZE);

        JsonObject response = mApiHandler.get("mods/"+modId+"/files", params, JsonObject.class);
        JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
        if(data == null) return CURSEFORGE_PAGINATION_ERROR;

        for(int i = 0; i < data.size(); i++) {
            JsonObject fileInfo = data.get(i).getAsJsonObject();
            if(fileInfo.get("isServerPack").getAsBoolean()) continue;
            objectList.add(fileInfo);
        }
        if(data.size() < CURSEFORGE_PAGINATION_SIZE) {
            return CURSEFORGE_PAGINATION_END_REACHED; // we read the remainder! yay!
        }
        return index + data.size();
    }

    private ModLoader installCurseforgeZip(File zipFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(zipFile)){
            CurseManifest curseManifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "manifest.json")),
                    CurseManifest.class);
            if(!verifyManifest(curseManifest)) {
                Log.i("CurseforgeApi","manifest verification failed");
                return null;
            }
            ModDownloader modDownloader = new ModDownloader(new File(instanceDestination,"mods"), true);
            int fileCount = curseManifest.files.length;
            for(int i = 0; i < fileCount; i++) {
                final CurseManifest.CurseFile curseFile = curseManifest.files[i];
                modDownloader.submitDownload(()->{
                    String url = getDownloadUrl(curseFile.projectID, curseFile.fileID);
                    if(url == null && curseFile.required)
                        throw new IOException("Failed to obtain download URL for "+curseFile.projectID+" "+curseFile.fileID);
                    else if(url == null) return null;
                    return new ModDownloader.FileInfo(url, FileUtils.getFileName(url), getDownloadSha1(curseFile.projectID, curseFile.fileID));
                });
            }
            modDownloader.awaitFinish((c,m)->
                    ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, (int) Math.max((float)c/m*100,0), R.string.modpack_download_downloading_mods_fc, c, m)
            );
            String overridesDir = "overrides";
            if(curseManifest.overrides != null) overridesDir = curseManifest.overrides;
            ZipUtils.zipExtract(modpackZipFile, overridesDir, instanceDestination);
            return createInfo(curseManifest.minecraft);
        }
    }

    private ModLoader createInfo(CurseManifest.CurseMinecraft minecraft) {
        CurseManifest.CurseModLoader primaryModLoader = null;
        for(CurseManifest.CurseModLoader modLoader : minecraft.modLoaders) {
            if(modLoader.primary) {
                primaryModLoader = modLoader;
                break;
            }
        }
        if(primaryModLoader == null) primaryModLoader = minecraft.modLoaders[0];
        String modLoaderId = primaryModLoader.id;
        int dashIndex = modLoaderId.indexOf('-');
        String modLoaderName = modLoaderId.substring(0, dashIndex);
        String modLoaderVersion = modLoaderId.substring(dashIndex+1);
        Log.i("CurseforgeApi", modLoaderId + " " + modLoaderName + " "+modLoaderVersion);
        int modLoaderTypeInt;
        switch (modLoaderName) {
            case "forge":
                modLoaderTypeInt = ModLoader.MOD_LOADER_FORGE;
                break;
            case "fabric":
                modLoaderTypeInt = ModLoader.MOD_LOADER_FABRIC;
                break;
            case "neoforge":
                modLoaderTypeInt = ModLoader.MOD_LOADER_NEOFORGE;
                break;
            default:
                return null;
            //TODO: Quilt is also Forge? How does that work?
        }
        return new ModLoader(modLoaderTypeInt, modLoaderVersion, minecraft.version);
    }

    private String getDownloadUrl(long projectID, long fileID) {
        // First try the official api endpoint
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID+"/download-url", JsonObject.class);
        if (response != null && !response.get("data").isJsonNull())
            return response.get("data").getAsString();

        // Otherwise, fallback to building an edge link
        JsonObject fallbackResponse = mApiHandler.get(String.format("mods/%s/files/%s", projectID, fileID), JsonObject.class);
        if (fallbackResponse != null && !fallbackResponse.get("data").isJsonNull()){
            JsonObject modData = fallbackResponse.get("data").getAsJsonObject();
            int id = modData.get("id").getAsInt();
            return String.format("https://edge.forgecdn.net/files/%s/%s/%s", id/1000, id % 1000, modData.get("fileName").getAsString());
        }

        return null;
    }

    private @Nullable String getDownloadSha1(long projectID, long fileID) {
        // Try the api endpoint, die in the other case
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID, JsonObject.class);
        JsonObject data = GsonJsonUtils.getJsonObjectSafe(response, "data");
        if(data == null) return null;
        return getSha1FromModData(data);
    }

    private String getSha1FromModData(@NonNull JsonObject object) {
        JsonArray hashes = GsonJsonUtils.getJsonArraySafe(object, "hashes");
        if(hashes == null) return null;
        for (JsonElement jsonElement : hashes) {
            // The sha1 = 1; md5 = 2;
            JsonObject jsonObject = GsonJsonUtils.getJsonObjectSafe(jsonElement);
            if(GsonJsonUtils.getIntSafe(
                    jsonObject,
                    "algo",
                    -1) == ALGO_SHA_1) {
                return GsonJsonUtils.getStringSafe(jsonObject, "value");
            }
        }
        return null;
    }

    private boolean verifyManifest(CurseManifest manifest) {
        if(!"minecraftModpack".equals(manifest.manifestType)) return false;
        if(manifest.manifestVersion != 1) return false;
        if(manifest.minecraft == null) return false;
        if(manifest.minecraft.version == null) return false;
        if(manifest.minecraft.modLoaders == null) return false;
        return manifest.minecraft.modLoaders.length >= 1;
    }

    static class CurseforgeSearchResult extends SearchResult {
        int previousOffset;
    }
}
