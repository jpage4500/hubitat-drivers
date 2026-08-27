# go2rtc — app notes

Hubitat integration for a [go2rtc](https://github.com/AlexxIT/go2rtc) server. Three files deployed **together**
(they call each other's methods; a version skew can hit a missing method during `createChild`/`configure`):

- `go2rtc-app.groovy` — discovery UI + child reconcile (driver name **go2rtc**)
- `go2rtc-parent-driver.groovy` — lean aggregate tile + child-management hooks (driver name **go2rtc Parent**)
- `go2rtc-driver.groovy` — one child per camera group; ImageCapture + multi-quality RTSP (driver name **go2rtc Camera**)

Structure mirrors `google-chromecast-plus/`: an app creates a single parent device and one child per camera group,
with a single debug toggle broadcast app → parent → children and a central poll timer in the app.

## go2rtc REST API (what discovery relies on)

- `GET {server}/api/streams` → JSON **object keyed by stream name**; each value is `{producers:[{url,...}], consumers:[...]}`.
  The map keys are the stream names. (`?src=NAME` scopes to one stream.)
- `GET {server}/api/frame.jpeg?src=NAME` → JPEG still. Optional `w`/`h`, `rotate`, `hw`, `cache` params. go2rtc
  connects to the source on demand to grab a frame, so it works even with no active viewer.
- RTSP lives on a **separate port** (default **8554**, not the 1984 API port): `rtsp://<host>:8554/NAME`.
- Default API port is **1984**. Auth (if enabled) is HTTP Basic; the same creds are embedded in the RTSP URL.

## go2rtc YAML — no native grouping

go2rtc `streams` is a flat name → source map. Multiple URLs under one name = alternate producers for the *same*
stream (failover/transcode), NOT quality variants. Grouping is done in this Hubitat package.

**Naming convention** (auto-grouped by the app):

```yaml
streams:
  front_door:       rtsp://.../subtype=0   # main (bare name)
  front_door_sub:   rtsp://.../subtype=1   # sub
  front_door_ext:   rtsp://.../subtype=2   # ext (optional 3rd+)
```

Role suffixes after last `_`: `main`, `sub`, `ext`, `low`, `high`, `sd`, `hd`. Bare name = main.
App setting overrides: `map_<cleanBase>_<role>` enum per group.

## Capabilities / design decisions

- **ImageCapture**: the `image` attribute is a **URL**, not a downloaded file. Snapshot always from **primary/main**
  stream. `take()` bumps cache-buster; `publishUrls()` does not.
- **Multi-quality RTSP**: each role gets a fixed attribute (`videoMain`, `videoSub`, ...). `video` = active quality
  (default `main`). `selectStream(role)` command switches `video`. `streamRoles` JSON attribute for apps/debug.
  Stream names are **path-encoded** in RTSP urls (`pathEnc`: space → `%20`, not `+`) — a raw space yields an
  invalid URI that many RTSP clients reject.
- **`videoWeb`**: go2rtc HTTP player page (`{server}/stream.html?src=NAME`, on the 1984 API port) for the active
  stream — follows `selectStream` like `video`. For viewers that want the browser player, not raw RTSP.
- **VideoCapture** is declared only because the user asked for it; Hubitat's def is useless for live cameras.
- Server URL / host / RTSP port / creds are pushed app → parent (`setServer`) → child (`configure`). Creds in
  child's `state`; embedded in RTSP urls but NOT in `image`/`snapshotUrl`.
- **Poll on interval**: app's central timer calls `child.refresh()` → `refreshStatus()` + `take()`.
- **Status**: `online` if primary stream exists; `degraded` if primary ok but a mapped substream is missing;
  `offline` if primary missing.
- **Orphan cleanup**: children carry `streamName` (primary) and `cameraBase` data values. Orphan = primary stream
  no longer on server. Old per-stream children (pre-grouping) are removed when their primary vanishes or group merges.

## Gotchas (Hubitat)

- `getClass()` is blocked in the sandbox — use `instanceof` (see `summarizeStream` shape checks).
- Binary fetch: `httpGet` hands back a stream / `byte[]` / String depending on content-type.
- This only runs on a hub; local verification is limited to brace/paren balance and reading the diff.
- The user commits changes themselves — don't offer to commit.
