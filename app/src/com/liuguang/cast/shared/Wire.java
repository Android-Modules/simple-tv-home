package com.liuguang.cast.shared;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Binary packets carried only inside an authenticated TLS connection. */
public final class Wire {
    public static final int PORT = 45454;
    public static final String SERVICE = "_liuguangcast._tcp.";
    public static final String SHORT_CODE_ATTRIBUTE = "sc";
    public static final int MAGIC = 0x4c474331; // LGC1
    public static final int SESSION_MAGIC = 0x4c474332; // LGC2
    public static final int SHORT_PAIR_MAGIC = 0x4c474333; // LGC3
    public static final int REQUEST_PAIR_CODE_MAGIC = 0x4c474334; // LGC4
    public static final int ACCEPT = 0, REJECT = 1, BUSY = 2, PAIR_LIMIT = 3;
    public static final int PAIR_RETRY_LATER = 4;
    public static final int PAIR_DEVICE = 1, START_CAST = 2, GET_TV = 3, SEND_FILE = 4;
    public static final int GET_TV_WITH_ICONS = 5, GET_APP = 6, EXPORT_APP = 7;
    public static final int PREVIOUS_VIDEO = 1, NEXT_VIDEO = 2;
    public static final int AUDIO_PLAYING = 3, AUDIO_IDLE = 4;
    public static final int USE_AUDIO_CLOCK = 5;
    public static final int SUPPORTS_PICTURE_FILL = 6;
    public static final int SUPPORTS_PICTURE_SCALE = 7;
    public static final int SUPPORTS_LIVE_PICTURE_FILL = 8, PICTURE_FILL_ON = 9, PICTURE_FILL_OFF = 10;
    public static final int PING = 0, AVC = 1, VIDEO = 2, PCM = 3, TIMED_PCM = 4;
    public static final int FILL_PICTURE = 5, SCALE_PICTURE = 6, FIT_PICTURE = 7;
    // Scale is sent as tenths, so 10 means 1.0×.
    public static final int MIN_SCALE = 4, DEFAULT_SCALE = 10, MAX_SCALE = 20;
    public static final int KEY_FRAME = 1;
    public static final int AUDIO_RATE = 48000, AUDIO_BYTES_PER_FRAME = 4;
    private static final int MAX_CSD = 16384, MAX_VIDEO = 2 * 1024 * 1024, MAX_PCM = 16384;

    private Wire() {}

    public static void ping(DataOutputStream out) throws IOException {
        out.writeByte(PING);
    }

    public static void pictureFill(DataOutputStream out, boolean enabled) throws IOException {
        out.writeByte(enabled ? FILL_PICTURE : FIT_PICTURE);
    }

    public static boolean validPictureScale(int scale) {
        return scale >= MIN_SCALE && scale <= MAX_SCALE;
    }

    public static void pictureScale(DataOutputStream out, int scale) throws IOException {
        if (!validPictureScale(scale)) throw invalid();
        out.writeByte(SCALE_PICTURE);
        out.writeByte(scale);
    }

    public static void videoConfig(DataOutputStream out, int w, int h, byte[] sps, byte[] pps)
            throws IOException {
        checkSize(w, h);
        if (sps.length == 0) throw new IOException("缺少 H.264 参数");
        out.writeByte(AVC);
        out.writeShort(w);
        out.writeShort(h);
        writeBytes(out, sps, MAX_CSD);
        writeBytes(out, pps, MAX_CSD);
    }

    public static void video(DataOutputStream out, long pts, int flags, byte[] bytes) throws IOException {
        if (bytes.length == 0 || (flags & ~KEY_FRAME) != 0 || pts < 0) throw invalid();
        out.writeByte(VIDEO);
        out.writeLong(pts);
        out.writeByte(flags);
        writeBytes(out, bytes, MAX_VIDEO);
    }

    public static void pcm(DataOutputStream out, byte[] bytes, int size, long pts) throws IOException {
        if (size <= 0 || size > MAX_PCM || size > bytes.length || size % AUDIO_BYTES_PER_FRAME != 0) {
            throw invalid();
        }
        out.writeByte(pts > 0 ? TIMED_PCM : PCM);
        if (pts > 0) out.writeLong(pts);
        out.writeInt(size);
        out.write(bytes, 0, size);
    }

    public static Packet read(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        return switch (type) {
            case PING, FILL_PICTURE, FIT_PICTURE -> new Packet(type, 0, 0, 0, 0, null, null);
            case SCALE_PICTURE -> {
                int scale = in.readUnsignedByte();
                if (!validPictureScale(scale)) throw invalid();
                yield new Packet(type, 0, 0, 0, 0, null, null, scale);
            }
            case AVC -> {
                int w = in.readUnsignedShort(), h = in.readUnsignedShort();
                checkSize(w, h);
                yield new Packet(AVC, w, h, 0, 0, readBytes(in, 1, MAX_CSD),
                        readBytes(in, 0, MAX_CSD));
            }
            case VIDEO -> {
                long pts = in.readLong();
                int flags = in.readUnsignedByte();
                if (pts < 0 || (flags & ~KEY_FRAME) != 0) throw invalid();
                yield new Packet(VIDEO, 0, 0, pts, flags, readBytes(in, 1, MAX_VIDEO), null);
            }
            case PCM, TIMED_PCM -> {
                long pts = type == TIMED_PCM ? in.readLong() : 0;
                byte[] bytes = readBytes(in, AUDIO_BYTES_PER_FRAME, MAX_PCM);
                if (pts < 0 || pts > Long.MAX_VALUE / 1000 || bytes.length % AUDIO_BYTES_PER_FRAME != 0) {
                    throw invalid();
                }
                yield new Packet(type, 0, 0, pts, 0, bytes, null);
            }
            default -> throw invalid();
        };
    }

    private static void checkSize(int w, int h) throws IOException {
        if (w < 16 || h < 16 || w > 1920 || h > 1920 || w % 2 != 0 || h % 2 != 0) throw invalid();
    }

    private static byte[] readBytes(DataInputStream in, int min, int max) throws IOException {
        int length = in.readInt();
        if (length < min || length > max) throw invalid();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes, int max) throws IOException {
        if (bytes.length > max) throw invalid();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static IOException invalid() {
        return new IOException("投屏数据格式不正确");
    }

    public static final class Packet {
        public final int type, width, height, flags;
        public final long pts;
        public final byte[] data, extra;
        public final int pictureScale;

        Packet(int type, int width, int height, long pts, int flags, byte[] data, byte[] extra) {
            this(type, width, height, pts, flags, data, extra, DEFAULT_SCALE);
        }

        private Packet(int type, int width, int height, long pts, int flags, byte[] data,
                       byte[] extra, int pictureScale) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.pts = pts;
            this.flags = flags;
            this.data = data;
            this.extra = extra;
            this.pictureScale = pictureScale;
        }
    }
}
