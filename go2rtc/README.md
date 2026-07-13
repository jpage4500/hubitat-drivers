# go2rtc

Hubitat integration for [go2rtc](https://github.com/AlexxIT/go2rtc) — the "ultimate camera streaming application".

Point this app at your go2rtc server and each camera / stream becomes a Hubitat device with a **still image**
and its **RTSP URL**, ready for dashboards, rules, and the HD+ app.

----

## Features

- **Auto-discovery** of every stream configured on your go2rtc server (via its REST API)
- **Pick which cameras to create** — a toggle per camera, with a live snapshot thumbnail and source info
- **Still image** on each camera device (Image Capture) — pulled on demand or on a schedule
- **RTSP stream URL** published in a `video` attribute for players that support it
- **Auto-cleanup** — if you rename or remove a camera on go2rtc, the orphaned Hubitat device is flagged and
  removed when you hit Done

----

## Installation

Install via Hubitat Package Manager (HPM) — search for **go2rtc**. It installs three pieces:

- **go2rtc** (app)
- **go2rtc Parent** (driver)
- **go2rtc Camera** (driver)

## Setup

1. **Apps → Add user app → go2rtc**
2. Enter your **go2rtc server URL** — e.g. `http://192.168.0.160:1984/` — and click **Refresh**.
3. The app lists every camera on the server. Uncheck any you don't want.
4. Click **Done**. A **go2rtc** parent device is created, with one child device per selected camera.

If your go2rtc server requires authentication, expand **Advanced** and enter the username / password (also used
to build the RTSP URL). The RTSP port defaults to `8554` and can be changed there too.

----

## How it works

Discovery uses go2rtc's REST API: `GET {server}/api/streams` returns every configured stream, keyed by name.

Each camera device exposes:

- **`image`** (Image Capture) — points directly at the live still URL `{server}/api/frame.jpeg?src=NAME` with a
  cache-busting `&refresh=<timestamp>` on the end. Nothing is downloaded to the hub; your dashboard/browser
  fetches the frame straight from go2rtc. The `refresh` value only changes when a refresh is actually triggered
  (the **Take** command, device **Refresh**, or the app's poll timer), so the image caches in between and reloads
  exactly when you want a fresh frame.
- **`video`** — the camera's RTSP URL, `rtsp://[user:pass@]<host>:<rtspPort>/NAME`. (Hubitat's built-in
  VideoCapture capability has no useful live-stream attribute, so the URL is published here.)
- **`snapshotUrl`** — the same still-image URL without the cache-buster, handy for an HD+ image tile or direct use.
- **`source`** / **`status`** — the producer source (password masked) and whether the stream is present on the server.

The **parent** device is a simple status tile: how many cameras exist and how many are online.

### Snapshot refresh

The `image` URL's cache-buster changes only on demand — the **Take** command, the device **Refresh**, or when the
device is first created. Set a **Snapshot refresh interval** in the app to have every camera bump its image URL
(and re-check status) on a timer, so dashboards auto-refresh the frame every X seconds.

### Renamed / removed cameras

go2rtc stream names are user-editable. When a camera's stream name no longer exists on the server, its Hubitat
device is listed (disabled) at the bottom of the app under **No longer on the server** and is deleted when you
hit **Done**.

----

## Notes

- The app fetches over your LAN each time the config page opens; if the server is unreachable the page shows the
  error and no cameras. Check the URL (include `http://` and the port) and that go2rtc is running.
- Snapshot images can be large — set a **Snapshot width** on a camera device to downscale (go2rtc resizes for you).
- This is open source; issues and PRs welcome.
