package com.baogong.server.server;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** 获取本机在局域网中的 IP（用于状态显示）。 */
public final class NetUtils {

    public static String getLocalIp(Context context) {
        try {
            // 优先取 Wi-Fi IP（更贴近局域网实际地址）
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) {
        }
        // 回退：枚举网卡
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    String host = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && host != null && !host.contains(":")) {
                        return host;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private NetUtils() {
    }
}
