package net.kdt.pojavlaunch.modloaders.modpacks.models;

import androidx.annotation.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public static final String TYPE_MOD = "mod";
    public static final String TYPE_RESOURCEPACK = "resourcepack";
    public static final String TYPE_SHADER = "shader";
    public static final String TYPE_MODPACK = "modpack";
    public static final String TYPE_MAP = "maps";

    public String projectType = TYPE_MOD;
    public boolean isModpack = false;
    public String name = "";
    @Nullable public String mcVersion;
    @Nullable public String modLoader;

    public void setProjectType(String type) {
        this.projectType = type;
        this.isModpack = TYPE_MODPACK.equals(type);
    }
}
