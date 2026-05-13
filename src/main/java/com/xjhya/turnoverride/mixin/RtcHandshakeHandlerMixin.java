package com.xjhya.turnoverride.mixin;

import com.xjhya.turnoverride.TurnOverrideMod;
import com.xjhya.turnoverride.config.TurnOverrideConfig;
import com.mojang.logging.LogUtils;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.List;

/**
 * Single entry point for every override we apply: this mutates the
 * {@link RTCConfiguration} just before it is consumed by {@code RtcHandshake}'s
 * constructor.
 *
 * <p>Why this hook (and not on {@code TurnAuthResult.toRtcIceServer}):
 * <ul>
 *   <li>{@code toRtcIceServer} returns a single {@link RTCIceServer} — but
 *       WebRTC supports a <em>list</em> of independent ice-servers, each with
 *       its own username/password. To make "mojang creds for mojang URLs,
 *       custom creds for custom URLs" actually work we must add multiple
 *       {@link RTCIceServer} entries to {@link RTCConfiguration#iceServers}.</li>
 *   <li>By the time {@code RtcHandshake} is constructed, Mojang has already
 *       appended its own server to {@code cfg.iceServers}. We just slot ours
 *       in front/behind/instead of it.</li>
 *   <li>We can also flip {@link RTCConfiguration#iceTransportPolicy} here, in
 *       the same hook — keeping all rewrites in a single place.</li>
 * </ul>
 *
 * <p>Behaviour matrix:</p>
 * <table>
 *   <tr><th>mode</th>     <th>final iceServers list</th>     <th>credentials</th></tr>
 *   <tr><td>REPLACE</td>  <td>[custom1, custom2, ...]</td>   <td>each entry uses its own</td></tr>
 *   <tr><td>PREPEND</td>  <td>[custom1, ..., mojang]</td>    <td>per-entry, untouched</td></tr>
 *   <tr><td>APPEND</td>   <td>[mojang, custom1, ...]</td>    <td>per-entry, untouched</td></tr>
 * </table>
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.multiplayer.p2p.RtcHandshakeHandler", remap = false)
public abstract class RtcHandshakeHandlerMixin {

    @ModifyArg(
        method = "createHandshake",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/webrtc/RtcHandshake;<init>("
                   + "Ldev/onvoid/webrtc/PeerConnectionFactory;"
                   + "Ldev/onvoid/webrtc/RTCConfiguration;"
                   + "Ljava/lang/String;"
                   + "Z"
                   + "Ljava/util/function/Consumer;"
                   + ")V"
        ),
        index = 1
    )
    private RTCConfiguration turnoverride$rewriteConfig(RTCConfiguration cfg) {
        if (cfg == null) return null;
        try {
            applyOverrides(cfg);
        } catch (Throwable t) {
            LogUtils.getLogger().error("[TurnOverride] failed to apply overrides, falling back to Mojang defaults", t);
        }
        return cfg;
    }

    private static void applyOverrides(RTCConfiguration cfg) {
        TurnOverrideConfig conf = TurnOverrideMod.config();
        if (!conf.enabled) return;

        // 1. Build custom servers (each preserves its own user/pass).
        List<RTCIceServer> custom = TurnOverrideMod.engine().buildCustomServers();
        // null = engine signalled "do not touch" (disabled, or remote failed)
        // empty list = no custom servers configured at all
        if (custom != null && !custom.isEmpty()) {
            List<RTCIceServer> mojangServers = cfg.iceServers != null
                    ? new ArrayList<>(cfg.iceServers)
                    : new ArrayList<>();

            List<RTCIceServer> merged = switch (conf.mergeMode != null ? conf.mergeMode : TurnOverrideConfig.MergeMode.PREPEND) {
                case REPLACE -> new ArrayList<>(custom);
                case PREPEND -> {
                    List<RTCIceServer> list = new ArrayList<>(custom.size() + mojangServers.size());
                    list.addAll(custom);
                    list.addAll(mojangServers);
                    yield list;
                }
                case APPEND -> {
                    List<RTCIceServer> list = new ArrayList<>(custom.size() + mojangServers.size());
                    list.addAll(mojangServers);
                    list.addAll(custom);
                    yield list;
                }
            };

            cfg.iceServers = merged;

            if (conf.debugLog) {
                LogUtils.getLogger().info(
                        "[TurnOverride] mode={} iceServers.size={} (mojang={}, custom={})",
                        conf.mergeMode, merged.size(), mojangServers.size(), custom.size());
            }
        }

        // 2. Force relay-only transport policy (no hole punching) if requested.
        if (conf.forceRelay) {
            cfg.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
            if (conf.debugLog) {
                LogUtils.getLogger().info("[TurnOverride] forceRelay=true -> iceTransportPolicy=RELAY");
            }
        }
    }
}
