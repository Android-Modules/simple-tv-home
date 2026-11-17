package com.liuguang.cast.airplay;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/** MediaCodec renderer for the Annex B video delivered by Android AirPlay Server. */
public final class AirPlayVideoRenderer implements AutoCloseable {
    private static final String TAG = "AirPlayVideo";
    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final int MAX_BUFFERED_FRAMES = 120;
    private static final int MAX_BUFFERED_BYTES = 12 * 1024 * 1024;
    private static final int MAX_PARAMETER_SET_BYTES = 64 * 1024;
    private static final long MAX_CLOCK_OFFSET_NS = 2_000_000_000L;
    private static final int PICTURE = 1;
    private static final int KEY_FRAME = 2;

    private final Object lock = new Object();
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final Thread decoderThread;
    private final boolean fireTv;
    private int pendingBytes;
    private boolean closed;
    private boolean active;
    private boolean waitingForKeyFrame = true;
    private boolean streamHevc;
    private Surface surface;
    private int width;
    private int height;
    private int frameRate = 60;
    private long ntpBaseNs;
    private long monotonicBaseNs;
    private byte[] vps;
    private byte[] sps;
    private byte[] pps;
    private volatile int generation;

    // Decoder resources are owned by decoderThread.
    private final MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private int decoderGeneration;
    private long renderedFrames;
    private boolean inputBackpressure;

    public AirPlayVideoRenderer(Context context) {
        fireTv = "Amazon".equalsIgnoreCase(Build.MANUFACTURER)
                || context.getPackageManager().hasSystemFeature("amazon.hardware.fire_tv");
        decoderThread = new Thread(this::decode, "airplay-video");
        decoderThread.start();
    }

    public boolean supportsHevc(int width, int height, int fps) {
        synchronized (lock) {
            frameRate = Math.max(1, fps);
        }
        MediaCodecInfo hevc = findDecoder(HEVC, width, height, false);
        if (hevc == null) return false;
        if (lowLatency(hevc, HEVC) || knownHevcHardware(hevc)) return true;
        MediaCodecInfo avc = findDecoder(AVC, width, height, false);
        return avc != null && !supportsRate(avc, AVC, width, height, fps)
                && supportsRate(hevc, HEVC, width, height, fps);
    }

    public void startSession() {
        synchronized (lock) {
            if (closed || active) return;
            active = true;
            resetStreamLocked();
        }
    }

    public void endSession() {
        synchronized (lock) {
            if (!active) return;
            active = false;
            resetStreamLocked();
        }
    }

    public void setSurface(Surface surface) {
        synchronized (lock) {
            if (closed || this.surface == surface) return;
            if (this.surface != null) discardUntilKeyFrameLocked();
            this.surface = surface;
            invalidateDecoderLocked();
        }
    }

