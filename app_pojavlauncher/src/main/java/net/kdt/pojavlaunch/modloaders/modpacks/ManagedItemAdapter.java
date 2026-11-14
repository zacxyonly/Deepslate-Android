package net.kdt.pojavlaunch.modloaders.modpacks;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.MineButton;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ManagedItem;

import java.util.ArrayList;
import java.util.List;

public class ManagedItemAdapter extends RecyclerView.Adapter<ManagedItemAdapter.ViewHolder> {

    public interface ItemActionCallback {
        void onToggle(ManagedItem item, boolean isEnabled);
        void onDelete(ManagedItem item);
        void onAction(ManagedItem item);
    }

    private final List<ManagedItem> mAllItems = new ArrayList<>();
    private final List<ManagedItem> mFilteredItems = new ArrayList<>();
    private final ItemActionCallback mCallback;
    private String mCurrentQuery = "";

    public ManagedItemAdapter(ItemActionCallback callback) {
        this.mCallback = callback;
    }

    public void setItems(List<ManagedItem> items) {
        mAllItems.clear();
        if (items != null) {
            mAllItems.addAll(items);
        }
        applyFilter(mCurrentQuery);
    }

    public void applyFilter(String query) {
        mCurrentQuery = query != null ? query.trim().toLowerCase() : "";
        mFilteredItems.clear();
        if (mCurrentQuery.isEmpty()) {
            mFilteredItems.addAll(mAllItems);
        } else {
            for (ManagedItem item : mAllItems) {
                boolean matchName = item.name != null && item.name.toLowerCase().contains(mCurrentQuery);
                boolean matchFile = item.fileName != null && item.fileName.toLowerCase().contains(mCurrentQuery);
                boolean matchDesc = item.description != null && item.description.toLowerCase().contains(mCurrentQuery);
                if (matchName || matchFile || matchDesc) {
                    mFilteredItems.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    public int getAllCount() {
        return mAllItems.size();
    }

    public int getEnabledCount() {
        int count = 0;
        for (ManagedItem item : mAllItems) {
            if (item.isEnabled) count++;
        }
        return count;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_managed_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ManagedItem item = mFilteredItems.get(position);

        holder.mTitle.setText(item.name);

        if (item.type == ManagedItem.TYPE_MODPACK) {
            String ver = item.version != null ? item.version : "-";
            holder.mMeta.setText(holder.itemView.getContext().getString(R.string.profiles_profile_version) + ": " + ver);
            holder.mDesc.setText(item.fileName != null ? item.fileName : "");
        } else {
            String sizeStr = ContentMetadataUtils.formatFileSize(item.fileSize);
            String statusStr = item.isEnabled ?
                    holder.itemView.getContext().getString(R.string.manager_status_enabled) :
                    holder.itemView.getContext().getString(R.string.manager_status_disabled);
            String metaText = (item.version != null ? "v" + item.version + " • " : "") + sizeStr + " • " + statusStr;
            holder.mMeta.setText(metaText);

            if (item.description != null && !item.description.trim().isEmpty()) {
                holder.mDesc.setText(item.description);
            } else {
                holder.mDesc.setText(item.fileName);
            }
        }

        // Icon
        if (item.iconBitmap != null && !item.iconBitmap.isRecycled()) {
            holder.mIcon.setImageBitmap(item.iconBitmap);
        } else {
            if (item.type == ManagedItem.TYPE_MOD) {
                holder.mIcon.setImageResource(R.drawable.ic_menu_install_jar);
            } else if (item.type == ManagedItem.TYPE_RESOURCEPACK) {
                holder.mIcon.setImageResource(R.drawable.ic_folder);
            } else if (item.type == ManagedItem.TYPE_SHADER) {
                holder.mIcon.setImageResource(R.drawable.ic_setting_engine);
            } else {
                holder.mIcon.setImageResource(R.mipmap.ic_launcher);
            }
        }

        // Action controls
        if (item.type == ManagedItem.TYPE_MODPACK) {
            holder.mSwitch.setVisibility(View.GONE);
            holder.mBtnAction.setVisibility(View.VISIBLE);
            if (item.isActiveProfile) {
                holder.mBtnAction.setText(R.string.manager_profile_active);
                holder.mBtnAction.setEnabled(false);
            } else {
                holder.mBtnAction.setText(R.string.manager_profile_set_active);
                holder.mBtnAction.setEnabled(true);
                holder.mBtnAction.setOnClickListener(v -> {
                    if (mCallback != null) mCallback.onAction(item);
                });
            }
            holder.itemView.setAlpha(1.0f);
        } else {
            holder.mBtnAction.setVisibility(View.GONE);
            holder.mSwitch.setVisibility(View.VISIBLE);
            holder.mSwitch.setOnCheckedChangeListener(null);
            holder.mSwitch.setChecked(item.isEnabled);
            holder.mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (mCallback != null) {
                    mCallback.onToggle(item, isChecked);
                }
            });
            holder.itemView.setAlpha(item.isEnabled ? 1.0f : 0.6f);
        }

        holder.mDeleteButton.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onDelete(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mFilteredItems.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView mIcon;
        final TextView mTitle;
        final TextView mMeta;
        final TextView mDesc;
        final SwitchCompat mSwitch;
        final ImageButton mDeleteButton;
        final MineButton mBtnAction;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            mIcon = itemView.findViewById(R.id.managed_item_icon);
            mTitle = itemView.findViewById(R.id.managed_item_title);
            mMeta = itemView.findViewById(R.id.managed_item_meta);
            mDesc = itemView.findViewById(R.id.managed_item_desc);
            mSwitch = itemView.findViewById(R.id.managed_item_switch);
            mDeleteButton = itemView.findViewById(R.id.managed_item_delete);
            mBtnAction = itemView.findViewById(R.id.managed_item_btn_action);
        }
    }
}
