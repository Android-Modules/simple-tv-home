package com.liuguang.cast.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.liuguang.cast.shared.Wire;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class TvAudio implements AutoCloseable {
    interface Output { void changed(boolean playing); }

    private static final String TAG = "LiuguangTV";
    private final ArrayBlockingQueue<Wire.Packet> pending = new ArrayBlockingQueue<>(6);
    private final Output audioState;
    private final TvPlaybackClock clock;
    private Thread worker;
    private volatile boolean running = true;

    TvAudio(Output audioState, TvPlaybackClock clock) {
        this.audioState = audioState;
        this.clock = clock;
    }

    void offer(Wire.Packet pcm) {
        if (!running) return;
        if (worker == null) {
            worker = new Thread(this::play, "tv-audio");
            worker.start();
        }
        if (!pending.offer(pcm)) {
            pending.poll();
            pending.offer(pcm);
        }
    }

    private void play() {
        AudioTrack track = null;
        boolean playingSound = false;
        long lastSound = 0;
        long writtenFrames = 0;
        AudioTimestamp stamp = new AudioTimestamp();
        try {
            AudioFormat format = new AudioFormat.Builder().setSampleRate(Wire.AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            int min = AudioTrack.getMinBufferSize(Wire.AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack.Builder builder = new AudioTrack.Builder().setAudioFormat(format)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setBufferSizeInBytes(Math.max(7680, min)).setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            track = builder.build();
            if (Build.VERSION.SDK_INT >= 24) track.setBufferSizeInFrames(Wire.AUDIO_RATE * 60 / 1000);
            track.play();
            while (running) {
                Wire.Packet packet = pending.poll(250, TimeUnit.MILLISECONDS);
                if (!running) break;
                boolean audible = false;
                if (packet != null) {
                    byte[] pcm = packet.data;
                    int written = track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IllegalStateException("AudioTrack write failed: " + written);
                    audible = hasSound(pcm, written);
                    if (written > 0 && packet.pts > 0 && (playingSound || audible) && track.getTimestamp(stamp)) {
                        long presentation = stamp.nanoTime
                                + (writtenFrames - stamp.framePosition) * 1_000_000_000L / Wire.AUDIO_RATE;
                        clock.update(packet.pts, presentation);
                    }
                    writtenFrames += written / Wire.AUDIO_BYTES_PER_FRAME;
                }
                long now = SystemClock.elapsedRealtime();
                if (audible) {
                    lastSound = now;
                    if (!playingSound) {
                        playingSound = true;
                        Log.i(TAG, "Playing captured media audio");
                        audioState.changed(true);
                    }
                } else if (playingSound && now - lastSound >= 1500) {
                    playingSound = false;
                    audioState.changed(false);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "audio output unavailable", e);
        } finally {
            running = false;
            if (playingSound) audioState.changed(false);
            if (track != null) track.release();
        }
    }

    private static boolean hasSound(byte[] pcm, int length) {
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (pcm[i] & 255) | (pcm[i + 1] << 8);
            if (sample < -24 || sample > 24) return true;
        }
        return false;
    }

    @Override public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
