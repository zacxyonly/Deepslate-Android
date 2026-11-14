package net.kdt.pojavlaunch.servers;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class ServersDatUtils {
    private static final String TAG = "ServersDatUtils";

    public static List<ServerData> loadServers(File dataFile) {
        List<ServerData> serverList = new ArrayList<>();
        if (dataFile == null || !dataFile.exists() || dataFile.length() == 0) {
            return serverList;
        }

        try (InputStream rawIn = new FileInputStream(dataFile);
             BufferedInputStream bis = new BufferedInputStream(rawIn)) {

            bis.mark(2);
            int b0 = bis.read();
            int b1 = bis.read();
            bis.reset();

            InputStream is;
            if (b0 == 0x1F && b1 == 0x8B) {
                is = new GZIPInputStream(bis);
            } else {
                is = bis;
            }

            try (DataInputStream in = new DataInputStream(is)) {
                byte rootType = in.readByte();
                if (rootType != 10) { // TAG_Compound
                    Log.w(TAG, "Root tag is not TAG_Compound, type=" + rootType);
                    return serverList;
                }
                String rootName = in.readUTF(); // usually ""

                // Read tags in root compound until TAG_End
                while (true) {
                    byte tagType = in.readByte();
                    if (tagType == 0) break; // TAG_End
                    String tagName = in.readUTF();

                    if ("servers".equals(tagName) && tagType == 9) { // TAG_List
                        byte elemType = in.readByte();
                        int count = in.readInt();
                        for (int i = 0; i < count; i++) {
                            if (elemType == 10) { // TAG_Compound
                                ServerData server = new ServerData();
                                while (true) {
                                    byte fType = in.readByte();
                                    if (fType == 0) break;
                                    String fName = in.readUTF();
                                    if ("name".equals(fName) && fType == 8) {
                                        server.name = in.readUTF();
                                    } else if ("ip".equals(fName) && fType == 8) {
                                        server.ip = in.readUTF();
                                    } else if ("icon".equals(fName) && fType == 8) {
                                        server.icon = in.readUTF();
                                    } else if ("acceptTextures".equals(fName) && fType == 1) {
                                        server.acceptTextures = in.readByte();
                                    } else if ("hidden".equals(fName) && fType == 1) {
                                        server.hidden = (in.readByte() != 0);
                                    } else {
                                        skipTagPayload(in, fType);
                                    }
                                }
                                if (server.name != null || server.ip != null) {
                                    serverList.add(server);
                                }
                            } else {
                                skipTagPayload(in, elemType);
                            }
                        }
                    } else {
                        skipTagPayload(in, tagType);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading servers.dat from " + dataFile.getAbsolutePath(), e);
        }
        return serverList;
    }

    public static boolean saveServers(File dataFile, List<ServerData> servers) {
        if (dataFile == null) return false;
        File parent = dataFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File tempFile = new File(dataFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DataOutputStream out = new DataOutputStream(bos)) {

            // Root Compound
            out.writeByte(10); // TAG_Compound
            out.writeUTF("");  // Root name

            // TAG_List "servers"
            out.writeByte(9);  // TAG_List
            out.writeUTF("servers");
            out.writeByte(10); // Element type: TAG_Compound
            out.writeInt(servers != null ? servers.size() : 0);

            if (servers != null) {
                for (ServerData s : servers) {
                    if (s.name != null) {
                        out.writeByte(8); // TAG_String
                        out.writeUTF("name");
                        out.writeUTF(s.name);
                    }
                    if (s.ip != null) {
                        out.writeByte(8); // TAG_String
                        out.writeUTF("ip");
                        out.writeUTF(s.ip);
                    }
                    if (s.icon != null && !s.icon.isEmpty()) {
                        out.writeByte(8); // TAG_String
                        out.writeUTF("icon");
                        out.writeUTF(s.icon);
                    }
                    if (s.acceptTextures != 0) {
                        out.writeByte(1); // TAG_Byte
                        out.writeUTF("acceptTextures");
                        out.writeByte(s.acceptTextures);
                    }
                    if (s.hidden) {
                        out.writeByte(1); // TAG_Byte
                        out.writeUTF("hidden");
                        out.writeByte(1);
                    }
                    out.writeByte(0); // TAG_End for this server compound
                }
            }

            out.writeByte(0); // TAG_End for root compound
            out.flush();

            if (dataFile.exists()) {
                dataFile.delete();
            }
            return tempFile.renameTo(dataFile);
        } catch (Exception e) {
            Log.e(TAG, "Error saving servers.dat to " + dataFile.getAbsolutePath(), e);
            if (tempFile.exists()) {
                tempFile.delete();
            }
            return false;
        }
    }

    private static void skipTagPayload(DataInputStream in, byte tagType) throws IOException {
        switch (tagType) {
            case 1: // TAG_Byte
                in.skipBytes(1);
                break;
            case 2: // TAG_Short
                in.skipBytes(2);
                break;
            case 3: // TAG_Int
            case 5: // TAG_Float
                in.skipBytes(4);
                break;
            case 4: // TAG_Long
            case 6: // TAG_Double
                in.skipBytes(8);
                break;
            case 7: { // TAG_Byte_Array
                int len = in.readInt();
                if (len > 0) in.skipBytes(len);
                break;
            }
            case 8: { // TAG_String
                in.readUTF();
                break;
            }
            case 9: { // TAG_List
                byte elemType = in.readByte();
                int len = in.readInt();
                for (int i = 0; i < len; i++) {
                    skipTagPayload(in, elemType);
                }
                break;
            }
            case 10: { // TAG_Compound
                while (true) {
                    byte t = in.readByte();
                    if (t == 0) break;
                    in.readUTF();
                    skipTagPayload(in, t);
                }
                break;
            }
            case 11: { // TAG_Int_Array
                int len = in.readInt();
                if (len > 0) in.skipBytes(len * 4);
                break;
            }
            case 12: { // TAG_Long_Array
                int len = in.readInt();
                if (len > 0) in.skipBytes(len * 8);
                break;
            }
            default:
                break;
        }
    }
}
