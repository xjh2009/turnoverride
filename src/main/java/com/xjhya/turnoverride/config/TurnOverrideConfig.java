package com.xjhya.turnoverride.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod config file model + load/save helpers.
 * <p>Pure Gson, no external libs.</p>
 */
public final class TurnOverrideConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    /** Mode controlling how Mojang-provided TURN servers interact with custom ones. */
    public enum MergeMode {
        /** Drop Mojang servers entirely, use only custom. */
        REPLACE,
        /** Custom servers first, Mojang servers as fallback. */
        PREPEND,
        /** Mojang servers first, custom servers as fallback. */
        APPEND
    }

    /** Top-level master switch. If false, this mod is a no-op. */
    public boolean enabled = true;

    /** Whether to dump a debug log line every time we mutate the ICE server. */
    public boolean debugLog = false;

    /** REPLACE / PREPEND / APPEND — see {@link MergeMode}. */
    public MergeMode mergeMode = MergeMode.PREPEND;

    /**
     * Force WebRTC to use TURN relay only — disables hole-punching, the connection
     * will always be tunneled through a TURN server (no direct P2P).
     *
     * <p>Maps to {@code RTCIceTransportPolicy.RELAY}. Pros: always works behind
     * symmetric NAT / strict firewalls. Cons: higher latency, all traffic eats
     * your TURN server's bandwidth.</p>
     */
    public boolean forceRelay = false;

    /** Static custom TURN servers, always available, no network round-trip needed. */
    public List<TurnServerEntry> staticServers = new ArrayList<>();

    /** Optional remote endpoint pulled on every requestTurnAuth call. */
    public RemoteSource remote = new RemoteSource();

    public static class TurnServerEntry {
        public String username = "";
        public String password = "";
        public List<String> urls = new ArrayList<>();

        public TurnServerEntry() {}

        public TurnServerEntry(String username, String password, List<String> urls) {
            this.username = username;
            this.password = password;
            this.urls = urls;
        }

        public boolean isValid() {
            return urls != null && !urls.isEmpty();
        }
    }

    public static class RemoteSource {
        /** Disabled by default. Set to a full HTTPS URL to enable remote fetching. */
        public String url = "";

        /** Optional HTTP headers — supports Authorization, X-API-Key, etc. */
        public Map<String, String> headers = new LinkedHashMap<>();

        /** HTTP request timeout in milliseconds. */
        public int timeoutMillis = 5_000;

        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }

    // ---------------------------------------------------------------------
    // Load / save
    // ---------------------------------------------------------------------

    public static TurnOverrideConfig load(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                TurnOverrideConfig fresh = defaults();
                save(file, fresh);
                LOGGER.info("[TurnOverride] Wrote default config to {}", file);
                return fresh;
            }
            String json = Files.readString(file);
            // setLenient(true): allow // and /* */ comments, plus a few other
            // common-sense relaxations of strict JSON. Users want to annotate
            // their config; we honour that.
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(true);
            TurnOverrideConfig cfg = GSON.fromJson(reader, TurnOverrideConfig.class);
            if (cfg == null) cfg = defaults();
            cfg.sanitize();
            return cfg;
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("[TurnOverride] Failed to load config, using defaults", e);
            return defaults();
        }
    }

    public static void save(Path file, TurnOverrideConfig cfg) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg));
        } catch (IOException e) {
            LOGGER.error("[TurnOverride] Failed to save config", e);
        }
    }

    private static TurnOverrideConfig defaults() {
        TurnOverrideConfig c = new TurnOverrideConfig();
        // Provide a commented-out style example via the static list so the user sees the shape.
        TurnServerEntry example = new TurnServerEntry(
                "example-user",
                "example-pass",
                List.of(
                        "turn:turn.example.com:3478?transport=udp",
                        "turn:turn.example.com:3478?transport=tcp",
                        "stun:stun.example.com:3478"
                )
        );
        // Add it but leave the user free to delete it on first run.
        c.staticServers.add(example);
        c.remote.headers.put("User-Agent", "turnoverride/1.0");
        return c;
    }

    private void sanitize() {
        if (mergeMode == null) mergeMode = MergeMode.PREPEND;
        if (staticServers == null) staticServers = new ArrayList<>();
        if (remote == null) remote = new RemoteSource();
        if (remote.headers == null) remote.headers = new LinkedHashMap<>();
        if (remote.timeoutMillis <= 0) remote.timeoutMillis = 5_000;
        staticServers.removeIf(e -> e == null || !e.isValid());
    }
}
