package com.liuguang.cast.tv;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.liuguang.cast.shared.Wire;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class TvVideo implements AutoCloseable {
    private final Surface surface;
    private final TvServer.Events events;
    private final Runnable failed;
    private final TvPlaybackClock clock;
    private final ArrayBlockingQueue<Wire.Packet> pending = new ArrayBlockingQueue<>(18);
    private final Thread worker;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private volatile boolean running = true;
    volatile boolean hasFrame;
    private MediaCodec codec;
    private long rendered;

    TvVideo(Surface surface, TvServer.Events events, Runnable failed, TvPlaybackClock clock) {
        this.surface = surface;
        this.events = events;
        this.failed = failed;
        this.clock = clock;
        worker = new Thread(this::decode, "tv-video");
        worker.start();
    }

    void enqueue(Wire.Packet p) throws IOException, InterruptedException {
        if (!running || !pending.offer(p, 400, TimeUnit.MILLISECONDS)) {
            throw new IOException("TV video decoder is not keeping up");
        }
    }

    private void decode() {
        try {
            while (running) {
                Wire.Packet packet = pending.poll(8, TimeUnit.MILLISECONDS);
                if (packet != null) {
                    if (packet.type == Wire.AVC) setup(packet);
                    else {
                        clock.waitForDecode(packet.pts);
                        input(packet);
                    }
                }
                drain();
            }
        } catch (Exception e) {
            if (running) {
                Log.e("LiuguangTV", "video decoder", e);
                failed.run();
            }
        } finally {
            running = false;
            release();
            if (rendered > 0) Log.i("LiuguangTV", "Session rendered " + rendered + " video frames");
        }
    }

    private void setup(Wire.Packet p) throws IOException {
        release();
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p.width, p.height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(p.data));
        if (p.extra.length > 0) format.setByteBuffer("csd-1", ByteBuffer.wrap(p.extra));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, surface, null, 0);
        codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        codec.start();
        events.sizeChanged(p.width, p.height);
        Log.i("LiuguangTV", "Video stream " + p.width + "x" + p.height + " / " + codec.getName());
    }

    private void input(Wire.Packet p) throws IOException {
        if (codec == null) throw new IOException("missing H.264 configuration");
        int index;
        long until = SystemClock.elapsedRealtime() + 200;
        while ((index = codec.dequeueInputBuffer(6000)) < 0) {
            drain();
            if (SystemClock.elapsedRealtime() > until || !running) throw new IOException("decoder input stalled");
        }
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null || p.data.length > buffer.capacity()) throw new IOException("oversized H.264 frame");
        buffer.clear();
        buffer.put(p.data);
        codec.queueInputBuffer(index, 0, p.data.length, p.pts, 0);
    }

    private void drain() {
        if (codec == null) return;
        for (int i = 0; i < 18; i++) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (index < 0) return;
            boolean render = info.size > 0;
            long presentation = render ? clock.presentationTime(info.presentationTimeUs) : 0;
            if (presentation > 0) codec.releaseOutputBuffer(index, presentation);
            else codec.releaseOutputBuffer(index, render);
            if (render) {
                if (!hasFrame) {
                    hasFrame = true;
                    events.firstFrame();
                }
                if (++rendered == 1 || rendered % 60 == 0) {
                    Log.i("LiuguangTV", "Rendered video frame " + rendered);
                }
            }
        }
    }

    private void release() {
        if (codec == null) return;
        try { codec.stop(); } catch (RuntimeException ignored) {}
        codec.release();
        codec = null;
    }

    @Override public void close() {
        running = false;
        worker.interrupt();
        try { worker.join(1300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
