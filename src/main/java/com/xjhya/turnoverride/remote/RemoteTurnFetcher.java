package com.xjhya.turnoverride.remote;

import com.xjhya.turnoverride.config.TurnOverrideConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches custom TURN servers from a remote HTTP(S) endpoint.
 * <p>Auto-sniffs two payload shapes:</p>
 * <ol>
 *   <li>Mojang-native: {@code {"ExpirationInSeconds":N,"TurnAuthServers":[{"Username":..,"Password":..,"Urls":[..]}]}}</li>
 *   <li>Flat: {@code [{"urls":[..],"username":..,"password":..}, ...]} (browser-style RTCIceServer array)</li>
 * </ol>
 */
public final class RemoteTurnFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final HttpClient httpClient;

    public RemoteTurnFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetch + parse. Returns an empty list on any failure (logged at WARN).
     * This is called synchronously from the signaling executor; we keep it
     * blocking but bounded by timeoutMillis.
     */
    public List<TurnOverrideConfig.TurnServerEntry> fetch(TurnOverrideConfig.RemoteSource source) {
        if (source == null || !source.enabled()) return List.of();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(source.url))
                    .timeout(Duration.ofMillis(Math.max(1_000, source.timeoutMillis)))
                    .GET();
            if (source.headers != null) {
                for (Map.Entry<String, String> h : source.headers.entrySet()) {
                    if (h.getKey() == null || h.getKey().isBlank()) continue;
                    if (h.getValue() == null) continue;
                    b.header(h.getKey(), h.getValue());
                }
            }
            HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                LOGGER.warn("[TurnOverride] Remote {} returned HTTP {}", source.url, resp.statusCode());
                return List.of();
            }
            return parse(resp.body());
        } catch (Exception e) {
            LOGGER.warn("[TurnOverride] Remote fetch failed: {}", e.toString());
            return List.of();
        }
    }

    /** Visible for testing. */
    public static List<TurnOverrideConfig.TurnServerEntry> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                // Sniff for Mojang-native shape first.
                if (obj.has("TurnAuthServers") && obj.get("TurnAuthServers").isJsonArray()) {
                    return parseMojangShape(obj.getAsJsonArray("TurnAuthServers"));
                }
                // Single-object flat shape: {"urls":[..],"username":..,"password":..}
                TurnOverrideConfig.TurnServerEntry single = parseFlatEntry(obj);
                if (single != null) return List.of(single);
            } else if (root.isJsonArray()) {
                return parseFlatArray(root.getAsJsonArray());
            }
            LOGGER.warn("[TurnOverride] Remote body did not match any known schema");
        } catch (JsonSyntaxException e) {
            LOGGER.warn("[TurnOverride] Remote body is not valid JSON: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("[TurnOverride] Remote body parse error: {}", e.toString());
        }
        return List.of();
    }

    private static List<TurnOverrideConfig.TurnServerEntry> parseMojangShape(JsonArray arr) {
        List<TurnOverrideConfig.TurnServerEntry> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String u  = optString(o, "Username");
            String p  = optString(o, "Password");
            List<String> urls = optStringArray(o, "Urls");
            if (urls.isEmpty()) continue;
            out.add(new TurnOverrideConfig.TurnServerEntry(u, p, urls));
        }
        return out;
    }

    private static List<TurnOverrideConfig.TurnServerEntry> parseFlatArray(JsonArray arr) {
        List<TurnOverrideConfig.TurnServerEntry> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            TurnOverrideConfig.TurnServerEntry e = parseFlatEntry(el.getAsJsonObject());
            if (e != null) out.add(e);
        }
        return out;
    }

    private static TurnOverrideConfig.TurnServerEntry parseFlatEntry(JsonObject o) {
        // Allow either "urls" (browser-style) or singular "url".
        List<String> urls = optStringArray(o, "urls");
        if (urls.isEmpty()) {
            String single = optString(o, "url");
            if (!single.isEmpty()) urls = List.of(single);
        }
        if (urls.isEmpty()) return null;
        String u = optString(o, "username");
        String p = firstNonEmpty(optString(o, "credential"), optString(o, "password"));
        return new TurnOverrideConfig.TurnServerEntry(u, p, urls);
    }

    // ---- json helpers ----
    private static String optString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return "";
        try {
            return el.getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> optStringArray(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonArray()) return List.of();
        JsonArray a = el.getAsJsonArray();
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement e : a) {
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                String s = e.getAsString();
                if (s != null && !s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b != null ? b : "");
    }
}
