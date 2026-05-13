package com.xjhya.turnoverride;

import com.xjhya.turnoverride.config.TurnOverrideConfig;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Singleton holder for the loaded {@link TurnOverrideConfig} and the engine.
 *
 * <p>Implemented as a {@link ClientModInitializer} (client-only entrypoint, since the
 * P2P signaling code only exists client-side).</p>
 */
public final class TurnOverrideMod implements ClientModInitializer {

    public static final String MOD_ID = "turnoverride";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile TurnOverrideConfig CONFIG;
    private static volatile TurnOverrideEngine ENGINE;
    private static volatile Path CONFIG_PATH;

    @Override
    public void onInitializeClient() {
        CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
                .resolve("turnoverride")
                .resolve("config.json");
        CONFIG = TurnOverrideConfig.load(CONFIG_PATH);
        ENGINE = new TurnOverrideEngine();
        LOGGER.info("[TurnOverride] Initialized. enabled={}, mergeMode={}, forceRelay={}, staticServers={}, remote={}",
                CONFIG.enabled,
                CONFIG.mergeMode,
                CONFIG.forceRelay,
                CONFIG.staticServers.size(),
                CONFIG.remote != null && CONFIG.remote.enabled() ? CONFIG.remote.url : "<disabled>");
    }

    /** Hot-reload config from disk. Safe to call from any thread. */
    public static synchronized void reload() {
        if (CONFIG_PATH == null) return;
        CONFIG = TurnOverrideConfig.load(CONFIG_PATH);
        LOGGER.info("[TurnOverride] Config reloaded.");
    }

    public static TurnOverrideConfig config() {
        TurnOverrideConfig c = CONFIG;
        if (c == null) {
            // Should never happen post-init, but if a Mixin fires before
            // onInitializeClient (it shouldn't), fall back to in-memory defaults.
            synchronized (TurnOverrideMod.class) {
                if (CONFIG == null) CONFIG = TurnOverrideConfig.load(
                        FabricLoader.getInstance().getConfigDir()
                                .resolve("turnoverride")
                                .resolve("config.json"));
                c = CONFIG;
            }
        }
        return c;
    }

    public static TurnOverrideEngine engine() {
        TurnOverrideEngine e = ENGINE;
        if (e == null) {
            synchronized (TurnOverrideMod.class) {
                if (ENGINE == null) ENGINE = new TurnOverrideEngine();
                e = ENGINE;
            }
        }
        return e;
    }
}
