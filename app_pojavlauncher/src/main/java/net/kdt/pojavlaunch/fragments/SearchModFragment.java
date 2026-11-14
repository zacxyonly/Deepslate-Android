package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.MineButton;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ManagedItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchModFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "SearchModFragment";
    private View mOverlay;
    private float mOverlayTopCache;

    private final RecyclerView.OnScrollListener mOverlayPositionListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            mOverlay.setY(MathUtils.clamp(mOverlay.getY() - dy, -mOverlay.getHeight(), mOverlayTopCache));
        }
    };

    private ImageButton mBackButton;
    private EditText mSearchEditText;
    private ImageButton mClearSearchButton;
    private ImageButton mFilterButton;
    private MineButton mBtnMods;
    private MineButton mBtnResourcePacks;
    private MineButton mBtnShaders;
    private MineButton mBtnModpacks;

    private View mChipVersion;
    private TextView mChipVersionText;
    private ImageView mChipVersionClear;

    private View mChipLoader;
    private TextView mChipLoaderText;
    private ImageView mChipLoaderClear;

    private TextView mTargetInfoText;
    private MinecraftProfile mTargetProfile;

    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mSearchProgressBar;
    private View mEmptyContainer;
    private TextView mStatusTextView;
    private Button mBtnSearchAllVersions;
    private Button mBtnResetFilters;

    private ModpackApi modpackApi;
    private final SearchFilters mSearchFilters;

    public SearchModFragment() {
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
        mSearchFilters.setProjectType(SearchFilters.TYPE_MOD);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        modpackApi = new CommonApi(context.getString(R.string.curseforge_api_key));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        initFiltersFromProfile();
        if (getArguments() != null) {
            if (getArguments().getBoolean("is_modpack", false)) {
                mSearchFilters.setProjectType(SearchFilters.TYPE_MODPACK);
            } else if (getArguments().containsKey("initial_type")) {
                mSearchFilters.setProjectType(getArguments().getString("initial_type"));
            }
        }

        mModItemAdapter = new ModItemAdapter(getResources(), modpackApi, this);
        mModItemAdapter.setTargetProfile(mTargetProfile);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay = view.findViewById(R.id.search_mod_overlay);
        mBackButton = view.findViewById(R.id.search_mod_back);
        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mClearSearchButton = view.findViewById(R.id.search_mod_clear_btn);
        ImageButton mManageButton = view.findViewById(R.id.search_mod_manage);
        mFilterButton = view.findViewById(R.id.search_mod_filter);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.search_mod_list);
        mEmptyContainer = view.findViewById(R.id.search_mod_empty_container);
        mStatusTextView = view.findViewById(R.id.search_mod_status_text);
        mBtnSearchAllVersions = view.findViewById(R.id.search_mod_btn_search_all_versions);
        mBtnResetFilters = view.findViewById(R.id.search_mod_btn_reset_filters);

        mTargetInfoText = view.findViewById(R.id.search_mod_target_info);

        mBtnMods = view.findViewById(R.id.category_mods_button);
        mBtnResourcePacks = view.findViewById(R.id.category_resourcepacks_button);
        mBtnShaders = view.findViewById(R.id.category_shaders_button);
        mBtnModpacks = view.findViewById(R.id.category_modpacks_button);

        mChipVersion = view.findViewById(R.id.search_mod_chip_version);
        mChipVersionText = view.findViewById(R.id.search_mod_chip_version_text);
        mChipVersionClear = view.findViewById(R.id.search_mod_chip_version_clear);

        mChipLoader = view.findViewById(R.id.search_mod_chip_loader);
        mChipLoaderText = view.findViewById(R.id.search_mod_chip_loader_text);
        mChipLoaderClear = view.findViewById(R.id.search_mod_chip_loader_clear);

        mRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerview.setAdapter(mModItemAdapter);
        mRecyclerview.addOnScrollListener(mOverlayPositionListener);

        if (mBackButton != null) {
            mBackButton.setOnClickListener(v -> {
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                }
            });
        }

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mClearSearchButton != null) {
                    mClearSearchButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        if (mClearSearchButton != null) {
            mClearSearchButton.setOnClickListener(v -> {
                mSearchEditText.setText("");
                searchMods("");
            });
        }

        mBtnMods.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_MOD));
        mBtnResourcePacks.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_RESOURCEPACK));
        mBtnShaders.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_SHADER));
        mBtnModpacks.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_MODPACK));

        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        if (mManageButton != null) {
            mManageButton.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                if (SearchFilters.TYPE_MODPACK.equals(mSearchFilters.projectType)) {
                    bundle.putBoolean("is_modpack", true);
                } else if (SearchFilters.TYPE_RESOURCEPACK.equals(mSearchFilters.projectType)) {
                    bundle.putInt("category", ManagedItem.TYPE_RESOURCEPACK);
                } else if (SearchFilters.TYPE_SHADER.equals(mSearchFilters.projectType)) {
                    bundle.putInt("category", ManagedItem.TYPE_SHADER);
                } else {
                    bundle.putInt("category", ManagedItem.TYPE_MOD);
                }
                if (getArguments() != null && getArguments().containsKey("profile_key")) {
                    bundle.putString("profile_key", getArguments().getString("profile_key"));
                }
                Tools.swapFragment(requireActivity(), ContentManagerFragment.class, ContentManagerFragment.TAG, bundle);
            });
        }

        // Chip click listeners
        if (mChipVersion != null) {
            mChipVersion.setOnClickListener(v -> {
                VersionSelectorDialog.open(v.getContext(), true, (id, snapshot) -> {
                    mSearchFilters.mcVersion = id;
                    updateFilterChips();
                    searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
                });
            });
        }

        if (mChipVersionClear != null) {
            mChipVersionClear.setOnClickListener(v -> {
                mSearchFilters.mcVersion = null;
                updateFilterChips();
                searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
            });
        }

        if (mChipLoader != null) {
            mChipLoader.setOnClickListener(v -> displayFilterDialog());
        }

        if (mChipLoaderClear != null) {
            mChipLoaderClear.setOnClickListener(v -> {
                mSearchFilters.modLoader = null;
                updateFilterChips();
                searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
            });
        }

        if (mBtnSearchAllVersions != null) {
            mBtnSearchAllVersions.setOnClickListener(v -> {
                mSearchFilters.mcVersion = null;
                updateFilterChips();
                searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
            });
        }

        if (mBtnResetFilters != null) {
            mBtnResetFilters.setOnClickListener(v -> {
                mSearchFilters.mcVersion = null;
                mSearchFilters.modLoader = null;
                updateFilterChips();
                searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
            });
        }

        mOverlay.post(() -> {
            int overlayHeight = mOverlay.getHeight();
            mRecyclerview.setPadding(mRecyclerview.getPaddingLeft(),
                    overlayHeight + (int) getResources().getDimension(R.dimen._4sdp),
                    mRecyclerview.getPaddingRight(),
                    mRecyclerview.getPaddingBottom());
        });

        updateCategoryButtonStyles();
        updateFilterChips();
        searchMods(null);
    }

    private void initFiltersFromProfile() {
        try {
            mTargetProfile = null;
            if (getArguments() != null && getArguments().containsKey("profile_key")) {
                String key = getArguments().getString("profile_key");
                LauncherProfiles.load();
                if (LauncherProfiles.mainProfileJson != null && LauncherProfiles.mainProfileJson.profiles != null) {
                    mTargetProfile = LauncherProfiles.mainProfileJson.profiles.get(key);
                }
            }
            if (mTargetProfile == null) {
                mTargetProfile = LauncherProfiles.getCurrentProfile();
            }
            if (mModItemAdapter != null) {
                mModItemAdapter.setTargetProfile(mTargetProfile);
            }
            if (mTargetProfile != null && mTargetProfile.lastVersionId != null) {
                String id = mTargetProfile.lastVersionId;
                String ver = id.toLowerCase();
                if (ver.contains("fabric")) mSearchFilters.modLoader = "fabric";
                else if (ver.contains("neoforge")) mSearchFilters.modLoader = "neoforge";
                else if (ver.contains("forge")) mSearchFilters.modLoader = "forge";
                else if (ver.contains("quilt")) mSearchFilters.modLoader = "quilt";
                else mSearchFilters.modLoader = null;

                String detectedMcVer = null;
                // 1. Try to read inheritsFrom from version json on disk
                try {
                    File jsonFile = new File(Tools.DIR_HOME_VERSION + "/" + id + "/" + id + ".json");
                    if (jsonFile.exists() && jsonFile.canRead()) {
                        JMinecraftVersionList.Version jsonVer = Tools.GLOBAL_GSON.fromJson(
                                Tools.read(jsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);
                        if (jsonVer != null && Tools.isValidString(jsonVer.inheritsFrom)) {
                            detectedMcVer = jsonVer.inheritsFrom;
                        }
                    }
                } catch (Exception ignored) {}

                // 2. Fallback pattern matching on version string
                if (detectedMcVer == null) {
                    if (id.startsWith("fabric-loader-") || id.startsWith("quilt-loader-")) {
                        // Formats: fabric-loader-<loaderVersion>-<mcVersion>
                        // e.g. fabric-loader-0.20.1-26.3 -> 26.3
                        // e.g. fabric-loader-0.16.9-1.21.1 -> 1.21.1
                        int lastDash = id.lastIndexOf('-');
                        if (lastDash > 0 && lastDash < id.length() - 1) {
                            detectedMcVer = id.substring(lastDash + 1);
                        }
                    } else if (id.startsWith("neoforge-")) {
                        // Formats: neoforge-21.1.77 or neoforge-20.4.80-beta or neoforge-1.20.4-...
                        String remainder = id.substring("neoforge-".length());
                        if (remainder.startsWith("1.")) {
                            Matcher m = Pattern.compile("^(1\\.\\d+(\\.\\d+)?)").matcher(remainder);
                            if (m.find()) detectedMcVer = m.group(1);
                        } else {
                            Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(remainder);
                            if (m.find()) {
                                int major = Integer.parseInt(m.group(1));
                                int minor = Integer.parseInt(m.group(2));
                                if (major >= 20) {
                                    if (minor == 0) detectedMcVer = "1." + major;
                                    else detectedMcVer = "1." + major + "." + minor;
                                }
                            }
                        }
                    } else if (ver.contains("forge-")) {
                        // Formats: 1.20.1-forge-47.2.0 or forge-1.20.1-47.2.0
                        Matcher m = Pattern.compile("(1\\.\\d+(\\.\\d+)?)").matcher(id);
                        if (m.find()) {
                            detectedMcVer = m.group(1);
                        }
                    }
                }

                // 3. Fallback: if id contains 1.x.x, extract it; otherwise use the whole id (e.g. snapshots)
                if (detectedMcVer == null) {
                    Matcher m = Pattern.compile("(1\\.\\d+(\\.\\d+)?)").matcher(id);
                    if (m.find()) {
                        detectedMcVer = m.group(1);
                    } else {
                        detectedMcVer = id;
                    }
                }

                mSearchFilters.mcVersion = detectedMcVer;
            }
        } catch (Exception ignored) {}
    }

    private void selectCategory(String type) {
        mSearchFilters.setProjectType(type);
        updateCategoryButtonStyles();
        updateFilterChips();
        searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
    }

    private void updateCategoryButtonStyles() {
        String currentType = mSearchFilters.projectType;
        updateTabButton(mBtnMods, SearchFilters.TYPE_MOD.equals(currentType));
        updateTabButton(mBtnResourcePacks, SearchFilters.TYPE_RESOURCEPACK.equals(currentType));
        updateTabButton(mBtnShaders, SearchFilters.TYPE_SHADER.equals(currentType));
        updateTabButton(mBtnModpacks, SearchFilters.TYPE_MODPACK.equals(currentType));
    }

    private void updateTabButton(MineButton button, boolean isSelected) {
        if (button == null) return;
        button.setSelected(isSelected);
        if (getContext() != null) {
            button.setTextColor(ContextCompat.getColor(getContext(),
                    isSelected ? R.color.minebutton_color : R.color.primary_text));
        }
    }

    private void updateFilterChips() {
        // Version Chip
        if (mChipVersionText != null) {
            if (mSearchFilters.mcVersion != null && !mSearchFilters.mcVersion.trim().isEmpty()) {
                mChipVersionText.setText(getString(R.string.search_mod_filter_version_chip, mSearchFilters.mcVersion));
                if (mChipVersionClear != null) mChipVersionClear.setVisibility(View.VISIBLE);
            } else {
                mChipVersionText.setText(R.string.search_mod_all_versions);
                if (mChipVersionClear != null) mChipVersionClear.setVisibility(View.GONE);
            }
        }

        // Loader Chip (only shown when in Mods category)
        boolean isModCategory = SearchFilters.TYPE_MOD.equals(mSearchFilters.projectType);
        if (mChipLoader != null) {
            mChipLoader.setVisibility(isModCategory ? View.VISIBLE : View.GONE);
            if (isModCategory && mChipLoaderText != null) {
                if (mSearchFilters.modLoader != null && !mSearchFilters.modLoader.trim().isEmpty()) {
                    mChipLoaderText.setText(getString(R.string.search_mod_filter_loader_chip, mSearchFilters.modLoader));
                    if (mChipLoaderClear != null) mChipLoaderClear.setVisibility(View.VISIBLE);
                } else {
                    mChipLoaderText.setText(R.string.search_mod_all_loaders);
                    if (mChipLoaderClear != null) mChipLoaderClear.setVisibility(View.GONE);
                }
            }
        }

        // Target profile info
        if (mTargetInfoText != null) {
            try {
                if (mTargetProfile != null && mTargetProfile.name != null) {
                    mTargetInfoText.setText(getString(R.string.filter_target_profile, mTargetProfile.name));
                } else {
                    mTargetInfoText.setText("");
                }
            } catch (Exception ignored) {
                mTargetInfoText.setText("");
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
        mRecyclerview.removeOnScrollListener(mOverlayPositionListener);
    }

    @Override
    public void onSearchFinished() {
        mSearchProgressBar.setVisibility(View.GONE);
        if (mEmptyContainer != null) mEmptyContainer.setVisibility(View.GONE);
        if (mStatusTextView != null) mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mSearchProgressBar.setVisibility(View.GONE);
        if (error == ERROR_NO_RESULTS) {
            if (mEmptyContainer != null) {
                mEmptyContainer.setVisibility(View.VISIBLE);
                if (mBtnSearchAllVersions != null) {
                    mBtnSearchAllVersions.setVisibility(mSearchFilters.mcVersion != null ? View.VISIBLE : View.GONE);
                }
            }
            if (mStatusTextView != null) mStatusTextView.setVisibility(View.GONE);
        } else {
            if (mEmptyContainer != null) mEmptyContainer.setVisibility(View.GONE);
            if (mStatusTextView != null) {
                mStatusTextView.setVisibility(View.VISIBLE);
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_modpack_error);
            }
        }
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
        if (mEmptyContainer != null) mEmptyContainer.setVisibility(View.GONE);
        if (mStatusTextView != null) mStatusTextView.setVisibility(View.GONE);
        mSearchFilters.name = name == null ? "" : name;
        mModItemAdapter.performSearchQuery(mSearchFilters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button mClearVersionButton = dialog.findViewById(R.id.search_mod_mc_version_clear_button);
            Spinner mLoaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);
            Button mMatchProfileButton = dialog.findViewById(R.id.search_mod_match_profile_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);

            assert mSelectVersionButton != null;
            assert mSelectedVersion != null;
            assert mLoaderSpinner != null;
            assert mApplyButton != null;

            if (mClearVersionButton != null) {
                mClearVersionButton.setOnClickListener(v -> mSelectedVersion.setText(""));
            }

            List<String> loaders = new ArrayList<>();
            loaders.add("all");
            loaders.add("fabric");
            loaders.add("forge");
            loaders.add("neoforge");
            loaders.add("quilt");

            ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<String>(requireContext(),
                    R.layout.item_simple_list_1, loaders) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull android.view.ViewGroup parent) {
                    View v = super.getView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(ContextCompat.getColor(parent.getContext(), R.color.primary_text));
                    }
                    return v;
                }

                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull android.view.ViewGroup parent) {
                    View v = super.getDropDownView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(ContextCompat.getColor(parent.getContext(), R.color.primary_text));
                        v.setBackgroundColor(ContextCompat.getColor(parent.getContext(), R.color.background_overlay));
                    }
                    return v;
                }
            };
            spinnerAdapter.setDropDownViewResource(R.layout.item_simple_list_1);
            mLoaderSpinner.setAdapter(spinnerAdapter);

            if (mSearchFilters.modLoader != null) {
                int idx = loaders.indexOf(mSearchFilters.modLoader.toLowerCase());
                if (idx >= 0) mLoaderSpinner.setSelection(idx);
            }

            mSelectedVersion.setText(mSearchFilters.mcVersion != null ? mSearchFilters.mcVersion : "");

            mSelectVersionButton.setOnClickListener(v -> VersionSelectorDialog.open(v.getContext(), true, (id, snapshot) -> mSelectedVersion.setText(id)));

            if (mMatchProfileButton != null) {
                mMatchProfileButton.setOnClickListener(v -> {
                    initFiltersFromProfile();
                    mSelectedVersion.setText(mSearchFilters.mcVersion != null ? mSearchFilters.mcVersion : "");
                    if (mSearchFilters.modLoader != null) {
                        int idx = loaders.indexOf(mSearchFilters.modLoader.toLowerCase());
                        if (idx >= 0) mLoaderSpinner.setSelection(idx);
                    } else {
                        mLoaderSpinner.setSelection(0);
                    }
                });
            }

            mApplyButton.setOnClickListener(v -> {
                String verText = mSelectedVersion.getText().toString().trim();
                mSearchFilters.mcVersion = verText.isEmpty() ? null : verText;

                String selectedLoader = (String) mLoaderSpinner.getSelectedItem();
                mSearchFilters.modLoader = "all".equalsIgnoreCase(selectedLoader) ? null : selectedLoader;

                updateFilterChips();
                searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }
}
