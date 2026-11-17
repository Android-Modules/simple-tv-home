package com.liuguang.cast.tv;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.liuguang.cast.shared.TvTools;
import com.liuguang.cast.shared.Wire;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TvApks {
    private record Part(String name, File file, long bytes) {}

    record Installed(TvTools.AppDetails details, List<Part> parts) {
        void send(DataOutputStream out) throws IOException {
            out.writeByte(Wire.ACCEPT);
            out.writeShort(parts.size());
            byte[] buffer = new byte[TvTools.CHUNK];
            for (Part part : parts) {
                try (FileInputStream input = new FileInputStream(part.file())) {
                    out.writeUTF(part.name());
                    out.writeLong(part.bytes());
                    long remaining = part.bytes();
                    while (remaining > 0) {
                        int count = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                        if (count < 0) throw new IOException("APK changed during transfer");
                        out.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
            }
            out.flush();
        }
    }

    static Installed lookup(Context context, String packageName) throws IOException {
        PackageManager manager = context.getPackageManager();
        if (!TvDetails.launcherApps(manager).containsKey(packageName)) {
            throw new IOException("应用已从电视的应用列表移除，请刷新。");
        }
        PackageInfo info;
        try { info = manager.getPackageInfo(packageName, 0); }
        catch (PackageManager.NameNotFoundException e) {
            throw new IOException("在电视上找不到该应用，请刷新。", e);
        }
        ApplicationInfo app = info.applicationInfo;
        if (app == null) throw new IOException("电视无法读取此应用的信息。");
        List<Part> parts = new ArrayList<>();
        File base = app.sourceDir == null ? null : new File(app.sourceDir);
        parts.add(new Part("base.apk", base, base == null ? 0 : base.length()));
        if (app.splitSourceDirs != null) for (String path : app.splitSourceDirs) {
            File split = new File(path);
            parts.add(new Part(split.getName(), split, split.length()));
        }

        long bytes = 0;
        boolean exportable = parts.size() <= TvTools.MAX_APK_PARTS;
        Set<String> names = new HashSet<>();
        for (Part part : parts) {
            bytes += part.bytes();
            if (part.file() == null || !part.file().isFile() || !part.file().canRead() || part.bytes() <= 0
                    || !TvTools.validApkPart(part.name()) || !names.add(part.name())) exportable = false;
        }

        TvTools.AppDetails details = new TvTools.AppDetails(info.versionName == null ? "" : TvDetails.limit(info.versionName),
                TvDetails.versionCode(info), Build.VERSION.SDK_INT >= 24 ? app.minSdkVersion : 0,
                app.targetSdkVersion, (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                info.firstInstallTime, info.lastUpdateTime, bytes, parts.size(), exportable);
        return new Installed(details, parts);
    }
}
