package com.liuguang.cast.tv;

import android.content.Context;
import android.util.Log;

import com.liuguang.cast.shared.DiscoveryPacket;
import com.liuguang.cast.shared.LanIpv4;
import com.liuguang.cast.shared.Wire;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

final class TvDiscoveryResponder implements AutoCloseable {
    private static final String TAG = "LiuguangDiscovery";
    private final Context context;
    private DatagramSocket socket;
    private volatile boolean closed;

    TvDiscoveryResponder(Context context) { this.context = context; }

    synchronized void start(String pin) {
        if (closed || socket != null) return;
        try {
            DatagramSocket listener = new DatagramSocket(Wire.PORT);
            socket = listener;
            new Thread(() -> respond(listener, pin), "tv-discovery").start();
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Could not open the broadcast discovery socket", e);
        }
    }

    private void respond(DatagramSocket listener, String pin) {
        byte[] buffer = new byte[DiscoveryPacket.MAX_BYTES + 1];
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
        String name = TvDetails.name();
        try (listener) {
            while (!listener.isClosed()) {
                request.setLength(buffer.length);
                listener.receive(request);
                DiscoveryPacket.Query query = DiscoveryPacket.readQuery(request);
                InetAddress peer = request.getAddress();
                if (query == null || request.getPort() == 0 || peer.isAnyLocalAddress()
                        || peer.isLoopbackAddress() || peer.isMulticastAddress() || !isOnLink(peer)) continue;
                byte[] bytes = DiscoveryPacket.reply(query, name, pin);
                try {
                    listener.send(new DatagramPacket(bytes, bytes.length, request.getSocketAddress()));
                } catch (IOException ignored) {
                    // A Wi-Fi address can disappear between receiving the query and replying.
                }
            }
        } catch (IOException e) {
            if (!closed) Log.w(TAG, "Broadcast discovery socket stopped", e);
        }
    }

    private boolean isOnLink(InetAddress peer) {
        for (LanIpv4 link : LanIpv4.links(context)) if (link.contains(peer)) return true;
        return false;
    }

    @Override public synchronized void close() {
        closed = true;
        if (socket != null) socket.close();
    }
}
