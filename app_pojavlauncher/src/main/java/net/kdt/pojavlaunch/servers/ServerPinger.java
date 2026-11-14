package net.kdt.pojavlaunch.servers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerPinger {
    private static final String TAG = "ServerPinger";
    private static final int TIMEOUT_MS = 5000;
    private static final ExecutorService PING_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface PingCallback {
        void onPingResult(ServerData server, boolean success);
    }

    public static void ping(ServerData server, PingCallback callback) {
        server.status = ServerData.STATUS_PINGING;
        PING_EXECUTOR.execute(() -> {
            boolean success = false;
            try {
                pingInternal(server);
                server.status = ServerData.STATUS_ONLINE;
                success = true;
            } catch (Exception e) {
                Log.d(TAG, "Failed to ping server " + server.ip + ": " + e.getMessage());
                server.status = ServerData.STATUS_OFFLINE;
                server.pingMs = -1;
            }

            final boolean finalSuccess = success;
            MAIN_HANDLER.post(() -> {
                if (callback != null) {
                    callback.onPingResult(server, finalSuccess);
                }
            });
        });
    }

    private static void pingInternal(ServerData server) throws IOException {
        String host = server.getHost();
        int port = server.getPort();

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            DataInputStream dis = new DataInputStream(in);
            DataOutputStream dos = new DataOutputStream(out);

            // 1. Handshake Packet (ID 0x00)
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshakeDos = new DataOutputStream(handshakeBytes);
            writeVarInt(handshakeDos, 0x00); // Packet ID
            writeVarInt(handshakeDos, 765);  // Protocol version (1.20.4)
            writeString(handshakeDos, host); // Host
            handshakeDos.writeShort(port);    // Port
            writeVarInt(handshakeDos, 1);    // Next state: 1 (status)

            writeVarInt(dos, handshakeBytes.size());
            dos.write(handshakeBytes.toByteArray());

            // 2. Status Request Packet (ID 0x00)
            dos.writeByte(0x01); // Packet length: 1
            dos.writeByte(0x00); // Packet ID: 0x00
            dos.flush();

            // 3. Status Response Packet
            int packetLength = readVarInt(dis);
            int packetId = readVarInt(dis);
            if (packetId != 0x00) {
                throw new IOException("Unexpected packet id: " + packetId);
            }

            String jsonResponse = readString(dis);

            // 4. Ping / Pong latency check
            long pingStart = System.currentTimeMillis();
            try {
                dos.writeByte(0x09); // Packet length: 9 (1 byte ID + 8 bytes payload)
                dos.writeByte(0x01); // Ping packet ID
                dos.writeLong(pingStart);
                dos.flush();

                int pongPacketLength = readVarInt(dis);
                int pongId = readVarInt(dis);
                if (pongId == 0x01) {
                    long pongTimestamp = dis.readLong();
                    server.pingMs = Math.max(1, System.currentTimeMillis() - pingStart);
                } else {
                    server.pingMs = Math.max(1, System.currentTimeMillis() - pingStart);
                }
            } catch (Exception ignored) {
                server.pingMs = Math.max(1, System.currentTimeMillis() - pingStart);
            }

            // 5. Parse JSON
            parseStatusJson(server, jsonResponse);
        }
    }

    private static void parseStatusJson(ServerData server, String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);

            // Players
            if (json.has("players")) {
                JSONObject players = json.getJSONObject("players");
                server.onlinePlayers = players.optInt("online", 0);
                server.maxPlayers = players.optInt("max", 0);
            }

            // Version
            if (json.has("version")) {
                JSONObject version = json.getJSONObject("version");
                server.versionName = version.optString("name", "");
            }

            // Description / MOTD
            if (json.has("description")) {
                Object descObj = json.get("description");
                server.motd = extractCleanMotd(descObj);
            }

            // Favicon
            if (json.has("favicon")) {
                String favicon = json.optString("favicon", null);
                if (favicon != null && favicon.startsWith("data:image/png;base64,")) {
                    server.setIconBase64(favicon.substring("data:image/png;base64,".length()));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse SLP JSON", e);
        }
    }

    public static String extractCleanMotd(Object descObj) {
        if (descObj == null) return "";
        if (descObj instanceof String) {
            return stripFormatting((String) descObj).trim();
        }
        if (descObj instanceof JSONObject) {
            JSONObject obj = (JSONObject) descObj;
            StringBuilder sb = new StringBuilder();
            if (obj.has("text")) {
                sb.append(obj.optString("text", ""));
            }
            if (obj.has("extra")) {
                JSONArray extra = obj.optJSONArray("extra");
                if (extra != null) {
                    for (int i = 0; i < extra.length(); i++) {
                        Object item = extra.opt(i);
                        if (item instanceof String) {
                            sb.append((String) item);
                        } else if (item instanceof JSONObject) {
                            JSONObject itemObj = (JSONObject) item;
                            sb.append(itemObj.optString("text", ""));
                        }
                    }
                }
            }
            return stripFormatting(sb.toString()).trim();
        }
        return stripFormatting(descObj.toString()).trim();
    }

    public static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt is too big");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > 65536) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
