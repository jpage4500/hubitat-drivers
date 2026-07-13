# go2rtc — app notes

Hubitat integration for a [go2rtc](https://github.com/AlexxIT/go2rtc) server. Three files deployed **together**
(they call each other's methods; a version skew can hit a missing method during `createChild`/`configure`):

- `go2rtc-app.groovy` — discovery UI + child reconcile (driver name **go2rtc**)
- `go2rtc-parent-driver.groovy` — lean aggregate tile + child-management hooks (driver name **go2rtc Parent**)
- `go2rtc-driver.groovy` — one child per camera; ImageCapture + RTSP (driver name **go2rtc Camera**)

Structure mirrors `google-chromecast-plus/`: an app creates a single parent device and one child per device,
with a single debug toggle broadcast app → parent → children and a central poll timer in the app.

## go2rtc REST API (what discovery relies on)

- `GET {server}/api/streams` → JSON **object keyed by stream name**; each value is `{producers:[{url,...}], consumers:[...]}`.
  The map keys are the stream names — that's the camera list. (`?src=NAME` scopes to one stream.)
- `GET {server}/api/frame.jpeg?src=NAME` → JPEG still. Optional `w`/`h`, `rotate`, `hw`, `cache` params. go2rtc
  connects to the source on demand to grab a frame, so it works even with no active viewer.
- RTSP lives on a **separate port** (default **8554**, not the 1984 API port): `rtsp://<host>:8554/NAME`.
- Default API port is **1984**. Auth (if enabled) is HTTP Basic; the same creds are embedded in the RTSP URL.

## Capabilities / design decisions

- **ImageCapture**: the `image` attribute is a **URL**, not a downloaded file. `take()` bumps a cache-buster
  (`state.imageRefreshMs = now()`) and sets `image` = `{server}/api/frame.jpeg?src=NAME[&w=..]&refresh=<epochMs>`.
  Nothing is fetched/stored on the hub — the dashboard/browser loads the frame straight from go2rtc. The
  `refresh` value changes **only** on a real refresh (take/refresh/poll), so the frame caches in between and
  reloads on demand. `publishUrls()` re-emits `image` with the *current* (un-bumped) timestamp, so a plain Done
  doesn't force a reload. (Earlier versions downloaded via `uploadHubFile` → `file:<dni>.jpg`; removed at the
  user's request — no File Manager, no HE 2.3.4 floor.)
- **VideoCapture** is declared only because the user asked for it; Hubitat's def (a `capture(start,end,camera)`
  command + `clip` attribute) is useless for a live camera, so the RTSP URL goes in a custom **`video`** attribute
  and `capture()` is a logged no-op.
- Server URL / host / RTSP port / creds are pushed app → parent (`setServer`) → child (`configure`). Creds live in
  the child's `state` (not as prominent device data values); non-secret bits are data values. Creds are embedded
  in the RTSP `video` url but NOT in the `image`/`snapshotUrl` (so those stay shareable; auth'd servers are a
  known limitation for the image tile).
- **Poll on interval**: the app's central timer calls `child.refresh()` → `refreshStatus()` (a small
  `/api/streams?src=NAME` JSON check for online/source, *not* an image download) + `take()` (bump image URL).
  Interval defaults to **0 (off)**; the image URL still refreshes on create / Refresh / Take.
- **Orphan cleanup**: children carry a `streamName` data value. On each page render, any child whose `streamName`
  isn't in the current `/api/streams` result is shown disabled at the bottom and deleted on Done (it's simply not
  a "wanted" candidate in `syncChildren`, same reconcile as chromecast).

## Gotchas (Hubitat)

- `getClass()` is blocked in the sandbox — use `instanceof` (see `summarizeStream`/`fetchBytes` shape checks).
- Binary fetch: `httpGet` hands back a stream / `byte[]` / String depending on content-type; `fetchBytes` handles
  all three. Keep snapshots small (set a width) — `httpGet` has a response-size ceiling.
- This only runs on a hub; it can't be compiled/run locally. Local verification is limited to brace/paren balance
  and reading the diff — behavior must be confirmed on the user's hub.
- The user commits changes themselves — don't offer to commit.
