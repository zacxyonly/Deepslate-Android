package net.kdt.pojavlaunch.modloaders.modpacks.models;

import androidx.annotation.NonNull;

public class ModItem extends ModSource {

    public String id;
    public String title;
    public String description;
    public String imageUrl;

    public ModItem(int apiSource, boolean isModpack, String id, String title, String description, String imageUrl) {
        this(apiSource, isModpack ? SearchFilters.TYPE_MODPACK : SearchFilters.TYPE_MOD, id, title, description, imageUrl);
    }

    public ModItem(int apiSource, String projectType, String id, String title, String description, String imageUrl) {
        this.apiSource = apiSource;
        this.projectType = projectType != null ? projectType : SearchFilters.TYPE_MOD;
        this.isModpack = SearchFilters.TYPE_MODPACK.equals(this.projectType);
        this.id = id;
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
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
