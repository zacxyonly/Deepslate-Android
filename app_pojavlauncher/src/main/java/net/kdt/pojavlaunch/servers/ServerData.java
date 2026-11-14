package net.kdt.pojavlaunch.servers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import androidx.annotation.Nullable;

public class ServerData {
    public static final int STATUS_UNCHECKED = 0;
    public static final int STATUS_PINGING = 1;
    public static final int STATUS_ONLINE = 2;
    public static final int STATUS_OFFLINE = 3;

    public String name;
    public String ip;
    @Nullable
    public String icon; // Base64 encoded PNG
    public byte acceptTextures = 0;
    public boolean hidden = false;

    // Live ping status
    public int status = STATUS_UNCHECKED;
    public long pingMs = -1;
    public int onlinePlayers = 0;
    public int maxPlayers = 0;
    public String versionName;
    public String motd;
    @Nullable
    private Bitmap mCachedBitmap;

    public ServerData() {}

    public ServerData(String name, String ip) {
        this.name = name;
        this.ip = ip;
    }

    @Nullable
    public Bitmap getIconBitmap() {
        if (mCachedBitmap != null && !mCachedBitmap.isRecycled()) {
            return mCachedBitmap;
        }
        if (icon == null || icon.trim().isEmpty()) {
            return null;
        }
        try {
            String b64 = icon;
            if (b64.startsWith("data:image/png;base64,")) {
                b64 = b64.substring("data:image/png;base64,".length());
            }
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            mCachedBitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
            return mCachedBitmap;
        } catch (Exception ignored) {
            return null;
        }
    }

    public void setIconBase64(@Nullable String base64Png) {
        this.icon = base64Png;
        this.mCachedBitmap = null;
    }

    public String getHost() {
        if (ip == null) return "";
        int colonIdx = ip.indexOf(':');
        if (colonIdx > 0) {
            return ip.substring(0, colonIdx).trim();
        }
        return ip.trim();
    }

    public int getPort() {
        if (ip == null) return 25565;
        int colonIdx = ip.indexOf(':');
        if (colonIdx > 0 && colonIdx < ip.length() - 1) {
            try {
                return Integer.parseInt(ip.substring(colonIdx + 1).trim());
            } catch (NumberFormatException ignored) {}
        }
        return 25565;
    }
}
