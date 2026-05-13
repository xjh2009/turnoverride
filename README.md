# TURN Override (Fabric Mod)

Override the TURN / ICE servers Minecraft uses for its WebRTC-based P2P "Friend
Worlds" feature (introduced in the 26.2 snapshot cycle). Supports static
servers configured in JSON, optional live fetching from a remote URL, and three
merge strategies controlling how your servers interact with Mojang's defaults.

> Client-side mod. Required only on the host of the friend world (the one
> initiating the WebRTC handshake — usually both sides since each connection is
> peer-to-peer).

---

## How it works (short version)

Mojang's client chain:

```
SignalingServiceClient.requestTurnAuth()
  └─ refreshTurnAuth()
       └─ JSON-RPC "Signaling_TurnAuth_v1_0"   → TurnAuthResult (cached)
       └─ TurnAuthResult.toRtcIceServer()      → RTCIceServer
            └─ fed into RTCConfiguration.iceServers
```

This mod attaches a Mixin to `TurnAuthResult.toRtcIceServer()` at `@At("RETURN")`
and substitutes / augments the returned `RTCIceServer` based on your config.
Because Mojang caches the `TurnAuthResult` record (not the built ICE server),
our hook fires on every handshake, which naturally satisfies the "fetch the
remote URL every request" requirement.

---

## Build

```powershell
gradlew build
```

Drop `build/libs/turnoverride-<version>.jar` into your Fabric `mods/` folder.

Notes on mappings: the included `build.gradle` uses **official Mojang mappings**
because the 26.2 snapshot didn't yet have a Yarn release at the time of
writing. If/when a matching Yarn build appears, swap the `mappings` line in
`build.gradle` and update `gradle.properties` accordingly.

---

## Config

Path: `<game_dir>/config/turnoverride/config.json` (auto-generated on first run).

```json
{
  "enabled": true,
  "debugLog": false,
  "mergeMode": "PREPEND",
  "staticServers": [
    {
      "username": "user",
      "password": "pass",
      "urls": [
        "turn:turn.example.com:3478?transport=udp",
        "turn:turn.example.com:3478?transport=tcp",
        "stun:stun.example.com:3478"
      ]
    }
  ],
  "remote": {
    "url": "",
    "headers": {
      "User-Agent": "turnoverride/1.0"
    },
    "timeoutMillis": 5000
  }
}
```

### Fields

| field                 | type       | meaning                                                                                   |
|-----------------------|------------|-------------------------------------------------------------------------------------------|
| `enabled`             | bool       | Master switch. `false` = mod is a no-op.                                                  |
| `debugLog`            | bool       | If `true`, log the resulting URL list every handshake.                                    |
| `mergeMode`           | enum       | `REPLACE` / `PREPEND` / `APPEND` — see below.                                             |
| `staticServers`       | array      | Always-on custom TURN/STUN servers.                                                       |
| `remote.url`          | string     | Optional HTTPS endpoint. Empty = disabled. Fetched on **every** `requestTurnAuth` call.   |
| `remote.headers`      | obj        | Arbitrary request headers (e.g. `"Authorization": "Bearer ..."`).                         |
| `remote.timeoutMillis`| int        | Per-request timeout in ms. Default 5000.                                                  |

### `mergeMode`

| mode      | URL order                                | credentials used                                  |
|-----------|------------------------------------------|---------------------------------------------------|
| `REPLACE` | only custom (static + remote)            | first custom entry's user/pass                    |
| `PREPEND` | custom first, Mojang appended            | first custom entry's user/pass                    |
| `APPEND`  | Mojang first, custom appended            | Mojang's user/pass                                |

Why "first entry wins" for credentials: `dev.onvoid.webrtc.RTCIceServer` only
holds **one** username/password pair, even though it can hold many URLs.
This matches Mojang's own behavior (`TurnAuthResult.toRtcIceServer` does the
same thing — it uses `turnAuthServers.getFirst()`'s credentials and only
concatenates URLs from the rest).

### Remote payload formats (auto-detected)

**Mojang-native** (passes straight through their codec):

```json
{
  "ExpirationInSeconds": 3600,
  "TurnAuthServers": [
    {
      "Username": "u",
      "Password": "p",
      "Urls": ["turn:host:3478?transport=udp", "stun:host:3478"]
    }
  ]
}
```

**Flat / browser-style** (matches WebRTC's `RTCIceServer[]` shape):

```json
[
  {
    "urls": ["turn:host:3478?transport=udp"],
    "username": "u",
    "credential": "p"
  },
  {
    "urls": ["stun:host:3478"]
  }
]
```

Either `"credential"` or `"password"` is accepted. A single-object body (not
wrapped in an array) is also fine.

### Failure handling

- Remote URL unreachable / non-2xx / parse error → **fall back to Mojang TURN**
  for this handshake. The next handshake will retry the remote URL.
- Mod throws unexpectedly inside the Mixin → caught and logged; Mojang's
  original `RTCIceServer` is used.

---

## Verifying it works

Enable `"debugLog": true` and look for lines like:

```
[TurnOverride] mode=PREPEND username=u urls=[turn:turn.example.com:3478?transport=udp, ..., turn:relay-...minecraft-services.net:..., ...]
```

at the moment a friend joins your world.
