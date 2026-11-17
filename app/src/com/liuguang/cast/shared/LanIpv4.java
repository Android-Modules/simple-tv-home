package com.liuguang.cast.shared;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public record LanIpv4(Network network, Inet4Address address, int prefix) {
    public static List<LanIpv4> links(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        List<LanIpv4> result = new ArrayList<>();
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()) {
                    result.add(new LanIpv4(network, ipv4, link.getPrefixLength()));
                }
            }
        }
        return result;
    }

    public boolean contains(InetAddress peer) {
        if (!(peer instanceof Inet4Address) || prefix == 0) return false;
        int mask = -1 << (32 - prefix);
        return (bits(address) & mask) == (bits(peer) & mask);
    }

    public InetAddress broadcast() {
        if (prefix == 0 || prefix >= 31) return null;
        int mask = -1 << (32 - prefix);
        byte[] bytes = ByteBuffer.allocate(4).putInt(bits(address) | ~mask).array();
        try { return InetAddress.getByAddress(bytes); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }

    private static int bits(InetAddress address) {
        return ByteBuffer.wrap(address.getAddress()).getInt();
    }
}
