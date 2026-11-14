package net.kdt.pojavlaunch.fragments;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.MineButton;

import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.LauncherProfiles;
import net.kdt.pojavlaunch.servers.ServerData;
import net.kdt.pojavlaunch.servers.ServerListAdapter;
import net.kdt.pojavlaunch.servers.ServerPinger;
import net.kdt.pojavlaunch.servers.ServersDatUtils;
import net.kdt.pojavlaunch.value.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ServerManagerFragment extends Fragment implements ServerListAdapter.ServerActionCallback {
    public static final String TAG = "ServerManagerFragment";

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
    private TextView mSummaryText;
    private ImageButton mBtnRefresh;
    private ImageButton mBtnAdd;
    private ProgressBar mProgressBar;

    private RecyclerView mRecyclerView;
    private ServerListAdapter mAdapter;
    private View mEmptyContainer;
    private MineButton mEmptyAddBtn;

    private MinecraftProfile mCurrentProfile;
    private File mServersDatFile;
    private final List<ServerData> mServerList = new ArrayList<>();

    public ServerManagerFragment() {
        super(R.layout.fragment_server_manager);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mOverlay = view.findViewById(R.id.server_manager_overlay);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mBackButton = view.findViewById(R.id.server_manager_back);
        mSummaryText = view.findViewById(R.id.server_manager_summary);
        mBtnRefresh = view.findViewById(R.id.server_manager_btn_refresh);
        mBtnAdd = view.findViewById(R.id.server_manager_btn_add);
        mProgressBar = view.findViewById(R.id.server_manager_progressbar);

        mRecyclerView = view.findViewById(R.id.server_manager_recycler_view);
        mEmptyContainer = view.findViewById(R.id.server_manager_empty_container);
        mEmptyAddBtn = view.findViewById(R.id.server_manager_empty_add_btn);

        mAdapter = new ServerListAdapter(this);
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

        mBtnAdd.setOnClickListener(v -> showAddOrEditDialog(null));
        if (mEmptyAddBtn != null) {
            mEmptyAddBtn.setOnClickListener(v -> showAddOrEditDialog(null));
        }

        mBtnRefresh.setOnClickListener(v -> pingAllServers());

        mOverlay.post(() -> {
            int overlayHeight = mOverlay.getHeight();
            mRecyclerView.setPadding(mRecyclerView.getPaddingLeft(),
                    overlayHeight + (int) getResources().getDimension(R.dimen._4sdp),
                    mRecyclerView.getPaddingRight(),
                    mRecyclerView.getPaddingBottom());
        });

        initProfileAndFiles();
        loadServers();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRecyclerView.removeOnScrollListener(mOverlayPositionListener);
    }

    private void initProfileAndFiles() {
        LauncherProfiles.load();
        String currentKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        if (LauncherProfiles.mainProfileJson != null && LauncherProfiles.mainProfileJson.profiles != null) {
            mCurrentProfile = LauncherProfiles.mainProfileJson.profiles.get(currentKey);
        }
        if (mCurrentProfile == null) {
            mCurrentProfile = LauncherProfiles.getCurrentProfile();
        }

        File gameDir = (mCurrentProfile != null) ? Tools.getGameDirPath(mCurrentProfile) : new File(Tools.DIR_GAME_NEW);
        mServersDatFile = new File(gameDir, "servers.dat");
    }

    private void loadServers() {
        if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);

        PojavApplication.sExecutorService.execute(() -> {
            List<ServerData> loaded = ServersDatUtils.loadServers(mServersDatFile);
            mServerList.clear();
            mServerList.addAll(loaded);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
                mAdapter.setServers(mServerList);
                updateSummaryAndEmptyState();
                pingAllServers();
            });
        });
    }

    private void pingAllServers() {
        for (ServerData server : mServerList) {
            server.status = ServerData.STATUS_PINGING;
        }
        mAdapter.notifyDataSetChanged();

        for (int i = 0; i < mServerList.size(); i++) {
            final int pos = i;
            final ServerData server = mServerList.get(i);
            final String oldIcon = server.icon;

            ServerPinger.ping(server, (s, success) -> {
                if (getActivity() == null) return;
                mAdapter.notifyItemChanged(pos);

                // If remote server gave us a new favicon, save it to servers.dat
                if (s.icon != null && !s.icon.equals(oldIcon)) {
                    saveServersToFile();
                }
            });
        }
    }

    private void saveServersToFile() {
        PojavApplication.sExecutorService.execute(() -> {
            ServersDatUtils.saveServers(mServersDatFile, mServerList);
        });
    }

    private void updateSummaryAndEmptyState() {
        int count = mServerList.size();
        if (mSummaryText != null) {
            mSummaryText.setText(getString(R.string.server_manager_summary, count));
        }
        if (mEmptyContainer != null) {
            mEmptyContainer.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onPlay(ServerData server) {
        String host = server.getHost();
        int port = server.getPort();
        if (host.isEmpty()) {
            Toast.makeText(requireContext(), R.string.server_status_offline, Toast.LENGTH_SHORT).show();
            return;
        }

        // Direct connect via client arguments
        Tools.sDirectConnectServer = host;
        Tools.sDirectConnectPort = port;

        if (getActivity() != null) {
            getActivity().onBackPressed();
        }
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    @Override
    public void onEdit(ServerData server) {
        showAddOrEditDialog(server);
    }

    @Override
    public void onDelete(ServerData server) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.server_manager_delete)
                .setMessage(getString(R.string.manager_delete_confirm_msg, server.name != null ? server.name : server.ip))
                .setPositiveButton(R.string.global_delete, (d, w) -> {
                    mServerList.remove(server);
                    saveServersToFile();
                    mAdapter.setServers(mServerList);
                    updateSummaryAndEmptyState();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddOrEditDialog(@Nullable ServerData serverToEdit) {
        Context context = requireContext();
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_server_edit);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvTitle = dialog.findViewById(R.id.dialog_server_title);
        EditText etName = dialog.findViewById(R.id.dialog_server_name);
        EditText etIp = dialog.findViewById(R.id.dialog_server_ip);
        MineButton btnCancel = dialog.findViewById(R.id.dialog_server_cancel);
        MineButton btnSave = dialog.findViewById(R.id.dialog_server_save);

        if (serverToEdit != null) {
            tvTitle.setText(R.string.server_manager_edit);
            etName.setText(serverToEdit.name != null ? serverToEdit.name : "");
            etIp.setText(serverToEdit.ip != null ? serverToEdit.ip : "");
        } else {
            tvTitle.setText(R.string.server_manager_add);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            if (ip.isEmpty()) {
                etIp.setError(getString(R.string.global_error));
                return;
            }

            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                name = ip;
            }

            if (serverToEdit != null) {
                serverToEdit.name = name;
                serverToEdit.ip = ip;
                serverToEdit.status = ServerData.STATUS_PINGING;
                saveServersToFile();
                mAdapter.notifyDataSetChanged();
                ServerPinger.ping(serverToEdit, (s, success) -> {
                    if (getActivity() != null) {
                        mAdapter.notifyDataSetChanged();
                    }
                });
            } else {
                ServerData newServer = new ServerData(name, ip);
                newServer.status = ServerData.STATUS_PINGING;
                mServerList.add(newServer);
                saveServersToFile();
                mAdapter.setServers(mServerList);
                updateSummaryAndEmptyState();

                final int newPos = mServerList.size() - 1;
                ServerPinger.ping(newServer, (s, success) -> {
                    if (getActivity() != null) {
                        mAdapter.notifyItemChanged(newPos);
                        if (s.icon != null) {
                            saveServersToFile();
                        }
                    }
                });
            }

            dialog.dismiss();
        });

        dialog.show();
    }
}
