package com.liuguang.cast.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApkInstaller implements AutoCloseable {
    static final int SOURCE_SETTINGS = 42;
    static final int SYSTEM_INSTALL = 43;
    static final String TICKET = "install_ticket";
    interface Feedback { void show(String message, boolean ok); }
    private record Target(String packageName, long version) {}

    private static ApkInstaller current;
    private final Activity activity;
    private final PackageManager packages;
    private final PackageInstaller installer;
    private final Feedback feedback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private volatile int sessionId = -1;
    private volatile Target target;
    private volatile boolean closed;
    private String installing, sourceRequest;
    private Intent confirmation;
    private long beforeSystemInstall;
    private boolean ready, needsConfirmation;

    ApkInstaller(Activity activity, Feedback feedback) {
        this.activity = activity;
        this.feedback = feedback;
        packages = activity.getPackageManager();
        installer = packages.getPackageInstaller();
        current = this;
    }

    static void result(Intent outcome) {
        if (current != null) current.onResult(outcome);
    }

    void enqueue(File file) {
        String name = file.getName();
        if (!name.equals(installing) && !name.equals(sourceRequest) && !queue.contains(name)) queue.addLast(name);
        if (!ready) feedback.show("投屏结束后将安装 " + name, true);
        advance();
    }

    void setReady(boolean allowInstall) {
        ready = allowInstall;
        advance();
    }

    void sourcesReturned() {
        String name = sourceRequest;
        sourceRequest = null;
        if (name == null) return;
        if (mayInstall()) queue.addFirst(name);
        else feedback.show("未开启安装来源，可在接收文件中选中 APK 重试", false);
        advance();
    }

    private boolean mayInstall() {
        return Build.VERSION.SDK_INT < 26 || packages.canRequestPackageInstalls();
    }

    private void advance() {
        if (!ready || closed) return;
        if (needsConfirmation) {
            Intent action = confirmation;
            needsConfirmation = false;
            confirmation = null;
            if (action == null) {
                openSystemInstaller();
                return;
            }
            try { activity.startActivity(action); }
            catch (ActivityNotFoundException | SecurityException e) { openSystemInstaller(); }
            return;
        }
        if (installing != null || sourceRequest != null || queue.isEmpty()) return;
        File next = null;
        while (next == null && !queue.isEmpty()) next = TvFiles.resolve(activity, queue.removeFirst());
        if (next == null) return;
        if (!mayInstall()) {
            askSource(next.getName());
            return;
        }
        installing = next.getName();
        feedback.show("正在准备安装 " + installing, true);
        File apk = next;
        worker.execute(() -> stage(apk));
    }

    private void askSource(String name) {
        sourceRequest = name;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("允许" + TvDetails.name() + "安装应用")
                .setMessage("已收到 " + name + "。在电视设置中允许此来源后，会继续安装。")
                .setNegativeButton("稍后", (d, w) -> sourcesReturned())
                .setPositiveButton("前往设置", (d, w) -> {
                    Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    try { activity.startActivityForResult(settings, SOURCE_SETTINGS); }
                    catch (ActivityNotFoundException | SecurityException e) { sourcesReturned(); }
                }).create();
        dialog.setOnCancelListener(d -> sourcesReturned());
        dialog.show();
    }

    private void stage(File file) {
        int id = -1;
        try {
            PackageInfo archive = packages.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (archive == null) {
                ui.post(() -> finish("无法识别此 APK，请使用完整的安装包", false));
                return;
            }
            target = new Target(archive.packageName, TvDetails.versionCode(archive));
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(file.length());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            if (Build.VERSION.SDK_INT >= 33) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
            id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (FileInputStream source = new FileInputStream(file);
                     OutputStream dest = session.openWrite("base.apk", 0, file.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int length; (length = source.read(buffer)) != -1;) {
                        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("installation closed");
                        dest.write(buffer, 0, length);
                    }
                    session.fsync(dest);
                }
                if (closed) throw new IOException("installation closed");
                Intent status = new Intent(activity, ApkInstallResult.class).putExtra(TICKET, id);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent callback = PendingIntent.getBroadcast(activity, id, status, flags);
                sessionId = id;
                session.commit(callback.getIntentSender());
            }
        } catch (IOException | RuntimeException e) {
            abandon(id);
            ui.post(() -> finish("无法准备安装这个 APK", false));
        }
    }

    private void onResult(Intent outcome) {
        if (closed || installing == null || sessionId < 0 || outcome.getIntExtra(TICKET, -1) != sessionId) return;
        int state = outcome.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (state == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            needsConfirmation = true;
            confirmation = Build.VERSION.SDK_INT >= 33
                    ? outcome.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : outcome.getParcelableExtra(Intent.EXTRA_INTENT);
            feedback.show("在电视上确认安装 " + installing, true);
            advance();
        } else {
            switch (state) {
                case PackageInstaller.STATUS_SUCCESS -> finish("已安装 " + installing, true);
                case PackageInstaller.STATUS_FAILURE_ABORTED -> finish("安装已取消 · " + installing, false);
                case PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        finish("安装冲突，请检查应用版本或签名 · " + installing, false);
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        finish("安装包与电视不兼容 · " + installing, false);
                case PackageInstaller.STATUS_FAILURE_STORAGE -> finish("电视存储不足 · " + installing, false);
                default -> finish("系统未能安装 · " + installing, false);
            }
        }
    }

    // Some TV firmware handles APK intents but omits the session confirmation activity.
    private void openSystemInstaller() {
        int failedSession = sessionId;
        sessionId = -1;
        abandon(failedSession);
        File apk = TvFiles.resolve(activity, installing);
        if (apk == null) {
            finish("安装包已从接收列表移除", false);
            return;
        }
        Uri uri = TvFiles.contentUri(activity, apk);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, TvFiles.mime(apk))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.setClipData(ClipData.newRawUri(apk.getName(), uri));
        beforeSystemInstall = installedUpdateTime();
        try { activity.startActivityForResult(install, SYSTEM_INSTALL); }
        catch (ActivityNotFoundException | SecurityException e) {
            finish("无法打开电视的系统安装器", false);
        }
    }

    void systemInstallerReturned(int result) {
        if (installing == null || sessionId != -1) return;
        // TV installers may return RESULT_CANCELED even after successfully installing.
        boolean success = result == Activity.RESULT_OK || installedUpdateTime() > beforeSystemInstall;
        finish((success ? "已安装 " : "系统安装已关闭 · ") + installing, success);
    }

    private long installedUpdateTime() {
        Target expected = target;
        if (expected == null) return 0;
        try {
            PackageInfo installed = packages.getPackageInfo(expected.packageName(), 0);
            return TvDetails.versionCode(installed) == expected.version() ? installed.lastUpdateTime : 0;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void finish(String message, boolean ok) {
        if (closed) return;
        sessionId = -1;
        target = null;
        needsConfirmation = false;
        confirmation = null;
        installing = null;
        feedback.show(message, ok);
        advance();
    }

    private void abandon(int id) {
        if (id < 0) return;
        try { installer.abandonSession(id); }
        catch (RuntimeException ignored) {}
    }

    @Override public void close() {
        closed = true;
        if (current == this) current = null;
        worker.shutdownNow();
    }
}
