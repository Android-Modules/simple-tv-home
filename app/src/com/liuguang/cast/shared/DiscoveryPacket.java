package com.liuguang.cast.shared;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Unauthenticated LAN discovery packets carry the public fingerprint and supported pairing mode. */
public final class DiscoveryPacket {
    public static final String RECEIVER_NAME = "简桌面投屏";

    private static final int QUERY = 0x4c474451; // LGDQ
    private static final int REPLY = 0x4c474452; // LGDR
    private static final int SHORT_CODE_REPLY = 0x4c474453; // LGDS
    private static final byte SHORT_CODE_QUERY = 1;
    private static final int HEADER = Integer.BYTES + Long.BYTES;
    private static final int NAME_CHARS = 40;
    public static final int MAX_BYTES = HEADER + Pairing.PIN_LENGTH + NAME_CHARS * 3;

    public record Query(long nonce, boolean acceptsShortCodeReply) {}
    public record Receiver(String name, String pin, boolean supportsShortCode) {}

    private DiscoveryPacket() {}

    public static byte[] query(long nonce) {
        // Pad requests so that a single reply is never larger than the request.
        return ByteBuffer.allocate(MAX_BYTES).putInt(QUERY).putLong(nonce).put(SHORT_CODE_QUERY).array();
    }

    public static Query readQuery(DatagramPacket packet) {
        if (packet.getLength() != MAX_BYTES) return null;
        ByteBuffer data = data(packet);
        return data.getInt() == QUERY ? new Query(data.getLong(), data.get() == SHORT_CODE_QUERY) : null;
    }

    public static byte[] reply(Query query, String name, String pin) {
        byte[] label = displayName(name).getBytes(StandardCharsets.UTF_8);
        int format = query.acceptsShortCodeReply() ? SHORT_CODE_REPLY : REPLY;
        return ByteBuffer.allocate(HEADER + Pairing.PIN_LENGTH + label.length)
                .putInt(format).putLong(query.nonce()).put(pin.getBytes(StandardCharsets.US_ASCII)).put(label).array();
    }

    public static Receiver readReply(DatagramPacket packet, long nonce) {
        if (packet.getLength() <= HEADER + Pairing.PIN_LENGTH || packet.getLength() > MAX_BYTES) return null;
        ByteBuffer data = data(packet);
        int format = data.getInt();
        if ((format != REPLY && format != SHORT_CODE_REPLY) || data.getLong() != nonce) return null;
        byte[] fingerprint = new byte[Pairing.PIN_LENGTH];
        data.get(fingerprint);
        String pin = new String(fingerprint, StandardCharsets.US_ASCII);
        if (!Pairing.validPin(pin)) return null;
        String name = new String(data.array(), data.position(), data.remaining(), StandardCharsets.UTF_8);
        return new Receiver(displayName(name), pin, format == SHORT_CODE_REPLY);
    }

    public static String displayName(String name) {
        String label = name.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").trim();
        if (label.isEmpty()) return RECEIVER_NAME;
        return label.substring(0, Math.min(label.length(), NAME_CHARS));
    }

    private static ByteBuffer data(DatagramPacket packet) {
        return ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
    }
}
