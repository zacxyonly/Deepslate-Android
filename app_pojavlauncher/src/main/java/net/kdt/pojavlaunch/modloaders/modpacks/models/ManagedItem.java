package net.kdt.pojavlaunch.modloaders.modpacks.models;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

public class ManagedItem {
    public static final int TYPE_MOD = 0;
    public static final int TYPE_RESOURCEPACK = 1;
    public static final int TYPE_SHADER = 2;
    public static final int TYPE_MODPACK = 3;
    public static final int TYPE_MAP = 4;

    public int type;
    @Nullable public File file;
    public String name;
    public String fileName;
    @Nullable public String version;
    @Nullable public String description;
    public long fileSize;
    public boolean isEnabled;
    @Nullable public Bitmap iconBitmap;

    // For Modpack profiles
    @Nullable public String profileKey;
    @Nullable public MinecraftProfile profile;
    public boolean isActiveProfile;

    public ManagedItem() {}

    public ManagedItem(int type, File file, String name, String fileName, @Nullable String version,
                       @Nullable String description, long fileSize, boolean isEnabled) {
        this.type = type;
        this.file = file;
        this.name = name;
        this.fileName = fileName;
        this.version = version;
        this.description = description;
        this.fileSize = fileSize;
        this.isEnabled = isEnabled;
    }

    public static ManagedItem forProfile(String profileKey, MinecraftProfile profile, boolean isActive) {
        ManagedItem item = new ManagedItem();
        item.type = TYPE_MODPACK;
        item.profileKey = profileKey;
        item.profile = profile;
        item.name = profile.name != null && !profile.name.isEmpty() ? profile.name : "Profile (" + profileKey + ")";
        item.fileName = profile.gameDir != null ? profile.gameDir : "(Default Directory)";
        item.version = profile.lastVersionId;
        item.description = "Versi: " + (profile.lastVersionId != null ? profile.lastVersionId : "Unknown");
        item.isEnabled = true;
        item.isActiveProfile = isActive;
        return item;
    }
}
