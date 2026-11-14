package net.kdt.pojavlaunch.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.ContextCompat;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.MineButton;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.modpacks.ContentMetadataUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.ManagedItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ContentInstallUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ManagedItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ContentManagerFragment extends Fragment implements ManagedItemAdapter.ItemActionCallback {
    public static final String TAG = "ContentManagerFragment";

    private final RecyclerView.OnScrollListener mOverlayPositionListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            if (mOverlay != null) {
                mOverlay.setY(MathUtils.clamp(mOverlay.getY() - dy, -mOverlay.getHeight(), mOverlayTopCache));
            }
        }
    };

    private View mOverlay;
    private float mOverlayTopCache;

    private ImageButton mBackButton;
    private EditText mSearchEditText;
    private ImageButton mClearSearchButton;
    private ImageButton mBtnDownload;
    private ImageButton mBtnFolder;
    private MineButton mBtnImport;

    private AppCompatSpinner mProfileSpinner;
    private final List<String> mProfileKeys = new ArrayList<>();
    private String mCurrentProfileKey;
    private MinecraftProfile mCurrentProfile;
    private final AtomicInteger mLoadSequence = new AtomicInteger(0);

    private MineButton mBtnMods;
    private MineButton mBtnResourcePacks;
    private MineButton mBtnShaders;
    private MineButton mBtnMaps;
    private MineButton mBtnModpacks;

    private TextView mSummaryText;
    private ProgressBar mProgressBar;

    private RecyclerView mRecyclerView;
    private ManagedItemAdapter mAdapter;

    private View mEmptyContainer;
    private TextView mEmptyTitle;
    private TextView mEmptyDesc;
    private MineButton mEmptyDownloadBtn;

    private int mCurrentCategory = ManagedItem.TYPE_MOD;

    private final ActivityResultLauncher<String[]> mFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onFilesPicked);

    public ContentManagerFragment() {
        super(R.layout.fragment_content_manager);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mOverlay = view.findViewById(R.id.manager_overlay);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mBackButton = view.findViewById(R.id.manager_back);
        mSearchEditText = view.findViewById(R.id.manager_edittext);
        mClearSearchButton = view.findViewById(R.id.manager_clear_btn);
        mBtnDownload = view.findViewById(R.id.manager_btn_download);
        mBtnFolder = view.findViewById(R.id.manager_btn_folder);
        mBtnImport = view.findViewById(R.id.manager_btn_import);

        mProfileSpinner = view.findViewById(R.id.manager_profile_spinner);

        mBtnMods = view.findViewById(R.id.category_mods_button);
        mBtnResourcePacks = view.findViewById(R.id.category_resourcepacks_button);
        mBtnShaders = view.findViewById(R.id.category_shaders_button);
        mBtnMaps = view.findViewById(R.id.category_maps_button);
        mBtnModpacks = view.findViewById(R.id.category_modpacks_button);

        mSummaryText = view.findViewById(R.id.manager_summary_text);
        mProgressBar = view.findViewById(R.id.manager_progressbar);

        mRecyclerView = view.findViewById(R.id.manager_list);
        mEmptyContainer = view.findViewById(R.id.manager_empty_container);
        mEmptyTitle = view.findViewById(R.id.manager_empty_title);
        mEmptyDesc = view.findViewById(R.id.manager_empty_desc);
        mEmptyDownloadBtn = view.findViewById(R.id.manager_empty_download_btn);

        mAdapter = new ManagedItemAdapter(this);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.addOnScrollListener(mOverlayPositionListener);

        if (mBackButton != null) {
            mBackButton.setOnClickListener(v -> {
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                }
            });
        }

        mSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mClearSearchButton != null) {
                    mClearSearchButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                mAdapter.applyFilter(s.toString());
                updateEmptyStateVisibility();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        if (mClearSearchButton != null) {
            mClearSearchButton.setOnClickListener(v -> mSearchEditText.setText(""));
        }

        mBtnMods.setOnClickListener(v -> selectCategory(ManagedItem.TYPE_MOD));
        mBtnResourcePacks.setOnClickListener(v -> selectCategory(ManagedItem.TYPE_RESOURCEPACK));
        mBtnShaders.setOnClickListener(v -> selectCategory(ManagedItem.TYPE_SHADER));
        if (mBtnMaps != null) {
            mBtnMaps.setOnClickListener(v -> selectCategory(ManagedItem.TYPE_MAP));
        }
        mBtnModpacks.setOnClickListener(v -> selectCategory(ManagedItem.TYPE_MODPACK));

        mBtnDownload.setOnClickListener(v -> openDownloadScreen());
        mEmptyDownloadBtn.setOnClickListener(v -> openDownloadScreen());

        mBtnFolder.setOnClickListener(v -> openCurrentFolder());

        mBtnImport.setOnClickListener(v -> {
            if (mCurrentCategory == ManagedItem.TYPE_MODPACK) {
                openDownloadScreen();
            } else {
                mFilePickerLauncher.launch(new String[]{"*/*"});
            }
        });

        // Set initial category from arguments if present
        if (getArguments() != null) {
            if (getArguments().getBoolean("is_modpack", false)) {
                mCurrentCategory = ManagedItem.TYPE_MODPACK;
            } else if (getArguments().containsKey("category")) {
                mCurrentCategory = getArguments().getInt("category", ManagedItem.TYPE_MOD);
            }
        }

        // Adjust RecyclerView padding so first item isn't hidden under overlay
        mOverlay.post(() -> {
            int overlayHeight = mOverlay.getHeight();
            mRecyclerView.setPadding(mRecyclerView.getPaddingLeft(),
                    overlayHeight + (int) getResources().getDimension(R.dimen._4sdp),
                    mRecyclerView.getPaddingRight(),
                    mRecyclerView.getPaddingBottom());
        });

        initProfiles();
        updateCategoryTabs();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRecyclerView.removeOnScrollListener(mOverlayPositionListener);
    }

    private void initProfiles() {
        LauncherProfiles.load();
        mProfileKeys.clear();
        List<String> displayNames = new ArrayList<>();

        String defaultKey = (getArguments() != null && getArguments().containsKey("profile_key"))
                ? getArguments().getString("profile_key")
                : LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");

        int selectedIdx = 0;
        int idx = 0;

        if (LauncherProfiles.mainProfileJson != null && LauncherProfiles.mainProfileJson.profiles != null) {
            for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
                mProfileKeys.add(entry.getKey());
                MinecraftProfile prof = entry.getValue();
                String name = prof.name != null && !prof.name.isEmpty() ? prof.name : entry.getKey();
                String ver = prof.lastVersionId != null ? prof.lastVersionId : "Unknown";
                displayNames.add(name + " (" + ver + ")");

                if (entry.getKey().equals(defaultKey)) {
                    selectedIdx = idx;
                }
                idx++;
            }
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.item_simple_list_1,
                displayNames
        );
        mProfileSpinner.setAdapter(spinnerAdapter);

        if (!mProfileKeys.isEmpty()) {
            mCurrentProfileKey = mProfileKeys.get(selectedIdx);
            mCurrentProfile = LauncherProfiles.mainProfileJson.profiles.get(mCurrentProfileKey);
            mProfileSpinner.setSelection(selectedIdx);
        }

        mProfileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < mProfileKeys.size()) {
                    String newKey = mProfileKeys.get(position);
                    if (!newKey.equals(mCurrentProfileKey)) {
                        mCurrentProfileKey = newKey;
                        mCurrentProfile = LauncherProfiles.mainProfileJson.profiles.get(mCurrentProfileKey);
                        loadContent();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadContent();
    }

    private void selectCategory(int category) {
        if (mCurrentCategory == category) return;
        mCurrentCategory = category;
        updateCategoryTabs();
        loadContent();
    }

    private void updateCategoryTabs() {
        updateTabButton(mBtnMods, mCurrentCategory == ManagedItem.TYPE_MOD);
        updateTabButton(mBtnResourcePacks, mCurrentCategory == ManagedItem.TYPE_RESOURCEPACK);
        updateTabButton(mBtnShaders, mCurrentCategory == ManagedItem.TYPE_SHADER);
        updateTabButton(mBtnMaps, mCurrentCategory == ManagedItem.TYPE_MAP);
        updateTabButton(mBtnModpacks, mCurrentCategory == ManagedItem.TYPE_MODPACK);

        if (mCurrentCategory == ManagedItem.TYPE_MODPACK) {
            mBtnImport.setVisibility(View.GONE);
        } else {
            mBtnImport.setVisibility(View.VISIBLE);
        }
    }

    private void updateTabButton(MineButton button, boolean isSelected) {
        if (button == null) return;
        button.setSelected(isSelected);
        if (getContext() != null) {
            button.setTextColor(ContextCompat.getColor(getContext(),
                    isSelected ? R.color.minebutton_color : R.color.primary_text));
        }
    }

    private void loadContent() {
        if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
        if (mEmptyContainer != null) mEmptyContainer.setVisibility(View.GONE);

        final int currentSeq = mLoadSequence.incrementAndGet();
        final int targetCategory = mCurrentCategory;
        final MinecraftProfile targetProfile = mCurrentProfile;
        final String activeProfileKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");

        PojavApplication.sExecutorService.execute(() -> {
            List<ManagedItem> items = new ArrayList<>();

            if (targetCategory == ManagedItem.TYPE_MODPACK) {
                if (LauncherProfiles.mainProfileJson != null && LauncherProfiles.mainProfileJson.profiles != null) {
                    for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
                        boolean isActive = entry.getKey().equals(activeProfileKey);
                        items.add(ManagedItem.forProfile(entry.getKey(), entry.getValue(), isActive));
                    }
                }
            } else if (targetProfile != null) {
                File gameDir = Tools.getGameDirPath(targetProfile);
                File targetDir = getTargetDirectory(gameDir, targetCategory);

                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                File[] files = targetDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        if (targetCategory == ManagedItem.TYPE_MOD) {
                            String nameLower = f.getName().toLowerCase();
                            if (nameLower.endsWith(".jar") || nameLower.endsWith(".jar.disabled")) {
                                items.add(ContentMetadataUtils.parseItem(f, targetCategory));
                            }
                        } else if (targetCategory == ManagedItem.TYPE_MAP) {
                            if (f.isDirectory() || f.getName().toLowerCase().endsWith(".zip")) {
                                items.add(ContentMetadataUtils.parseItem(f, targetCategory));
                            }
                        } else {
                            items.add(ContentMetadataUtils.parseItem(f, targetCategory));
                        }
                    }
                }
            }

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                // Ignore stale results from previous fast tab/profile switches
                if (currentSeq != mLoadSequence.get()) return;

                if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
                mAdapter.setItems(items);
                updateSummaryText();
                updateEmptyStateVisibility();
            });
        });
    }

    private File getTargetDirectory(File gameDir, int category) {
        if (category == ManagedItem.TYPE_RESOURCEPACK) {
            return new File(gameDir, "resourcepacks");
        } else if (category == ManagedItem.TYPE_SHADER) {
            return new File(gameDir, "shaderpacks");
        } else if (category == ManagedItem.TYPE_MAP) {
            return new File(gameDir, "saves");
        } else {
            return new File(gameDir, "mods");
        }
    }

    private void updateSummaryText() {
        if (mSummaryText == null) return;
        if (mCurrentCategory == ManagedItem.TYPE_MODPACK) {
            mSummaryText.setText(getString(R.string.manager_summary_modpacks, mAdapter.getAllCount()));
        } else if (mCurrentCategory == ManagedItem.TYPE_MAP) {
            mSummaryText.setText(getString(R.string.manager_summary_maps, mAdapter.getAllCount()));
        } else {
            mSummaryText.setText(getString(R.string.manager_status_summary, mAdapter.getAllCount(), mAdapter.getEnabledCount()));
        }
    }

    private void updateEmptyStateVisibility() {
        if (mEmptyContainer == null) return;
        boolean isEmpty = mAdapter.getItemCount() == 0;
        mEmptyContainer.setVisibility(isEmpty ? View.VISIBLE : View.GONE);

        if (isEmpty) {
            if (mCurrentCategory == ManagedItem.TYPE_MOD) {
                mEmptyTitle.setText(R.string.manager_empty_mods_title);
                mEmptyDesc.setText(R.string.manager_empty_mods_desc);
            } else if (mCurrentCategory == ManagedItem.TYPE_RESOURCEPACK) {
                mEmptyTitle.setText(R.string.manager_empty_resourcepacks_title);
                mEmptyDesc.setText(R.string.manager_empty_resourcepacks_desc);
            } else if (mCurrentCategory == ManagedItem.TYPE_SHADER) {
                mEmptyTitle.setText(R.string.manager_empty_shaders_title);
                mEmptyDesc.setText(R.string.manager_empty_shaders_desc);
            } else if (mCurrentCategory == ManagedItem.TYPE_MAP) {
                mEmptyTitle.setText(R.string.manager_empty_maps_title);
                mEmptyDesc.setText(R.string.manager_empty_maps_desc);
            } else {
                mEmptyTitle.setText(R.string.manager_empty_modpacks_title);
                mEmptyDesc.setText(R.string.manager_empty_modpacks_desc);
            }
        }
    }

    @Override
    public void onToggle(ManagedItem item, boolean isEnabled) {
        if (item.file == null) return;

        PojavApplication.sExecutorService.execute(() -> {
            File currentFile = item.file;
            String curName = currentFile.getName();
            File newFile;

            if (isEnabled) {
                if (curName.toLowerCase().endsWith(".disabled")) {
                    String baseName = curName.substring(0, curName.length() - 9);
                    newFile = new File(currentFile.getParentFile(), baseName);
                } else {
                    newFile = currentFile;
                }
            } else {
                if (!curName.toLowerCase().endsWith(".disabled")) {
                    newFile = new File(currentFile.getParentFile(), curName + ".disabled");
                } else {
                    newFile = currentFile;
                }
            }

            boolean success = true;
            if (!currentFile.equals(newFile)) {
                success = currentFile.renameTo(newFile);
                if (success) {
                    item.file = newFile;
                    item.fileName = newFile.getName();
                    item.isEnabled = isEnabled;
                }
            }

            final boolean renameOk = success;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!renameOk) {
                        Toast.makeText(requireContext(), R.string.manager_rename_failed, Toast.LENGTH_SHORT).show();
                    }
                    mAdapter.notifyDataSetChanged();
                    updateSummaryText();
                });
            }
        });
    }

    @Override
    public void onDelete(ManagedItem item) {
        if (item.type == ManagedItem.TYPE_MODPACK) {
            if (LauncherProfiles.mainProfileJson.profiles.size() <= 1) {
                Toast.makeText(requireContext(), R.string.manager_profile_cannot_delete_last, Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.manager_delete_confirm_title)
                    .setMessage(getString(R.string.manager_delete_confirm_msg, item.name))
                    .setPositiveButton(R.string.global_delete, (d, w) -> {
                        String currentActiveKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                        boolean wasActive = item.profileKey != null && item.profileKey.equals(currentActiveKey);

                        ProfileIconCache.dropIcon(item.profileKey);
                        LauncherProfiles.mainProfileJson.profiles.remove(item.profileKey);

                        if (wasActive && !LauncherProfiles.mainProfileJson.profiles.isEmpty()) {
                            String nextKey = LauncherProfiles.mainProfileJson.profiles.keySet().iterator().next();
                            LauncherPreferences.DEFAULT_PREF.edit()
                                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, nextKey)
                                    .apply();
                            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, ProfileEditorFragment.DELETED_PROFILE);
                        }
                        LauncherProfiles.write();
                        initProfiles();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else if (item.file != null) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.manager_delete_confirm_title)
                    .setMessage(getString(R.string.manager_delete_confirm_msg, item.name))
                    .setPositiveButton(R.string.global_delete, (d, w) -> {
                        PojavApplication.sExecutorService.execute(() -> {
                            deleteRecursive(item.file);
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(this::loadContent);
                            }
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    @Override
    public void onAction(ManagedItem item) {
        if (item.type == ManagedItem.TYPE_MODPACK && item.profileKey != null) {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, item.profileKey)
                    .apply();
            Toast.makeText(requireContext(), getString(R.string.filter_target_profile, item.name), Toast.LENGTH_SHORT).show();
            initProfiles();
        }
    }

    private void openDownloadScreen() {
        Bundle bundle = new Bundle();
        if (mCurrentProfileKey != null) {
            bundle.putString("profile_key", mCurrentProfileKey);
        }
        if (mCurrentCategory == ManagedItem.TYPE_MODPACK) {
            bundle.putBoolean("is_modpack", true);
        } else {
            String typeStr = SearchFilters.TYPE_MOD;
            if (mCurrentCategory == ManagedItem.TYPE_RESOURCEPACK) typeStr = SearchFilters.TYPE_RESOURCEPACK;
            else if (mCurrentCategory == ManagedItem.TYPE_SHADER) typeStr = SearchFilters.TYPE_SHADER;
            else if (mCurrentCategory == ManagedItem.TYPE_MAP) typeStr = SearchFilters.TYPE_MAP;
            bundle.putString("initial_type", typeStr);
        }
        Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, bundle);
    }

    private void openCurrentFolder() {
        if (mCurrentCategory == ManagedItem.TYPE_MODPACK) {
            Tools.openPath(requireContext(), new File(Tools.DIR_GAME_HOME), false);
        } else if (mCurrentProfile != null) {
            File gameDir = Tools.getGameDirPath(mCurrentProfile);
            File targetDir = getTargetDirectory(gameDir, mCurrentCategory);
            Tools.openPath(requireContext(), targetDir, false);
        }
    }

    private void onFilesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || mCurrentProfile == null) return;

        File gameDir = Tools.getGameDirPath(mCurrentProfile);
        File targetDir = getTargetDirectory(gameDir, mCurrentCategory);
        if (!targetDir.exists()) targetDir.mkdirs();

        PojavApplication.sExecutorService.execute(() -> {
            int importedCount = 0;
            String lastImported = "";

            for (Uri uri : uris) {
                String rawName = getFileNameFromUri(requireContext(), uri);
                String safeName = ContentInstallUtils.sanitizeFileName(rawName);
                if (safeName.isEmpty()) {
                    safeName = "imported_" + System.currentTimeMillis();
                }

                File destFile = getUniqueFile(targetDir, safeName);
                try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(destFile)) {
                    if (is != null) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) != -1) {
                            os.write(buf, 0, len);
                        }

                        if (mCurrentCategory == ManagedItem.TYPE_MAP && safeName.toLowerCase().endsWith(".zip")) {
                            try {
                                String unpackedName = ContentInstallUtils.unpackSaveZip(destFile, targetDir, safeName);
                                destFile.delete();
                                importedCount++;
                                lastImported = unpackedName;
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                destFile.delete();
                            }
                        } else {
                            importedCount++;
                            lastImported = destFile.getName();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if (destFile.exists()) {
                        destFile.delete();
                    }
                }
            }

            final int count = importedCount;
            final String lastName = lastImported;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (count > 0) {
                        Toast.makeText(requireContext(), getString(R.string.manager_import_success, lastName), Toast.LENGTH_SHORT).show();
                        loadContent();
                    } else {
                        Toast.makeText(requireContext(), R.string.manager_import_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private File getUniqueFile(File parentDir, String fileName) {
        File file = new File(parentDir, fileName);
        if (!file.exists()) return file;

        String name = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            name = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int counter = 1;
        while (file.exists()) {
            file = new File(parentDir, name + " (" + counter + ")" + extension);
            counter++;
        }
        return file;
    }

    private String getFileNameFromUri(Context context, Uri uri) {
        String name = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIdx >= 0) {
                        name = cursor.getString(nameIdx);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) return;
        try {
            if (Files.isSymbolicLink(fileOrDirectory.toPath())) {
                fileOrDirectory.delete();
                return;
            }
        } catch (Exception ignored) {}

        if (fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }
}
