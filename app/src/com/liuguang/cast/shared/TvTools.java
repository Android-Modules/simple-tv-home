package com.liuguang.cast.shared;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Data exchanged by the device information and file transfer operations. */
public final class TvTools {
    public static final long MAX_FILE = 512L * 1024 * 1024;
    public static final int CHUNK = 64 * 1024;
    public static final int MAX_APPS = 500;
    public static final int MAX_APP_ICON = 24 * 1024;
    public static final int MAX_APK_PARTS = 256;
    public static final int MAX_FILENAME = 76;

    private TvTools() {}

    public record Info(String name, String manufacturer, String model, String android, int api,
                       int width, int height, long available, long total, String version) {
        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(name);
            out.writeUTF(manufacturer);
            out.writeUTF(model);
            out.writeUTF(android);
            out.writeInt(api);
            out.writeInt(width);
            out.writeInt(height);
            out.writeLong(available);
            out.writeLong(total);
            out.writeUTF(version);
        }

        public static Info read(DataInputStream in) throws IOException {
            return new Info(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readInt(),
                    in.readInt(), in.readInt(), in.readLong(), in.readLong(), in.readUTF());
        }
    }

    public record App(String label, String packageName, String version, byte[] icon) {}

    public record AppDetails(String version, long versionCode, int minApi, int targetApi, boolean system,
                             long installedAt, long updatedAt, long bytes, int parts, boolean exportable) {
        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(version);
            out.writeLong(versionCode);
            out.writeInt(minApi);
            out.writeInt(targetApi);
            out.writeBoolean(system);
            out.writeLong(installedAt);
            out.writeLong(updatedAt);
            out.writeLong(bytes);
            out.writeShort(parts);
            out.writeBoolean(exportable);
        }

        public static AppDetails read(DataInputStream in) throws IOException {
            AppDetails details = new AppDetails(in.readUTF(), in.readLong(), in.readInt(), in.readInt(),
                    in.readBoolean(), in.readLong(), in.readLong(), in.readLong(), in.readUnsignedShort(),
                    in.readBoolean());
            if (details.bytes() < 0 || (details.exportable() && (details.bytes() == 0
                    || details.parts() < 1 || details.parts() > MAX_APK_PARTS))) {
                throw new IOException("电视返回的安装包信息无效。");
            }
            return details;
        }
    }

    public static boolean validApkPart(String name) {
        return name.endsWith(".apk") && name.length() <= 180 && !name.startsWith(".")
                && name.matches("[A-Za-z0-9_.-]+");
    }

    public static void writeApps(DataOutputStream out, List<App> apps, boolean withIcons) throws IOException {
        out.writeShort(apps.size());
        for (App app : apps) {
            out.writeUTF(app.label());
            out.writeUTF(app.packageName());
            out.writeUTF(app.version());
            if (withIcons) {
                byte[] icon = app.icon();
                if (icon == null || icon.length > MAX_APP_ICON) out.writeShort(0);
                else {
                    out.writeShort(icon.length);
                    out.write(icon);
                }
            }
        }
    }

    public static List<App> readApps(DataInputStream in) throws IOException {
        return readApps(in, true);
    }

    public static List<App> readApps(DataInputStream in, boolean withIcons) throws IOException {
        int count = in.readUnsignedShort();
        if (count > MAX_APPS) throw new IOException("电视返回的应用列表超出上限。");
        List<App> apps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = in.readUTF(), packageName = in.readUTF(), version = in.readUTF();
            int size = withIcons ? in.readUnsignedShort() : 0;
            if (size > MAX_APP_ICON) throw new IOException("电视返回的应用图标过大。");
            byte[] icon = size == 0 ? null : new byte[size];
            if (icon != null) in.readFully(icon);
            apps.add(new App(name, packageName, version, icon));
        }
        return apps;
    }

    public static String filename(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cc}\\p{Cf}]", "_").trim();
        int start = 0;
        while (start < clean.length() && clean.charAt(start) == '.') start++;
        clean = clean.substring(start).trim();
        if (clean.length() > MAX_FILENAME) {
            int extension = clean.lastIndexOf('.');
            String suffix = extension > 0 && clean.length() - extension <= 12 ? clean.substring(extension) : "";
            int end = MAX_FILENAME - suffix.length();
            if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
            clean = clean.substring(0, end).trim() + suffix;
        }
        return clean.isEmpty() ? "未命名文件" : clean;
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
