package dev.activedesk.net;

import dev.activedesk.tailscale.Tailscale;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Local-network facts used for Wake-on-LAN. A sleeping device is not on Tailscale, so it can only be
 * woken by a magic packet broadcast on its physical LAN - sent by any awake ActiveDesk device there.
 */
public final class Lan {
    private Lan() {}

    public record Iface(String mac, String broadcast) {}

    /** Physical-looking IPv4 interfaces that are up: excludes loopback, Tailscale and virtual adapters. */
    public static List<Iface> interfaces() {
        List<Iface> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                    byte[] hw = ni.getHardwareAddress();
                    if (hw == null || hw.length != 6) continue;
                    String name = (ni.getName() + " " + ni.getDisplayName()).toLowerCase();
                    if (name.contains("tailscale") || name.contains("virtualbox") || name.contains("vmware")
                            || name.contains("hyper-v") || name.contains("docker") || name.contains("wsl")) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (!(ia.getAddress() instanceof Inet4Address)) continue;
                        if (Tailscale.isTailnetAddress(ia.getAddress().getHostAddress())) continue;
                        InetAddress b = ia.getBroadcast();
                        if (b != null) out.add(new Iface(mac(hw), b.getHostAddress()));
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            Log.debug("interface scan failed: " + e);
        }
        return out;
    }

    static String mac(byte[] hw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hw.length; i++) sb.append(i == 0 ? "" : ":").append(String.format("%02x", hw[i]));
        return sb.toString();
    }

    public static boolean validMac(String m) { return m != null && m.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}"); }

    /** Broadcast the WoL magic packet (6x 0xFF + 16x MAC) on UDP 9 and 7 to every given broadcast address. */
    public static int wake(String mac, Set<String> broadcasts) {
        if (!validMac(mac)) return 0;
        byte[] m = new byte[6];
        String[] p = mac.split(":");
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(p[i], 16);
        byte[] pkt = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xff;
        for (int i = 6; i < pkt.length; i += 6) System.arraycopy(m, 0, pkt, i, 6);
        Set<String> targets = new LinkedHashSet<>(broadcasts);
        targets.add("255.255.255.255");
        int sent = 0;
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            for (String b : targets) {
                // only private / broadcast ranges: never let a peer make us send packets to the internet
                if (!isPrivateV4(b)) continue;
                for (int port : new int[]{9, 7}) {
                    try {
                        s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(b), port));
                        sent++;
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            Log.warn("wake-on-lan failed: " + e.getMessage());
        }
        return sent;
    }

    static boolean isPrivateV4(String ip) {
        if ("255.255.255.255".equals(ip)) return true;
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
