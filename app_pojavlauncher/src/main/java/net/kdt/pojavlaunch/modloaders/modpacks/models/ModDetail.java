package net.kdt.pojavlaunch.modloaders.modpacks.models;

import androidx.annotation.NonNull;

import java.util.Arrays;

public class ModDetail extends ModItem {
    /* A cheap way to map from the front facing name to the underlying id */
    public String[] versionNames;
    public String[] mcVersionNames;
    public String[] versionUrls;
    /* SHA 1 hashes, null if a hash is unavailable */
    public String[] versionHashes;
    public String[] versionIds;
    public String[] fileNames;
    public Dependencies[][] dependencies;

    public ModDetail(ModItem item, String[] versionNames, String[] versionIds, String[] mcVersionNames, String[] versionUrls, String[] hashes, Dependencies[][] dependencies) {
        this(item, versionNames, versionIds, mcVersionNames, versionUrls, null, hashes, dependencies);
    }

    public ModDetail(ModItem item, String[] versionNames, String[] versionIds, String[] mcVersionNames, String[] versionUrls, String[] fileNames, String[] hashes, Dependencies[][] dependencies) {
        super(item.apiSource, item.projectType, item.id, item.title, item.description, item.imageUrl);
        this.versionNames = versionNames;
        this.mcVersionNames = mcVersionNames;
        this.versionIds = versionIds;
        this.versionUrls = versionUrls;
        this.fileNames = fileNames;
        this.versionHashes = hashes;
        this.dependencies = dependencies;

        // Add the mc version to the version model
        for (int i = 0; i < versionNames.length; i++) {
            if (mcVersionNames[i] != null && !versionNames[i].contains(mcVersionNames[i]))
                versionNames[i] += " - " + mcVersionNames[i];
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "ModDetail{" +
                "versionNames=" + Arrays.toString(versionNames) +
                ", mcVersionNames=" + Arrays.toString(mcVersionNames) +
                ", versionIds=" + Arrays.toString(versionIds) +
                ", versionUrls=" + Arrays.toString(versionUrls) +
                ", id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", isModpack=" + isModpack +
                ", projectType=" + projectType +
                '}';
    }

    public static class Dependencies {
        public String project_id; // the main id in item.id
        public String version_id;
        public String file_name;
        public String dependency_type;

        public Dependencies(String project_id, String version_id, String file_name, String dependency_type) {
            this.project_id = project_id;
            this.version_id = version_id;
            this.file_name = file_name;
            this.dependency_type = dependency_type;
        }
    }
}
