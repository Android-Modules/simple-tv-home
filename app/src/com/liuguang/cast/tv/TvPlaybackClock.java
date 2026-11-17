package com.liuguang.cast.tv;

import java.util.concurrent.TimeUnit;

final class TvPlaybackClock {
    private static final long MAX_DELAY = TimeUnit.MILLISECONDS.toNanos(450);
    private static final long MAX_AGE = TimeUnit.MILLISECONDS.toNanos(600);
    private static final long DECODER_LEAD = TimeUnit.MILLISECONDS.toNanos(45);

    private record Mapping(long offset, long updated) {}

    private volatile Mapping mapping;

    void update(long audioPtsUs, long presentationNs) {
        long now = System.nanoTime();
        long offset = presentationNs - audioPtsUs * 1000;
        Mapping previous = mapping;
        if (previous != null && now - previous.updated < MAX_AGE) {
            offset = previous.offset + (offset - previous.offset) / 8;
        }
        mapping = new Mapping(offset, now);
    }

    long presentationTime(long videoPtsUs) {
        Mapping current = mapping;
        if (current == null || videoPtsUs > Long.MAX_VALUE / 1000) return 0;
        long now = System.nanoTime();
        if (now - current.updated > MAX_AGE) return 0;
        long target = current.offset + videoPtsUs * 1000;
        long delay = target - now;
        return delay > 0 && delay <= MAX_DELAY ? target : 0;
    }

    void waitForDecode(long ptsUs) throws InterruptedException {
        long wait = presentationTime(ptsUs) - System.nanoTime() - DECODER_LEAD;
        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
    }
}
