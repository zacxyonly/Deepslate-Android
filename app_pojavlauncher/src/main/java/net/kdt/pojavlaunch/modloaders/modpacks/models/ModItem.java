package net.kdt.pojavlaunch.modloaders.modpacks.models;

import androidx.annotation.NonNull;

public class ModItem extends ModSource {

    public String id;
    public String title;
    public String description;
    public String imageUrl;
    public String author;
    public long downloadCount;

    public ModItem(int apiSource, boolean isModpack, String id, String title, String description, String imageUrl) {
        this(apiSource, isModpack ? SearchFilters.TYPE_MODPACK : SearchFilters.TYPE_MOD, id, title, description, imageUrl, null, 0);
    }

    public ModItem(int apiSource, String projectType, String id, String title, String description, String imageUrl) {
        this(apiSource, projectType, id, title, description, imageUrl, null, 0);
    }

    public ModItem(int apiSource, String projectType, String id, String title, String description, String imageUrl, String author, long downloadCount) {
        this.apiSource = apiSource;
        this.projectType = projectType != null ? projectType : SearchFilters.TYPE_MOD;
        this.isModpack = SearchFilters.TYPE_MODPACK.equals(this.projectType);
        this.id = id;
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
        this.author = author;
        this.downloadCount = downloadCount;
    }

    public String getFormattedDownloads() {
        if (downloadCount <= 0) return null;
        if (downloadCount >= 1_000_000) {
            return String.format("%.1fM", downloadCount / 1_000_000.0);
        } else if (downloadCount >= 1_000) {
            return String.format("%.1fK", downloadCount / 1_000.0);
        } else {
            return String.valueOf(downloadCount);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "ModItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", isModpack=" + isModpack +
                ", projectType=" + projectType +
                '}';
    }

    public String getIconCacheTag() {
        return apiSource + "_" + id;
    }
}
