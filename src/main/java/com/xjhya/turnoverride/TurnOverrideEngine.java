package com.xjhya.turnoverride;

import com.xjhya.turnoverride.config.TurnOverrideConfig;
import com.xjhya.turnoverride.remote.RemoteTurnFetcher;
import com.mojang.logging.LogUtils;
import dev.onvoid.webrtc.RTCIceServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the list of custom {@link RTCIceServer}s based on the loaded config.
 *
 * <p>One {@link TurnOverrideConfig.TurnServerEntry} maps to <strong>one</strong>
 * {@link RTCIceServer}. That's important: each {@code RTCIceServer} carries
 * its own username/password pair, and WebRTC will use the matching credential
 * when contacting any URL that lives inside that server's {@code urls} list.
 * So a TURN server with credentials A goes in one RTCIceServer, a TURN server
 * with credentials B goes in another — even when they ultimately end up in the
 * same {@link dev.onvoid.webrtc.RTCConfiguration#iceServers} list.</p>
 */
public final class TurnOverrideEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final RemoteTurnFetcher fetcher = new RemoteTurnFetcher();

    /**
     * Build the user's custom servers. Empty list = nothing custom for this
     * handshake (which can happen because of remote-fetch failure → fall back
     * to Mojang behaviour).
     *
     * <p>Returns null if {@code enabled = false} (signal "do nothing at all"
     * to callers).</p>
     */
    public List<RTCIceServer> buildCustomServers() {
        TurnOverrideConfig cfg = TurnOverrideMod.config();
        if (!cfg.enabled) return null;

        List<TurnOverrideConfig.TurnServerEntry> entries = new ArrayList<>();
        if (cfg.staticServers != null) entries.addAll(cfg.staticServers);

        if (cfg.remote != null && cfg.remote.enabled()) {
            List<TurnOverrideConfig.TurnServerEntry> remote = fetcher.fetch(cfg.remote);
            if (remote.isEmpty()) {
                LOGGER.warn("[TurnOverride] Remote fetch returned no servers — falling back to Mojang TURN for this handshake");
                // We deliberately return null so the caller can keep Mojang's
                // server untouched (whether mergeMode is REPLACE or not).
                return null;
            }
            entries.addAll(remote);
        }

        if (entries.isEmpty()) return List.of();

        List<RTCIceServer> out = new ArrayList<>(entries.size());
        for (TurnOverrideConfig.TurnServerEntry e : entries) {
            if (e == null || !e.isValid()) continue;
            RTCIceServer s = new RTCIceServer();
            s.username = nullSafe(e.username);
            s.password = nullSafe(e.password);
            s.urls = new ArrayList<>(e.urls);
            out.add(s);
        }

        if (cfg.debugLog) {
            for (int i = 0; i < out.size(); i++) {
                RTCIceServer s = out.get(i);
                LOGGER.info("[TurnOverride] custom[{}] user={} urls={}", i, s.username, s.urls);
            }
        }
        return out;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