    public void setSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return;
        synchronized (lock) {
            if (closed || this.width == width && this.height == height) return;
            boolean initialSize = this.width == 0;
            this.width = width;
            this.height = height;
            if (!initialSize) {
                clearParameterSetsLocked();
                clearPendingLocked();
            }
            invalidateDecoderLocked();
        }
    }

    public void onVideoData(byte[] data, long ntpTimeNs, boolean isH265) {
        if (data.length == 0 || data.length > MAX_BUFFERED_BYTES) return;
        long receivedNs = System.nanoTime();
        synchronized (lock) {
            if (closed || !active) return;
            if (streamHevc != isH265) {
                streamHevc = isH265;
                clearParameterSetsLocked();
                clearPendingLocked();
                invalidateDecoderLocked();
            }
            int kind = inspectAccessUnitLocked(data, isH265);
            boolean keyFrame = kind == KEY_FRAME;
            if (kind == 0 || waitingForKeyFrame && !keyFrame) return;
            if (keyFrame && (waitingForKeyFrame || surface == null)) {
                clearPendingLocked();
            }
            if (pending.size() >= MAX_BUFFERED_FRAMES
                    || pendingBytes + data.length > MAX_BUFFERED_BYTES) {
                waitingForKeyFrame = true;
                return;
            }
            long timeUs = presentationTimeNsLocked(ntpTimeNs, receivedNs) / 1000;
            pending.addLast(new Frame(data, timeUs, keyFrame));
            pendingBytes += data.length;
            waitingForKeyFrame = false;
            lock.notifyAll();
        }
    }

    private long presentationTimeNsLocked(long ntpTimeNs, long receivedNs) {
        if (ntpTimeNs <= 0) return receivedNs;
        long target = monotonicBaseNs + (ntpTimeNs - ntpBaseNs);
        // NTP may become available after the first packets or reset on sender resume.
        if (ntpBaseNs == 0 || target - receivedNs > MAX_CLOCK_OFFSET_NS
                || receivedNs - target > MAX_CLOCK_OFFSET_NS) {
            ntpBaseNs = ntpTimeNs;
            monotonicBaseNs = receivedNs;
            return receivedNs;
        }
        return target;
    }

    private int inspectAccessUnitLocked(byte[] data, boolean hevc) {
        int start = nalStart(data, 0);
        while (start >= 0) {
            int header = start + 4;
            int type = hevc ? (data[header] & 0x7e) >> 1 : data[header] & 0x1f;
            // The native stream prefixes the parameter sets before the first video slice.
            if (hevc ? type <= 31 : type >= 1 && type <= 5) {
                boolean keyFrame = hevc ? type >= 16 && type <= 21 : type == 5;
                return keyFrame ? KEY_FRAME : PICTURE;
            }
            int next = nalStart(data, header + 1);
            int end = next < 0 ? data.length : next;
            if (end - start <= MAX_PARAMETER_SET_BYTES) {
                if (hevc && type == 32) vps = Arrays.copyOfRange(data, start, end);
                if (hevc ? type == 33 : type == 7) sps = Arrays.copyOfRange(data, start, end);
                if (hevc ? type == 34 : type == 8) pps = Arrays.copyOfRange(data, start, end);
            }
            start = next;
        }
        return 0;
    }

    private static int nalStart(byte[] data, int offset) {
        // The JNI producer converts every unit to a four-byte Annex B start code.
        for (int i = offset; i <= data.length - 5; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private boolean canDecodeLocked() {
        return active && surface != null && surface.isValid() && width > 0;
    }

    private void decode() {
        try {
            while (true) {
                Frame frame;
                Surface target;
                int version;
                StreamFormat initialFormat = null;
                synchronized (lock) {
                    if (!closed && generation == decoderGeneration
                            && (!canDecodeLocked() || pending.isEmpty())) {
                        lock.wait(codec == null ? 0 : 8);
                    }
                    if (closed) return;
                    version = generation;
                    target = canDecodeLocked() ? surface : null;
                    frame = target != null ? pending.peekFirst() : null;
                    if (frame != null && (codec == null || version != decoderGeneration)) {
                        initialFormat = new StreamFormat(width, height, frameRate, streamHevc,
                                vps, sps, pps);
                    }
                }

                if (decoderGeneration != version || target == null) {
                    releaseCodec();
                    decoderGeneration = version;
                }
                if (target == null || generation != version) continue;

                try {
                    if (codec == null && frame != null && frame.keyFrame) {
                        codec = startCodec(initialFormat, target);
                    }
                    if (frame != null) {
                        if (codec == null || queueFrame(frame)) {
                            synchronized (lock) {
                                if (version == generation) removeFrameLocked(frame);
                            }
                        }
                    }
                    if (codec != null) drainOutput();
                } catch (IOException | RuntimeException failure) {
                    Log.w(TAG, "Resetting the AirPlay video decoder", failure);
                    releaseCodec();
                    synchronized (lock) {
                        if (version == generation) {
                            removeFrameLocked(frame);
                            discardUntilKeyFrameLocked();
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            releaseCodec();
        }
    }

    private boolean queueFrame(Frame frame) throws IOException {
        int attempts = renderedFrames == 0 ? 50 : 10;
        for (int attempt = 0; attempt < attempts && generation == decoderGeneration; attempt++) {
            int index = codec.dequeueInputBuffer(20_000);
            if (index < 0) {
                drainOutput();
                continue;
            }
            ByteBuffer input = codec.getInputBuffer(index);
            if (input == null || input.capacity() < frame.data.length) {
                throw new IOException("AirPlay frame exceeds the MediaCodec input buffer");
            }
            input.clear();
            input.put(frame.data);
            codec.queueInputBuffer(index, 0, frame.data.length, frame.presentationUs, 0);
            inputBackpressure = false;
            return true;
        }
        // Waiting for buffers on an active decoder must preserve the reference frames.
        if (generation == decoderGeneration && !inputBackpressure) {
            Log.w(TAG, "Waiting for AirPlay decoder input buffers");
            inputBackpressure = true;
        }
        return false;
    }

    private void drainOutput() {
        for (int count = 0; count < 18 && generation == decoderGeneration; count++) {
            int index = codec.dequeueOutputBuffer(output, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (index < 0) return;
            if ((output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codec.releaseOutputBuffer(index, false);
                continue;
            }
            codec.releaseOutputBuffer(index, true);
            if (++renderedFrames == 1 || renderedFrames % 120 == 0) {
                Log.i(TAG, "Rendered AirPlay frames " + renderedFrames);
            }
        }
    }

    private MediaCodec startCodec(StreamFormat stream, Surface target) throws IOException {
        String mime = stream.hevc ? HEVC : AVC;
        MediaCodecInfo hardware = findDecoder(mime, stream.width, stream.height, false);
        MediaCodecInfo software = findDecoder(mime, stream.width, stream.height, true);
        if (hardware != null) {
            try {
                if (lowLatency(hardware, mime)) {
                    try {
                        return configureCodec(hardware, stream, target, true);
                    } catch (IOException | RuntimeException failure) {
                        if (generation != decoderGeneration) throw failure;
                        Log.w(TAG, "Retrying AirPlay decoder with the standard configuration", failure);
                    }
                }
                return configureCodec(hardware, stream, target, false);
            } catch (IOException | RuntimeException failure) {
                if (software == null || generation != decoderGeneration) throw failure;
                Log.w(TAG, "Trying AirPlay software decoding", failure);
            }
        }
        return configureCodec(software, stream, target, false);
    }

    private static MediaCodec configureCodec(MediaCodecInfo selected, StreamFormat stream,
                                             Surface target, boolean lowLatency) throws IOException {
        String mime = stream.hevc ? HEVC : AVC;
        MediaFormat format = MediaFormat.createVideoFormat(mime, stream.width, stream.height);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, stream.frameRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                Math.max(1024 * 1024, Math.min(MAX_BUFFERED_BYTES, stream.width * stream.height)));
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1);
        if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        stream.addParameterSets(format);

        MediaCodec decoder = selected == null ? MediaCodec.createDecoderByType(mime)
                : MediaCodec.createByCodecName(selected.getName());
        try {
            decoder.configure(format, target, null, 0);
            decoder.start();
            Log.i(TAG, mime + " " + stream.width + "x" + stream.height + " / " + decoder.getName());
            return decoder;
        } catch (RuntimeException failure) {
            try { decoder.release(); } catch (RuntimeException ignored) {}
            throw failure;
        }
    }

    private static MediaCodecInfo findDecoder(String mime, int width, int height, boolean software) {
        MediaCodecInfo best = null;
        int bestScore = 0;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() || Build.VERSION.SDK_INT >= 29 && info.isAlias()) continue;
            String name = info.getName().toLowerCase(Locale.ROOT);
            if (software != isSoftware(info, name)) continue;
            if (HEVC.equals(mime) && (name.equals("omx.qcom.video.decoder.hevcswvdec")
                    || name.equals("omx.sec.hevc.sw.dec"))) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video == null || !video.isSizeSupported(width, height)
                        && !(width < height && video.isSizeSupported(height, width))) continue;
                int score = 1;
                if (AVC.equals(mime)) {
                    for (MediaCodecInfo.CodecProfileLevel profile : caps.profileLevels) {
                        if (profile.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
                            score += 2;
                            break;
                        }
                    }
                }
                if (lowLatency(info, mime)) score++;
                if (score > bestScore) {
                    best = info;
                    bestScore = score;
                }
            } catch (RuntimeException ignored) {
                // Vendors can omit the requested MIME or report incomplete capabilities.
            }
        }
        return best;
    }

    private static boolean isSoftware(MediaCodecInfo info, String name) {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly();
        return name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.startsWith("omx.ffmpeg.") || name.contains(".sw.");
    }

    private static boolean lowLatency(MediaCodecInfo info, String mime) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            return info.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean knownHevcHardware(MediaCodecInfo info) {
        String name = info.getName().toLowerCase(Locale.ROOT);
        if (name.contains(".sw.")) return false;
        if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated()) return false;
        return name.startsWith("c2.") || name.startsWith("omx.exynos")
                || name.startsWith("omx.hisi")
                || Build.VERSION.SDK_INT >= 28 && (name.startsWith("omx.realtek")
                    || name.startsWith("omx.amlogic") && !"sabrina".equalsIgnoreCase(Build.DEVICE))
                || Build.VERSION.SDK_INT >= 26 && name.startsWith("omx.mtk")
                    && (fireTv || Build.DEVICE.startsWith("BRAVIA_"))
                || fireTv && name.startsWith("omx.amlogic");
    }

    private static boolean supportsRate(MediaCodecInfo info, String mime, int width, int height,
                                        int fps) {
        try {
            MediaCodecInfo.VideoCapabilities caps = info.getCapabilitiesForType(mime)
                    .getVideoCapabilities();
            return caps.areSizeAndRateSupported(width, height, fps)
                    || width < height && caps.areSizeAndRateSupported(height, width, fps);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void discardUntilKeyFrameLocked() {
        while (!pending.isEmpty() && !pending.peekFirst().keyFrame) {
            pendingBytes -= pending.removeFirst().data.length;
        }
        waitingForKeyFrame |= pending.isEmpty();
    }

    private void removeFrameLocked(Frame frame) {
        if (frame != null && pending.peekFirst() == frame) {
            pendingBytes -= pending.removeFirst().data.length;
        }
    }

    private void clearPendingLocked() {
        pending.clear();
        pendingBytes = 0;
        waitingForKeyFrame = true;
    }

    private void clearParameterSetsLocked() {
        vps = null;
        sps = null;
        pps = null;
    }

    private void resetStreamLocked() {
        clearPendingLocked();
        clearParameterSetsLocked();
        ntpBaseNs = 0;
        monotonicBaseNs = 0;
        invalidateDecoderLocked();
    }

    private void invalidateDecoderLocked() {
        generation++;
        lock.notifyAll();
    }

    private void releaseCodec() {
        MediaCodec oldCodec = codec;
        codec = null;
        renderedFrames = 0;
        inputBackpressure = false;
        if (oldCodec == null) return;
        try { oldCodec.stop(); } catch (RuntimeException ignored) {}
        try { oldCodec.release(); } catch (RuntimeException ignored) {}
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            active = false;
            surface = null;
            resetStreamLocked();
        }
    }

    private record Frame(byte[] data, long presentationUs, boolean keyFrame) {}

    private record StreamFormat(int width, int height, int frameRate, boolean hevc,
                                byte[] vps, byte[] sps, byte[] pps) {
        void addParameterSets(MediaFormat format) {
            if (sps == null || pps == null) return;
            if (hevc) {
                if (vps == null) return;
                ByteBuffer parameters = ByteBuffer.allocate(vps.length + sps.length + pps.length);
                parameters.put(vps).put(sps).put(pps);
                parameters.flip();
                format.setByteBuffer("csd-0", parameters);
            } else {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            }
        }
    }
}
