package com.liuguang.cast.tv;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import com.liuguang.cast.shared.TvTools;
import com.liuguang.cast.shared.Wire;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.dalong.tvhome.MainActivity;
import dev.dalong.tvhome.R;

public final class TvReceiverService extends Service {
    private static final String TAG = "TvReceiverService";
    private static final String CHANNEL = "simple_cast_receiver";
    private static final String PLAYBACK_ID = "playback_id";
    private static final int NOTIFICATION_ID = 45454;
    private static final long CHECK_INTERVAL_MS = 5000;
    private static final long SURFACE_TIMEOUT_MS = 4000;
    private static final long PROMPT_TIMEOUT_MS = 1200;

    // Activity and service lifecycle callbacks share the main thread.
    private static WeakReference<Activity> visibleHost = new WeakReference<>(null);
    private static final LinkedHashSet<Runnable> listeners = new LinkedHashSet<>();
    private static TvReceiverService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> pendingApks = new LinkedHashSet<>();
    private TvServer server;
    private int serverGeneration;
    private NsdManager nsd;
    private NsdManager.RegistrationListener advertisement;
    private TvServer.PairCode pairCode;
    private String address;
    private String statusText = "正在启动接收端…", fileStatusText = "";
    private boolean statusOk = true, foreground;
    private Activity installerHost;
    private ApkInstaller installer;
    private PlaybackRequest playback;
    private TvPairPrompt pairPrompt;

    private final Runnable checkNetwork = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            refreshAddress();
            if (server == null) startServer();
            else advertise();
            ui.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private static final class PlaybackRequest {
        final long id = SystemClock.elapsedRealtimeNanos();
        final boolean allowRemote;
        final CompletableFuture<Surface> ready = new CompletableFuture<>();
        TvActivity player;
        Surface surface;

        PlaybackRequest(boolean allowRemote) {
            this.allowRemote = allowRemote;
        }
    }

    public static void launch(Activity activity) {
        visibleHost = new WeakReference<>(activity);
        TvReceiverService receiver = instance;
        if (receiver != null && receiver.foreground) {
            receiver.startServer();
            receiver.resumeInstaller(activity);
            return;
        }
        try {
            activity.startForegroundService(new Intent(activity, TvReceiverService.class));
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Could not start receiver service", e);
        }
    }

    public static void leave(Activity activity) {
        if (visibleHost.get() == activity) visibleHost.clear();
        TvReceiverService receiver = instance;
        if (receiver == null) return;
        if (receiver.pairPrompt != null && receiver.pairPrompt.host == activity) receiver.dismissPairPrompt();
        if (receiver.installerHost == activity) receiver.installer.setReady(false);
    }

    public static void detach(Activity activity) {
        leave(activity);
        TvReceiverService receiver = instance;
        if (receiver != null && receiver.installerHost == activity) receiver.closeInstaller();
    }

    public static void activityResult(Activity activity, int request, int result) {
        TvReceiverService receiver = instance;
        if (receiver == null || receiver.installerHost != activity) return;
        if (request == ApkInstaller.SOURCE_SETTINGS) receiver.installer.sourcesReturned();
        if (request == ApkInstaller.SYSTEM_INSTALL) receiver.installer.systemInstallerReturned(result);
    }

