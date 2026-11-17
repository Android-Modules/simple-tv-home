package com.liuguang.cast.tv;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.liuguang.cast.shared.LanIpv4;
import com.liuguang.cast.shared.Pairing;
import com.liuguang.cast.shared.ShortPairing;
import com.liuguang.cast.shared.TvTools;
import com.liuguang.cast.shared.Wire;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

final class TvServer implements AutoCloseable {
    interface Events {
        void pairCode(PairCode code, String message, boolean paired);
        boolean showPairCode(PairCode code) throws InterruptedException;
        Surface openPlayback(boolean remote) throws InterruptedException;
        void sizeChanged(int width, int height);
        void pictureFill(boolean enabled);
        void pictureScale(int scale);
        void firstFrame();
        void castEnded(String message);
        void fileProgress(String name, long bytes, long length);
        void fileFinished(File file, String message);
        void failed(String message);
    }

    record PairCode(String fingerprint, String digits, String qrSecret) {}

    private record Playback(Socket socket, DataOutputStream commands, boolean remote, boolean legacy,
                            AtomicInteger pendingPictureMode) {}

    private static final String TAG = "LiuguangTV";
    private static final long REMOTE_INTERVAL_MS = 210;
    private static final long PAIR_PROMPT_INTERVAL_MS = 8000;
    private static final int PAIR_ATTEMPT_LIMIT = 5;
    private final Context context;
    private final Events events;
    private final ServerSocket listener = new ServerSocket();
    private final TvPairings pairings;
    private final TvDiscoveryResponder discovery;
    private final Set<Socket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Semaphore upload = new Semaphore(1);
    private final Semaphore export = new Semaphore(1);
    private final ThreadPoolExecutor workers = executor(5, "tv-link");
    private final ThreadPoolExecutor control = executor(1, "tv-remote");
    private volatile Playback playback;
    private volatile boolean running = true;
    private String pin;
    private PairCode pairCode;
    private long lastPairPrompt = -PAIR_PROMPT_INTERVAL_MS;
    private int shortAttempts, qrFailures;
    private volatile long lastControl;

    TvServer(Context context, Events events) throws IOException {
        this.context = context.getApplicationContext();
        this.events = events;
        pairings = new TvPairings(this.context);
        discovery = new TvDiscoveryResponder(this.context);
    }

    private static ThreadPoolExecutor executor(int max, String name) {
        return new ThreadPoolExecutor(0, max, 20, TimeUnit.SECONDS, new SynchronousQueue<>(),
                r -> new Thread(r, name));
    }

    void start() { new Thread(this::serve, "tv-server").start(); }

