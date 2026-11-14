package net.kdt.pojavlaunch.servers;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.MineButton;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

public class ServerListAdapter extends RecyclerView.Adapter<ServerListAdapter.ViewHolder> {

    public interface ServerActionCallback {
        void onPlay(ServerData server);
        void onEdit(ServerData server);
        void onDelete(ServerData server);
    }

    private final List<ServerData> mItems = new ArrayList<>();
    private final ServerActionCallback mCallback;

    public ServerListAdapter(ServerActionCallback callback) {
        this.mCallback = callback;
    }

    public void setServers(List<ServerData> servers) {
        mItems.clear();
        if (servers != null) {
            mItems.addAll(servers);
        }
        notifyDataSetChanged();
    }

    public List<ServerData> getServers() {
        return mItems;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_server_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ServerData server = mItems.get(position);

        holder.tvName.setText(server.name != null && !server.name.trim().isEmpty() ? server.name : server.ip);
        holder.tvIp.setText(server.ip != null ? server.ip : "");

        // Icon
        Bitmap bmp = server.getIconBitmap();
        if (bmp != null) {
            holder.ivIcon.setImageBitmap(bmp);
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_menu_custom_controls);
        }

        // Ping and Status
        if (server.status == ServerData.STATUS_PINGING) {
            holder.tvPing.setText("● Pinging...");
            holder.tvPing.setTextColor(Color.parseColor("#F59E0B")); // Amber / Yellow
            holder.tvPlayers.setVisibility(View.GONE);
            holder.tvMotd.setText(holder.itemView.getContext().getString(R.string.server_status_pinging));
        } else if (server.status == ServerData.STATUS_ONLINE) {
            holder.tvPing.setText(String.format(java.util.Locale.ROOT, "● %d ms", server.pingMs));
            holder.tvPing.setTextColor(Color.parseColor("#10B981")); // Emerald / Green

            holder.tvPlayers.setVisibility(View.VISIBLE);
            holder.tvPlayers.setText(holder.itemView.getContext().getString(
                    R.string.server_players_count, server.onlinePlayers, server.maxPlayers));

            if (server.motd != null && !server.motd.trim().isEmpty()) {
                holder.tvMotd.setVisibility(View.VISIBLE);
                holder.tvMotd.setText(server.motd);
            } else {
                holder.tvMotd.setVisibility(View.GONE);
            }
        } else if (server.status == ServerData.STATUS_OFFLINE) {
            holder.tvPing.setText("● Offline");
            holder.tvPing.setTextColor(Color.parseColor("#EF4444")); // Red
            holder.tvPlayers.setVisibility(View.GONE);
            holder.tvMotd.setVisibility(View.VISIBLE);
            holder.tvMotd.setText(holder.itemView.getContext().getString(R.string.server_status_offline));
        } else {
            holder.tvPing.setText("● -");
            holder.tvPing.setTextColor(Color.parseColor("#94A3B8"));
            holder.tvPlayers.setVisibility(View.GONE);
            holder.tvMotd.setVisibility(View.GONE);
        }

        // Listeners
        holder.btnPlay.setOnClickListener(v -> {
            if (mCallback != null) mCallback.onPlay(server);
        });

        holder.btnEdit.setOnClickListener(v -> {
            if (mCallback != null) mCallback.onEdit(server);
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (mCallback != null) mCallback.onDelete(server);
        });
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName;
        TextView tvIp;
        TextView tvPing;
        TextView tvPlayers;
        TextView tvMotd;
        MineButton btnPlay;
        ImageButton btnEdit;
        ImageButton btnDelete;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.server_item_icon);
            tvName = itemView.findViewById(R.id.server_item_name);
            tvIp = itemView.findViewById(R.id.server_item_ip);
            tvPing = itemView.findViewById(R.id.server_item_ping);
            tvPlayers = itemView.findViewById(R.id.server_item_players);
            tvMotd = itemView.findViewById(R.id.server_item_motd);
            btnPlay = itemView.findViewById(R.id.server_item_btn_play);
            btnEdit = itemView.findViewById(R.id.server_item_btn_edit);
            btnDelete = itemView.findViewById(R.id.server_item_btn_delete);
        }
    }
}