    static TvReceiverService get() { return instance; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        nsd = getSystemService(NsdManager.class);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground) {
            Notification notice = receiverNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notice);
            }
            foreground = true;
            checkNetwork.run();
        }
        Activity host = visibleHost.get();
        if (isVisibleHost(host)) resumeInstaller(host);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        foreground = false;
        if (instance == this) instance = null;
        serverGeneration++;
        ui.removeCallbacksAndMessages(null);
        if (server != null) server.close();
        stopAdvertisement();
        dismissPairPrompt();
        finishPlayback();
        closeInstaller();
        notifyListeners();
        super.onDestroy();
    }

    private Notification receiverNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "投屏接收",
                NotificationManager.IMPORTANCE_LOW));
        Intent home = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(this, 0, home,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.tv_cast_mark)
                .setContentTitle(TvDetails.name())
                .setContentText("接收手机投屏和文件")
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void startServer() {
        if (!foreground || server != null) return;
        int generation = ++serverGeneration;
        try {
            server = new TvServer(this, new ReceiverEvents(generation));
            setStatus("正在启动接收端…", true);
            server.start();
        } catch (IOException e) {
            serverFailed("接收端启动失败，请重试。");
        }
    }

    private void serverFailed(String message) {
        serverGeneration++;
        if (server != null) {
            server.close();
            server = null;
        }
        pairCode = null;
        stopAdvertisement();
        dismissPairPrompt();
        finishPlayback();
        setStatus(message, false);
    }

    private void refreshAddress() {
        String current = TvDetails.address(this);
        if (!Objects.equals(address, current)) {
            address = current;
            notifyListeners();
        }
    }

    String address() { return address; }
    TvServer.PairCode pairCode() { return pairCode; }
    String statusText() { return statusText; }
    boolean statusOk() { return statusOk; }
    String fileStatusText() { return fileStatusText; }

    static void addListener(Runnable listener) { listeners.add(listener); }
    static void removeListener(Runnable listener) { listeners.remove(listener); }

    private static void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    private void setStatus(String text, boolean ok) {
        statusText = text;
        statusOk = ok;
        notifyListeners();
    }

    void refreshCode() {
        if (server == null) startServer();
        else server.refresh();
    }

    void clearPairings() {
        if (server != null) server.clearPairings();
    }

    void install(File apk) {
        pendingApks.add(apk.getName());
        updateInstaller();
    }

    private void resumeInstaller(Activity activity) {
        if (installerHost != activity) {
            closeInstaller();
            installerHost = activity;
            installer = new ApkInstaller(activity, (message, ok) -> {
                fileStatusText = message;
                setStatus(message, ok);
            });
        }
        updateInstaller();
    }

    private void updateInstaller() {
        if (installer == null) return;
        boolean ready = foreground && playback == null && isVisibleHost(installerHost);
        installer.setReady(ready);
        if (!ready || pendingApks.isEmpty()) return;
        ArrayList<String> files = new ArrayList<>(pendingApks);
        pendingApks.clear();
        for (String name : files) {
            File apk = TvFiles.resolve(this, name);
            if (apk != null) installer.enqueue(apk);
        }
    }

    private void closeInstaller() {
        if (installer != null) installer.close();
        installer = null;
        installerHost = null;
    }

    private static boolean isVisibleHost(Activity host) {
        return host != null && visibleHost.get() == host && !host.isFinishing() && !host.isDestroyed();
    }

    private boolean showPairPrompt(TvServer.PairCode requested) {
        if (requested != pairCode || playback != null || server == null) return false;
        if (pairPrompt != null) return true;
        Activity host = visibleHost.get();
        if (!isVisibleHost(host)) host = null;
        if (host == null && !Settings.canDrawOverlays(this)) return false;
        try {
            TvPairPrompt prompt = new TvPairPrompt(this, host, this::refreshCode);
            prompt.update(pairCode, server.shortCodeLocked());
            prompt.dialog.setOnDismissListener(dialog -> {
                if (pairPrompt == prompt) pairPrompt = null;
            });
            prompt.show();
            pairPrompt = prompt;
            return true;
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException | SecurityException e) {
            Log.w(TAG, "Could not show pairing code", e);
            return false;
        }
    }

    private void dismissPairPrompt() {
        TvPairPrompt prompt = pairPrompt;
        pairPrompt = null;
        if (prompt != null) prompt.dialog.dismiss();
    }

    private void preparePlayback(PlaybackRequest request) {
        if (request.ready.isDone() || playback != null) return;
        Activity host = visibleHost.get();
        boolean fromHost = isVisibleHost(host);
        if (!fromHost && !Settings.canDrawOverlays(this)) {
            request.ready.complete(null);
            return;
        }

        playback = request;
        dismissPairPrompt();
        updateInstaller();
        Intent play = new Intent(this, TvActivity.class).putExtra(PLAYBACK_ID, request.id);
        try {
            if (fromHost) host.startActivity(play);
            else startActivity(play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not present playback", e);
            request.ready.complete(null);
        }
    }

    boolean attachPlayer(TvActivity player) {
        PlaybackRequest request = playback;
        if (request == null || request.ready.isDone() || request.player != null
                || player.getIntent().getLongExtra(PLAYBACK_ID, -1) != request.id) return false;
        request.player = player;
        player.showConnection(request.allowRemote);
        return true;
    }

    void attachSurface(TvActivity player, Surface surface) {
        PlaybackRequest request = playback;
        if (request != null && request.player == player && surface != null
                && surface.isValid() && !request.ready.isDone()) {
            request.surface = surface;
            request.ready.complete(surface);
        }
    }

    void detachSurface(TvActivity player, Surface surface) {
        PlaybackRequest request = playback;
        if (request != null && request.player == player && request.surface == surface) {
            request.surface = null;
            disconnect();
        }
    }

    void detachPlayer(TvActivity player) {
        PlaybackRequest request = playback;
        if (request == null || request.player != player) return;
        request.player = null;
        if (!request.ready.complete(null)) disconnect();
    }

    void disconnect() {
        if (playback != null && playback.ready.complete(null)) return;
        if (server != null) server.disconnect();
    }

    boolean remote(int command) { return server != null && server.remote(command); }

    void pictureFillChanged(boolean enabled) {
        if (server != null) server.pictureFillChanged(enabled);
    }

    private TvActivity player() { return playback == null ? null : playback.player; }

    private void finishPlayback() {
        PlaybackRequest request = playback;
        playback = null;
        if (request != null) {
            request.ready.complete(null);
            if (request.player != null) request.player.endConnection();
        }
        updateInstaller();
    }

    private void advertise() {
        if (nsd == null || advertisement != null || pairCode == null) return;
        NsdServiceInfo service = new NsdServiceInfo();
        String fingerprint = pairCode.fingerprint();
        // Some TV firmware requires distinct instance names across service types.
        service.setServiceName("JiandeskCast-" + fingerprint);
        service.setServiceType(Wire.SERVICE);
        service.setPort(Wire.PORT);
        service.setAttribute("id", fingerprint);
        service.setAttribute(Wire.SHORT_CODE_ATTRIBUTE, "1");
        service.setAttribute("name", TvDetails.name());
        NsdManager.RegistrationListener reg = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo info) {}
            @Override public void onServiceUnregistered(NsdServiceInfo info) {}
            @Override public void onRegistrationFailed(NsdServiceInfo info, int error) {
                ui.post(() -> { if (advertisement == this) advertisement = null; });
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo info, int error) {}
        };
        advertisement = reg;
        try { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, reg); }
        catch (RuntimeException e) { advertisement = null; }
    }

    private void stopAdvertisement() {
        if (advertisement == null) return;
        try { nsd.unregisterService(advertisement); }
        catch (RuntimeException ignored) {}
        advertisement = null;
    }

    private final class ReceiverEvents implements TvServer.Events {
        private final int generation;

        ReceiverEvents(int generation) { this.generation = generation; }

        private boolean current() { return foreground && generation == serverGeneration; }

        private void post(Runnable event) {
            ui.post(() -> { if (current()) event.run(); });
        }

        @Override public void pairCode(TvServer.PairCode value, String message, boolean paired) {
            post(() -> {
                boolean starting = pairCode == null;
                pairCode = value;
                boolean locked = server.shortCodeLocked();
                if (paired) dismissPairPrompt();
                else if (pairPrompt != null) pairPrompt.update(value, locked);
                advertise();
                if (starting || !message.isEmpty()) {
                    setStatus(message.isEmpty() ? "等待配对或投屏" : message, !locked);
                } else notifyListeners();
            });
        }

        @Override public boolean showPairCode(TvServer.PairCode requested) throws InterruptedException {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            ui.post(() -> {
                if (!result.isDone()) result.complete(current() && showPairPrompt(requested));
            });
            try { return result.get(PROMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (ExecutionException | TimeoutException e) { return false; }
            finally { result.cancel(false); }
        }

        @Override public Surface openPlayback(boolean remote) throws InterruptedException {
            PlaybackRequest request = new PlaybackRequest(remote);
            ui.post(() -> {
                if (current()) preparePlayback(request);
                else request.ready.complete(null);
            });
            try { return request.ready.get(SURFACE_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (ExecutionException | TimeoutException e) { return null; }
            finally { request.ready.cancel(false); }
        }

        @Override public void sizeChanged(int width, int height) {
            post(() -> {
                TvActivity player = player();
                if (player != null) player.setVideoSize(width, height);
            });
        }

        @Override public void pictureFill(boolean enabled) {
            post(() -> {
                TvActivity player = player();
                if (player != null) player.setPictureFill(enabled);
            });
        }

        @Override public void pictureScale(int scale) {
            post(() -> {
                TvActivity player = player();
                if (player != null) player.setPictureScale(scale);
            });
        }

        @Override public void firstFrame() {
            post(() -> {
                TvActivity player = player();
                if (player != null) player.firstFrame();
            });
        }

        @Override public void castEnded(String message) {
            post(() -> {
                finishPlayback();
                setStatus(message, "投屏已结束".equals(message));
            });
        }

        @Override public void fileProgress(String name, long bytes, long length) {
            post(() -> {
                fileStatusText = "接收中 · " + name + " · " + TvTools.size(bytes)
                        + (length < 0 ? "" : " / " + TvTools.size(length));
                setStatus("正在接收文件", true);
            });
        }

        @Override public void fileFinished(File file, String message) {
            post(() -> {
                fileStatusText = file == null ? message : "已收到 " + file.getName();
                setStatus(file == null ? message : "已收到文件", file != null);
                if (file != null && TvFiles.isApk(file)) install(file);
            });
        }

        @Override public void failed(String message) { post(() -> serverFailed(message)); }
    }
}
