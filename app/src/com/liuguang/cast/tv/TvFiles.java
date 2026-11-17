package com.liuguang.cast.tv;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import com.liuguang.cast.shared.TvTools;
import com.liuguang.cast.shared.Wire;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

final class TvFiles {
    private static final long RESERVED = 8L * 1024 * 1024;
    interface Progress { void update(String name, long bytes, long total); }

    static File folder(Context context) { return new File(context.getFilesDir(), "inbox"); }

    static Uri contentUri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".inbox")
                .appendPath(file.getName()).build();
    }

    static File[] list(Context context) {
        File[] files = folder(context).listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    static String mime(File file) {
        String name = file.getName();
        if (isApk(file)) return "application/vnd.android.package-archive";
        int split = name.lastIndexOf('.');
        String type = split < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(split + 1).toLowerCase(Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }

    static boolean isApk(File file) { return file.getName().toLowerCase(Locale.ROOT).endsWith(".apk"); }

    static File resolve(Context context, String name) {
        if (name == null || name.startsWith(".") || name.contains("/") || name.contains("\\")) return null;
        try {
            File folder = folder(context).getCanonicalFile();
            File selected = new File(folder, name).getCanonicalFile();
            if (folder.equals(selected.getParentFile()) && selected.getName().equals(name) && selected.isFile()) {
                return selected;
            }
        } catch (IOException ignored) {}
        return null;
    }

    static File receive(Context context, DataInputStream in, DataOutputStream out, Progress progress)
            throws IOException {
        String name = TvTools.filename(in.readUTF());
        long total = in.readLong();
        File folder = folder(context);
        if (total < -1 || total > TvTools.MAX_FILE) throw new IOException("单个文件最多可接收 512 MB。");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建电视的接收目录。");
        long free = folder.getUsableSpace() - RESERVED;
        if (Math.max(0, total) > free || free <= 0) throw new IOException("电视存储空间不足。");

        File partial = File.createTempFile(".upload-", ".part", folder);
        try {
            try (FileOutputStream file = new FileOutputStream(partial)) {
                out.writeByte(Wire.ACCEPT);
                out.flush();
                progress.update(name, 0, total);
                byte[] buffer = new byte[TvTools.CHUNK];
                long received = 0, lastUpdate = 0;
                for (int length; (length = in.readInt()) != 0;) {
                    if (length < 0 || length > buffer.length) throw new IOException("文件数据格式不正确。");
                    received += length;
                    if (received > TvTools.MAX_FILE || (total >= 0 && received > total)) {
                        throw new IOException("文件超过已声明大小或传输上限。");
                    }
                    if (received > free) throw new IOException("电视存储空间不足。");
                    in.readFully(buffer, 0, length);
                    file.write(buffer, 0, length);
                    if (android.os.SystemClock.elapsedRealtime() - lastUpdate >= 180) {
                        progress.update(name, received, total);
                        lastUpdate = android.os.SystemClock.elapsedRealtime();
                    }
                }
                if (total >= 0 && received != total) throw new IOException("文件尚未完整接收。");
                file.getFD().sync();
            }
            File file = availableName(folder, name);
            if (!partial.renameTo(file)) throw new IOException("无法在电视上保存文件。");
            return file;
        } finally {
            if (partial.exists()) partial.delete();
        }
    }

    private static File availableName(File folder, String name) {
        File result = new File(folder, name);
        int dot = name.lastIndexOf('.');
        String base = dot < 1 ? name : name.substring(0, dot);
        String extension = dot < 1 ? "" : name.substring(dot);
        for (int i = 2; result.exists(); i++) result = new File(folder, base + " (" + i + ")" + extension);
        return result;
    }
}
