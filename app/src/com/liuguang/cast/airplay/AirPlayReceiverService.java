package com.liuguang.cast.airplay;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import io.github.jqssun.airplay.bridge.NativeBridge;
import io.github.jqssun.airplay.bridge.RaopCallbackHandler;

public final class AirPlayReceiverService extends Service {
    private static final String TAG = "LiuguangAirPlay";
    private static final String CHANNEL = "airplay_receiver";
    private static final int NOTIFICATION_ID = 0x4169;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FPS = 60;
    private static final long PIN_DISPLAY_MS = 60_000;

    private static AirPlayReceiverService current;
    private static WeakReference<Activity> host = new WeakReference<>(null);

    public static void launch(Context context) {
        if (context instanceof Activity activity) host = new WeakReference<>(activity);
        Intent intent = new Intent(context, AirPlayReceiverService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (IllegalStateException | SecurityException error) {
            Log.w(TAG, "Could not start AirPlay receiver", error);
        }
    }

    static AirPlayReceiverService current() {
        return current;
    }

    private static Activity visibleHost() {
        Activity activity = host.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus()
                ? activity : null;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable closePin = this::dismissPin;
    private Dialog pinDialog;
    private AirPlayPlaybackActivity player;
    private boolean mirroring;
    private int width = WIDTH;
    private int height = HEIGHT;
    private boolean foreground;

    // The control thread owns the native handle and discovery registrations.
    private HandlerThread thread;
    private Handler control;
    private final ArrayList<NsdManager.RegistrationListener> registrations = new ArrayList<>();
    private NsdManager nsd;
    private WifiManager.MulticastLock multicast;
    private long handle;
    private int connections;
    private String name;
    private volatile boolean closing;
    private volatile ReceiverCallbacks callbacks;
    private AirPlayVideoRenderer video;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("AirPlayControl");
        thread.start();
        control = new Handler(thread.getLooper());
        video = new AirPlayVideoRenderer(this);
        current = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground) {
            try {
                Bundle profile = getPackageManager().getServiceInfo(
                        new ComponentName(this, AirPlayReceiverService.class), PackageManager.GET_META_DATA).metaData;
                name = profile.getString("com.liuguang.cast.airplay.NAME");
                int port = profile.getInt("com.liuguang.cast.airplay.PORT");
                if (name == null || name.isEmpty() || port <= 0 || port > 65535) {
                    throw new IllegalArgumentException("Missing AirPlay service name or port");
                }
                promote();
                foreground = true;
                control.post(() -> startServer(port));
            } catch (PackageManager.NameNotFoundException | RuntimeException error) {
                Log.e(TAG, "AirPlay service could not start", error);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        closing = true;
        if (current == this) current = null;
        finishPlayback();
        control.post(() -> {
            try {
                stopServer();
            } finally {
                video.close();
                thread.quitSafely();
            }
        });
        super.onDestroy();
    }

    private void promote() {
        Notification.Builder notification;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Mac 投屏接收", NotificationManager.IMPORTANCE_LOW));
            notification = new Notification.Builder(this, CHANNEL);
        } else {
            notification = new Notification.Builder(this);
        }
        startForeground(NOTIFICATION_ID, notification
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(name)
                .setContentText("等待 Mac 屏幕镜像")
                .setOngoing(true)
                .build());
    }

    private void startServer(int port) {
        if (closing) return;
        try {
            NativeBridge nativeBridge = NativeBridge.INSTANCE;
            AudioManager audio = getSystemService(AudioManager.class);
            nativeBridge.nativeSetDefaultStreamValues(
                    audioProperty(audio, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
                    audioProperty(audio, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            ReceiverCallbacks events = new ReceiverCallbacks(port);
            callbacks = events;
            File key = new File(getFilesDir(), "airplay.pem");
            handle = nativeBridge.nativeInit(events, receiverAddress(), name, key.getAbsolutePath(), false, true);
            if (handle == 0) throw new IllegalStateException("AirPlay runtime failed to initialize");

            nativeBridge.nativeSetH265Enabled(handle, false);
            nativeBridge.nativeSetCodecs(handle, false, true);
            nativeBridge.nativeSetHlsEnabled(handle, false);
            nativeBridge.nativeSetLang(handle, Locale.getDefault().toLanguageTag());
            nativeBridge.nativeSetFeature(handle, NativeBridge.FT_MIRROR, true);
            nativeBridge.nativeSetFeature(handle, NativeBridge.FT_AUDIO, true);
            nativeBridge.nativeSetDisplaySize(handle, WIDTH, HEIGHT, FPS);
            nativeBridge.nativeSetPlist(handle, "maxFPS", FPS);
            nativeBridge.nativeSetPlist(handle, "overscanned", 0);
            nativeBridge.nativeServerAudioConfigure(handle, 0, 95, 0, false, false, true, true, false);
            int boundPort = nativeBridge.nativeStart(handle, port);
            if (boundPort <= 0) throw new IllegalStateException("AirPlay runtime could not listen on " + port);

            nsd = getSystemService(NsdManager.class);
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            if (wifi != null && multicast == null) {
                multicast = wifi.createMulticastLock("AirPlayReceiver");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
            announce(nativeBridge.nativeGetServerName(handle), "_airplay._tcp", boundPort,
                    nativeBridge.nativeGetAirplayTxtRecords(handle));
            announce(nativeBridge.nativeGetRaopServiceName(handle), "_raop._tcp", boundPort,
                    nativeBridge.nativeGetRaopTxtRecords(handle));
            Log.i(TAG, "Started " + name + " on port " + boundPort);
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "AirPlay receiver failed to start", error);
            stopSelf();
        }
    }

    private void stopServer() {
        callbacks = null;
        connections = 0;
        for (NsdManager.RegistrationListener listener : registrations) {
            try {
                nsd.unregisterService(listener);
            } catch (RuntimeException error) {
                Log.d(TAG, "AirPlay announcement already removed", error);
            }
        }
        registrations.clear();
        if (multicast != null) {
            if (multicast.isHeld()) multicast.release();
            multicast = null;
        }
        if (handle != 0) {
            NativeBridge.INSTANCE.nativeStop(handle);
            NativeBridge.INSTANCE.nativeDestroy(handle);
            handle = 0;
        }
    }

    private void announce(String serviceName, String type, int port, Map<String, String> attributes) {
        if (serviceName == null || attributes == null) {
            throw new IllegalStateException("AirPlay DNS-SD data unavailable");
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(type);
        info.setPort(port);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            info.setAttribute(entry.getKey(), entry.getValue());
        }
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo service) {
                Log.d(TAG, "Discovered as " + service.getServiceName() + " " + type);
            }
            @Override public void onRegistrationFailed(NsdServiceInfo service, int error) {
                Log.w(TAG, "Could not announce " + type + ", error " + error);
            }
            @Override public void onServiceUnregistered(NsdServiceInfo service) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo service, int error) {
                Log.w(TAG, "Could not withdraw " + type + ", error " + error);
            }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        registrations.add(listener);
    }

    private byte[] receiverAddress() {
        SharedPreferences prefs = getSharedPreferences("airplay", MODE_PRIVATE);
        String saved = prefs.getString("receiver_address", "");
        if (!saved.isEmpty()) {
            byte[] address = Base64.decode(saved, Base64.NO_WRAP);
            if (address.length == 6) return address;
        }
        byte[] address = new byte[6];
        new SecureRandom().nextBytes(address);
        address[0] = (byte) ((address[0] & 0xfc) | 2);
        prefs.edit().putString("receiver_address", Base64.encodeToString(address, Base64.NO_WRAP)).apply();
        return address;
    }

    private static int audioProperty(AudioManager audio, String property) {
        try {
            return Integer.parseInt(audio.getProperty(property));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private void presentPin(String pin) {
        if (mirroring || pin == null || !pin.matches("[0-9]{4}")) return;
        boolean overlay = Settings.canDrawOverlays(this);
        Activity activity = overlay ? null : visibleHost();
        if (!overlay && activity == null) return;
        dismissPin();
        try {
            pinDialog = AirPlayPairPrompt.show(this, activity, name, pin);
            main.postDelayed(closePin, PIN_DISPLAY_MS);
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException | SecurityException error) {
            Log.w(TAG, "Could not show AirPlay pairing code", error);
        }
    }

    private void dismissPin() {
        main.removeCallbacks(closePin);
        if (pinDialog != null) {
            pinDialog.dismiss();
            pinDialog = null;
        }
    }

    private void openPlayback() {
        dismissPin();
        if (mirroring) return;
        mirroring = true;
        try {
            Intent intent = new Intent(this, AirPlayPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Activity activity = visibleHost();
            if (activity != null) activity.startActivity(intent);
            else startActivity(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not show AirPlay playback", error);
        }
    }

    private void finishPlayback() {
        mirroring = false;
        dismissPin();
        if (player != null) {
            AirPlayPlaybackActivity activity = player;
            player = null;
            video.setSurface(null);
            activity.finish();
        }
        width = WIDTH;
        height = HEIGHT;
    }

    boolean attachPlayer(AirPlayPlaybackActivity activity) {
        if (!mirroring || closing) return false;
        player = activity;
        activity.setVideoSize(width, height);
        return true;
    }

    void playerSurface(AirPlayPlaybackActivity activity, Surface surface) {
        if (player == activity) video.setSurface(surface);
    }

    void detachPlayer(AirPlayPlaybackActivity activity) {
        if (player != activity) return;
        video.setSurface(null);
        player = null;
    }

    void disconnect(AirPlayPlaybackActivity activity) {
        if (player != activity) return;
        ReceiverCallbacks session = callbacks;
        finishPlayback();
        video.endSession();
        if (session != null) session.onControl(() -> {
            stopServer();
            startServer(session.port);
        });
    }

    private final class ReceiverCallbacks implements RaopCallbackHandler {
        private final int port;
        private volatile float clientVolume;

        private ReceiverCallbacks(int port) {
            this.port = port;
        }

        private boolean active() {
            return !closing && callbacks == this;
        }

        private void onMain(Runnable action) {
            main.post(() -> {
                if (active()) action.run();
            });
        }

        private void onControl(Runnable action) {
            control.post(() -> {
                if (active() && handle != 0) action.run();
            });
        }

        @Override public void onDisplayPin(String pin) {
            onMain(() -> presentPin(pin));
        }

        @Override public void onMirrorRunning(boolean running) {
            if (!active()) return;
            if (running) {
                video.startSession();
                onMain(AirPlayReceiverService.this::openPlayback);
            } else {
                video.endSession();
                onMain(AirPlayReceiverService.this::finishPlayback);
            }
        }

        @Override public void onVideoData(byte[] data, long timestampNs, boolean hevc) {
            if (active()) video.onVideoData(data, timestampNs, hevc);
        }

        @Override public void onVideoSize(float sourceWidth, float sourceHeight, float videoWidth, float videoHeight) {
            if (!active() || !(videoWidth >= 16 && videoWidth <= 8192 && videoHeight >= 16 && videoHeight <= 8192)) return;
            int newWidth = Math.round(videoWidth);
            int newHeight = Math.round(videoHeight);
            video.setSize(newWidth, newHeight);
            onMain(() -> {
                width = newWidth;
                height = newHeight;
                if (player != null) player.setVideoSize(width, height);
            });
        }

        @Override public void onAudioFormat(int type, int samplesPerFrame, boolean usingScreen) {
            onMain(AirPlayReceiverService.this::dismissPin);
            onControl(() -> {
                NativeBridge.INSTANCE.nativeServerAudioStart(handle);
                NativeBridge.INSTANCE.nativeServerAudioFormat(handle, type, samplesPerFrame);
            });
        }

        @Override public void onClientVolume(float db) {
            if (Float.isNaN(db)) return;
            clientVolume = Math.max(-144f, Math.min(db, 0f));
            onControl(() -> {
                float gain = clientVolume <= -144f ? 0f : (float) Math.pow(10, clientVolume / 20);
                NativeBridge.INSTANCE.nativeServerAudioSetClientGain(handle, gain);
            });
        }

        @Override public float onClientVolumeQuery() {
            return clientVolume;
        }

        @Override public void onConnectionInit() {
            onControl(() -> connections++);
        }

        @Override public void onConnectionDestroy() {
            onControl(() -> {
                connections = Math.max(connections - 1, 0);
                if (connections == 0) {
                    NativeBridge.INSTANCE.nativeServerAudioStop(handle);
                    clientVolume = 0;
                    NativeBridge.INSTANCE.nativeServerAudioSetClientGain(handle, 1);
                }
            });
        }

        @Override public void onConnectionReset(int reason) {}
        @Override public void onAudioTeardown() {}
    }
}
