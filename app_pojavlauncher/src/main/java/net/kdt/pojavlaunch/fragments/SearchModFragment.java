package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

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
    private ImageButton mFilterButton;
    private Button mBtnMods;
    private Button mBtnResourcePacks;
    private Button mBtnShaders;
    private Button mBtnModpacks;
    private TextView mTargetInfoText;

    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mSearchProgressBar;
    private TextView mStatusTextView;
    private ColorStateList mDefaultTextColor;

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
        if (getArguments() != null && getArguments().getBoolean("is_modpack", false)) {
            mSearchFilters.setProjectType(SearchFilters.TYPE_MODPACK);
        }

        mModItemAdapter = new ModItemAdapter(getResources(), modpackApi, this);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay = view.findViewById(R.id.search_mod_overlay);
        mBackButton = view.findViewById(R.id.search_mod_back);
        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mFilterButton = view.findViewById(R.id.search_mod_filter);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.search_mod_list);
        mStatusTextView = view.findViewById(R.id.search_mod_status_text);
        mTargetInfoText = view.findViewById(R.id.search_mod_target_info);

        mBtnMods = view.findViewById(R.id.category_mods_button);
        mBtnResourcePacks = view.findViewById(R.id.category_resourcepacks_button);
        mBtnShaders = view.findViewById(R.id.category_shaders_button);
        mBtnModpacks = view.findViewById(R.id.category_modpacks_button);

        mDefaultTextColor = mStatusTextView.getTextColors();

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

        mBtnMods.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_MOD));
        mBtnResourcePacks.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_RESOURCEPACK));
        mBtnShaders.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_SHADER));
        mBtnModpacks.setOnClickListener(v -> selectCategory(SearchFilters.TYPE_MODPACK));

        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        mOverlay.post(() -> {
            int overlayHeight = mOverlay.getHeight();
            mRecyclerview.setPadding(mRecyclerview.getPaddingLeft(),
                    mRecyclerview.getPaddingTop() + overlayHeight,
                    mRecyclerview.getPaddingRight(),
                    mRecyclerview.getPaddingBottom());
        });

        updateCategoryButtonStyles();
        updateTargetInfo();
        searchMods(null);
    }

    private void initFiltersFromProfile() {
        try {
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            if (profile != null && profile.lastVersionId != null) {
                String ver = profile.lastVersionId.toLowerCase();
                if (ver.contains("fabric")) mSearchFilters.modLoader = "fabric";
                else if (ver.contains("neoforge")) mSearchFilters.modLoader = "neoforge";
                else if (ver.contains("forge")) mSearchFilters.modLoader = "forge";
                else if (ver.contains("quilt")) mSearchFilters.modLoader = "quilt";

                Matcher matcher = Pattern.compile("(\\d+\\.\\d+(\\.\\d+)?)").matcher(profile.lastVersionId);
                if (matcher.find()) {
                    mSearchFilters.mcVersion = matcher.group(1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void selectCategory(String type) {
        mSearchFilters.setProjectType(type);
        updateCategoryButtonStyles();
        updateTargetInfo();
        searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
    }

    private void updateCategoryButtonStyles() {
        String currentType = mSearchFilters.projectType;
        if (mBtnMods != null) mBtnMods.setSelected(SearchFilters.TYPE_MOD.equals(currentType));
        if (mBtnResourcePacks != null) mBtnResourcePacks.setSelected(SearchFilters.TYPE_RESOURCEPACK.equals(currentType));
        if (mBtnShaders != null) mBtnShaders.setSelected(SearchFilters.TYPE_SHADER.equals(currentType));
        if (mBtnModpacks != null) mBtnModpacks.setSelected(SearchFilters.TYPE_MODPACK.equals(currentType));
    }

    private void updateTargetInfo() {
        if (mTargetInfoText == null) return;
        StringBuilder sb = new StringBuilder();
        try {
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            if (profile != null && profile.name != null) {
                sb.append("Profile: ").append(profile.name);
            }
        } catch (Exception ignored) {}

        if (mSearchFilters.mcVersion != null && !mSearchFilters.mcVersion.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("MC: ").append(mSearchFilters.mcVersion);
        }
        if (mSearchFilters.modLoader != null && !mSearchFilters.modLoader.isEmpty() && SearchFilters.TYPE_MOD.equals(mSearchFilters.projectType)) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("Loader: ").append(mSearchFilters.modLoader);
        }
        mTargetInfoText.setText(sb.toString());
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
        mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_modpack_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_modpack_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
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
            Spinner mLoaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);
            Button mMatchProfileButton = dialog.findViewById(R.id.search_mod_match_profile_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);

            assert mSelectVersionButton != null;
            assert mSelectedVersion != null;
            assert mLoaderSpinner != null;
            assert mApplyButton != null;

            List<String> loaders = new ArrayList<>();
            loaders.add("all");
            loaders.add("fabric");
            loaders.add("forge");
            loaders.add("neoforge");
            loaders.add("quilt");

            ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, loaders);
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

                updateTargetInfo();
                searchMods(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }
}