    private void serve() {
        try {
            TvIdentity identity = TvIdentity.load(context);
            if (!running) return;
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(Wire.PORT));
            discovery.start(identity.pin());
            synchronized (this) {
                pin = identity.pin();
                rotate("");
            }
            while (running) {
                Socket raw = listener.accept();
                clients.add(raw);
                try { workers.execute(() -> handle(identity.context(), raw)); }
                catch (RejectedExecutionException e) {
                    clients.remove(raw);
                    closeQuietly(raw);
                }
            }
        } catch (Exception e) {
            if (running) {
                Log.e(TAG, "receiver startup failed", e);
                events.failed("接收端启动失败，请用遥控器重试。");
            }
        } finally { close(); }
    }

    private void handle(SSLContext tlsContext, Socket raw) {
        try (Socket socket = raw) {
            if (!running) return;
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            try (SSLSocket tls = (SSLSocket) tlsContext.getSocketFactory().createSocket(
                    socket, socket.getInetAddress().getHostAddress(), socket.getPort(), false)) {
                tls.setUseClientMode(false);
                Pairing.restrictProtocols(tls);
                tls.startHandshake();
                DataInputStream in = new DataInputStream(new BufferedInputStream(tls.getInputStream(), 64 * 1024));
                DataOutputStream out = new DataOutputStream(tls.getOutputStream());
                int magic = in.readInt();
                if (magic == Wire.REQUEST_PAIR_CODE_MAGIC) {
                    PairCode requested = reservePairPrompt(socket.getInetAddress());
                    reply(out, requested != null && events.showPairCode(requested) ? Wire.ACCEPT : Wire.REJECT);
                    return;
                }
                if (magic == Wire.SHORT_PAIR_MAGIC) {
                    pairShort(tls, in, out);
                    return;
                }
                if (magic == Wire.MAGIC) {
                    if (authenticateQr(readAscii(in, Pairing.SECRET_LENGTH)) != null) {
                        cast(socket, tls, in, out, false, true);
                    } else reply(out, Wire.REJECT);
                    return;
                }
                if (magic != Wire.SESSION_MAGIC) return;
                int operation = in.readUnsignedByte();
                if (operation == Wire.PAIR_DEVICE) {
                    finishPairing(authenticateQr(readAscii(in, Pairing.SECRET_LENGTH)), out);
                    return;
                }
                if (!pairings.accepts(readAscii(in, Pairing.TOKEN_LENGTH))) {
                    reply(out, Wire.REJECT);
                    return;
                }
                switch (operation) {
                    case Wire.START_CAST -> cast(socket, tls, in, out, in.readBoolean(), false);
                    case Wire.GET_TV, Wire.GET_TV_WITH_ICONS -> {
                        boolean icons = operation == Wire.GET_TV_WITH_ICONS;
                        out.writeByte(Wire.ACCEPT);
                        TvDetails.info(context).write(out);
                        TvTools.writeApps(out, TvDetails.apps(context, icons), icons);
                        out.flush();
                    }
                    case Wire.SEND_FILE -> {
                        reply(out, Wire.ACCEPT);
                        tls.setSoTimeout(15_000);
                        receiveFile(in, out);
                    }
                    case Wire.GET_APP, Wire.EXPORT_APP -> manageApp(operation, in, out);
                    default -> reply(out, Wire.REJECT);
                }
            }
        } catch (Exception e) {
            if (running && !(e instanceof EOFException) && !(e instanceof SocketTimeoutException)) {
                Log.d(TAG, "peer connection ended: " + e.getClass().getSimpleName());
            }
        } finally { clients.remove(raw); }
    }

    private void pairShort(SSLSocket tls, DataInputStream in, DataOutputStream out)
            throws IOException, GeneralSecurityException {
        PairCode expected = beginShortPairing();
        if (expected == null) {
            reply(out, Wire.PAIR_RETRY_LATER);
            return;
        }
        try {
            reply(out, Wire.ACCEPT);
            X509Certificate television = (X509Certificate) tls.getSession().getLocalCertificates()[0];
            ShortPairing.confirm(in, out, television, expected.digits(), true);
        } catch (GeneralSecurityException | IOException e) {
            synchronized (this) {
                if (expected == currentCode() && shortAttempts >= PAIR_ATTEMPT_LIMIT) {
                    events.pairCode(expected, "配对尝试过多，请在电视上更新投屏码。", false);
                }
            }
            return;
        }
        finishPairing(expected, out);
    }

    private void finishPairing(PairCode expected, DataOutputStream out) throws IOException, GeneralSecurityException {
        String token;
        boolean valid;
        synchronized (this) {
            valid = expected != null && expected == currentCode();
            token = valid ? pairings.issue() : null;
            if (token != null) rotate("已配对手机", true);
        }
        if (token == null) {
            reply(out, valid ? Wire.PAIR_LIMIT : Wire.REJECT);
            return;
        }
        out.writeByte(Wire.ACCEPT);
        out.writeUTF(TvDetails.name());
        out.writeUTF(token);
        out.flush();
    }

    private synchronized PairCode beginShortPairing() {
        PairCode current = currentCode();
        if (current == null || shortAttempts >= PAIR_ATTEMPT_LIMIT) return null;
        shortAttempts++;
        return current;
    }

    private synchronized PairCode authenticateQr(String supplied) {
        PairCode current = currentCode();
        if (current == null || qrFailures >= PAIR_ATTEMPT_LIMIT) return null;
        if (Pairing.matches(current.qrSecret(), supplied)) return current;
        qrFailures++;
        return null;
    }

    private PairCode reservePairPrompt(InetAddress peer) {
        boolean local = false;
        for (LanIpv4 link : LanIpv4.links(context)) {
            if (link.contains(peer)) {
                local = true;
                break;
            }
        }
        if (!local) return null;
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            PairCode current = currentCode();
            if (current == null || now - lastPairPrompt < PAIR_PROMPT_INTERVAL_MS) return null;
            lastPairPrompt = now;
            return current;
        }
    }

    private PairCode currentCode() {
        return running ? pairCode : null;
    }

    synchronized boolean shortCodeLocked() {
        return shortAttempts >= PAIR_ATTEMPT_LIMIT;
    }

    synchronized void refresh() {
        if (running && pin != null) rotate("投屏码已更新");
    }

    synchronized void clearPairings() {
        if (!running || pin == null) return;
        pairings.clear();
        rotate("已解除所有手机配对");
    }

    private void rotate(String message) {
        rotate(message, false);
    }

    private void rotate(String message, boolean paired) {
        String digits;
        do { digits = Pairing.shortCode(); }
        while (pairCode != null && digits.equals(pairCode.digits()));
        shortAttempts = 0;
        qrFailures = 0;
        pairCode = new PairCode(pin, digits, Pairing.codeSecret());
        events.pairCode(pairCode, message, paired);
    }

    private void cast(Socket raw, SSLSocket tls, DataInputStream in, DataOutputStream out,
                      boolean remote, boolean legacy) throws IOException {
        Playback active;
        synchronized (this) {
            active = running && playback == null ? new Playback(raw, out, remote, legacy, new AtomicInteger()) : null;
            if (active != null) {
                lastControl = 0;
                playback = active;
            }
        }
        if (active == null) {
            reply(out, legacy ? Wire.REJECT : Wire.BUSY);
            return;
        }
        String ended = "投屏已结束";
        boolean accepted = false;
        try {
            Surface surface = events.openPlayback(remote);
            if (surface == null || !surface.isValid() || !running || raw.isClosed()) {
                ended = "无法打开投屏画面，请返回简洁桌面后重试。";
                reply(out, Wire.REJECT);
                return;
            }
            reply(out, Wire.ACCEPT);
            accepted = true;
            if (!active.legacy()) {
                sendControl(active, Wire.USE_AUDIO_CLOCK);
                sendControl(active, Wire.SUPPORTS_PICTURE_FILL);
                sendControl(active, Wire.SUPPORTS_PICTURE_SCALE);
                sendControl(active, Wire.SUPPORTS_LIVE_PICTURE_FILL);
            }
            tls.setSoTimeout(7000);
            TvPlaybackClock clock = new TvPlaybackClock();
            try (TvVideo video = new TvVideo(surface, events, () -> closeQuietly(raw), clock);
                 TvAudio audio = new TvAudio(playing -> {
                     if (!active.legacy()) sendControl(active, playing ? Wire.AUDIO_PLAYING : Wire.AUDIO_IDLE);
                 }, clock)) {
                long startupBy = SystemClock.elapsedRealtime() + 15_000;
                while (running && !raw.isClosed()) {
                    Wire.Packet packet = Wire.read(in);
                    if (!video.hasFrame && SystemClock.elapsedRealtime() > startupBy) {
                        throw new IOException("no video received");
                    }
                    if (packet.type == Wire.AVC || packet.type == Wire.VIDEO) video.enqueue(packet);
                    else if (packet.type == Wire.PCM || packet.type == Wire.TIMED_PCM) audio.offer(packet);
                    else if ((packet.type == Wire.FILL_PICTURE || packet.type == Wire.FIT_PICTURE) && !active.legacy()) {
                        events.pictureFill(packet.type == Wire.FILL_PICTURE);
                    }
                    else if (packet.type == Wire.SCALE_PICTURE && !active.legacy()) {
                        events.pictureScale(packet.pictureScale);
                    }
                    int pictureMode = active.pendingPictureMode().getAndSet(0);
                    if (pictureMode != 0) sendControl(active, pictureMode);
                }
            }
        } catch (EOFException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running && !raw.isClosed()) {
                ended = "投屏连接已中断";
                Log.w(TAG, "cast ended", e);
            }
        } finally {
            synchronized (this) {
                playback = null;
                if (running) {
                    if (active.legacy() && accepted) rotate("");
                    events.castEnded(ended);
                }
            }
        }
    }

    void pictureFillChanged(boolean enabled) {
        Playback active = playback;
        if (active != null && !active.legacy()) {
            active.pendingPictureMode().set(enabled ? Wire.PICTURE_FILL_ON : Wire.PICTURE_FILL_OFF);
        }
    }

    boolean remote(int command) {
        Playback active = playback;
        long now = SystemClock.elapsedRealtime();
        if (active == null || !active.remote() || now - lastControl < REMOTE_INTERVAL_MS) return false;
        try {
            control.execute(() -> sendControl(active, command));
            lastControl = now;
            return true;
        } catch (RejectedExecutionException ignored) { return false; }
    }

    private void sendControl(Playback active, int command) {
        if (active != playback || active.socket().isClosed()) return;
        try {
            synchronized (active.commands()) { reply(active.commands(), command); }
        } catch (IOException e) {
            closeQuietly(active.socket());
        }
    }

    private void receiveFile(DataInputStream in, DataOutputStream out) throws IOException {
        if (!upload.tryAcquire()) {
            fileError(out, "电视正在接收另一个文件，请稍后再试。");
            return;
        }
        File received = null;
        try {
            received = TvFiles.receive(context, in, out, events::fileProgress);
            out.writeByte(Wire.ACCEPT);
            out.writeUTF(received.getName());
            out.flush();
        } catch (IOException e) {
            String detail = e.getMessage();
            if (detail == null || !detail.matches(".*[\\p{IsHan}].*")) detail = "文件传输中断，请重试。";
            fileError(out, detail);
        } finally {
            events.fileFinished(received, received == null ? "文件未能保存" : "");
            upload.release();
        }
    }

    private void manageApp(int operation, DataInputStream in, DataOutputStream out) throws IOException {
        reply(out, Wire.ACCEPT);
        String packageName = in.readUTF();
        TvApks.Installed installed;
        try { installed = TvApks.lookup(context, packageName); }
        catch (IOException e) {
            fileError(out, e.getMessage());
            return;
        }
        TvTools.AppDetails details = installed.details();
        if (operation == Wire.GET_APP) {
            out.writeByte(Wire.ACCEPT);
            details.write(out);
            out.flush();
            return;
        }
        long version = in.readLong(), updatedAt = in.readLong();
        if (version != details.versionCode() || updatedAt != details.updatedAt()) {
            fileError(out, "电视上的应用版本已变更，请重新打开详情。");
            return;
        }
        if (!details.exportable()) {
            fileError(out, "电视无法读取此应用的完整安装包。");
            return;
        }
        if (!export.tryAcquire()) {
            fileError(out, "电视正在导出另一个应用，请稍后再试。");
            return;
        }
        try { installed.send(out); }
        finally { export.release(); }
    }

    private static String readAscii(DataInputStream in, int size) throws IOException {
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void reply(DataOutputStream out, int value) throws IOException {
        out.writeByte(value);
        out.flush();
    }

    private static void fileError(DataOutputStream out, String message) throws IOException {
        out.writeByte(Wire.REJECT);
        out.writeUTF(message);
        out.flush();
    }

    void disconnect() {
        Playback active = playback;
        if (active != null) closeQuietly(active.socket());
    }

    @Override public void close() {
        running = false;
        discovery.close();
        closeQuietly(listener);
        for (Socket client : clients) closeQuietly(client);
        workers.shutdownNow();
        control.shutdownNow();
    }

    private static void closeQuietly(Closeable closeable) {
        try { closeable.close(); } catch (IOException ignored) {}
    }
}
