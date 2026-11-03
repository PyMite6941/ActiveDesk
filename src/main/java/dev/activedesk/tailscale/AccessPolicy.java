package dev.activedesk.tailscale;

import dev.activedesk.Config;
import java.util.List;
import java.util.Optional;

/**
 * Decides who may talk to this node at all. This is the first of two gates:
 *   1. network identity: the caller must be this machine (loopback) or a Tailscale device whose
 *      owner login is allowed (default: the same login that owns this device). Tailscale has already
 *      authenticated the device with WireGuard keys, so the source 100.x IP cannot be spoofed.
 *   2. for screen control only, the web UI additionally requires the device PIN (see WebServer).
 */
public final class AccessPolicy {

    public record Decision(boolean allowed, String who, String reason) {
        static Decision allow(String who) { return new Decision(true, who, "ok"); }
        static Decision deny(String reason) { return new Decision(false, null, reason); }
    }

    private final Tailscale tailscale;
    private final Config config;

    public AccessPolicy(Tailscale tailscale, Config config) {
        this.tailscale = tailscale;
        this.config = config;
    }

    public Decision check(String remoteIp) {
        if (Tailscale.isLoopback(remoteIp)) return Decision.allow("localhost");
        if (!Tailscale.isTailnetAddress(remoteIp)) return Decision.deny("not a Tailscale address: " + remoteIp);

        Tailscale.Status st = tailscale.status();
        Optional<Tailscale.Peer> peer = st.byIp(remoteIp);
        if (peer.isEmpty()) {
            st = tailscale.status(true); // new device may have joined since last refresh
            peer = st.byIp(remoteIp);
        }
        if (peer.isEmpty()) return Decision.deny("unknown tailnet address " + remoteIp);
        Tailscale.Peer p = peer.get();
        if (p.self()) return Decision.allow(p.displayName());

        List<String> allowedTags = config.getList("allow.tags");
        for (String t : p.tags()) {
            if (allowedTags.contains(t)) return Decision.allow(p.displayName() + " (" + t + ")");
        }
        if (!p.tags().isEmpty()) return Decision.deny("tagged device " + p.displayName() + " not in allow.tags");

        List<String> allowedLogins = config.getList("allow.logins");
        String selfLogin = st.self() == null ? "" : st.self().login();
        boolean ok = (!selfLogin.isEmpty() && selfLogin.equalsIgnoreCase(p.login()))
                || allowedLogins.stream().anyMatch(l -> l.equalsIgnoreCase(p.login()));
        return ok ? Decision.allow(p.displayName() + " <" + p.login() + ">")
                  : Decision.deny("owner " + p.login() + " of " + p.displayName() + " is not allowed");
    }
}
