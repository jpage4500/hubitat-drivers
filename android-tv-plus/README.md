# Android TV+

Hubitat integration for ADB-capable TV devices — Fire TV / Firestick, Shield TV, Google TV, and most
Android TV boxes. The hub talks the ADB protocol directly over TCP (port 5555); no PC, server, or
add-on hardware in the middle.

Based on the [Amazon Fire TV (ADB) driver]([../samples/fire-tv/Hubitat_FireTV_ADB.groovy](https://community.hubitat.com/t/release-firetv-driver-for-hubitat-using-adb-without-other-hardware/163660/1)) by
Vartan Horigian (TRATO), Apache 2.0. See [Changes from the original driver](#changes-from-the-original-driver).

## Features

- **Auto-discovery** of TV devices on your network (mDNS + SSDP), with manual IP entry as a fallback
- **Multiple devices** from one app — each TV is its own Hubitat device under a shared parent
- **Remote control** — d-pad, back/home/menu, play/pause/stop, skip, rewind/fast-forward, volume, mute
- **Launch apps** by package name or by friendly name (Netflix, Prime Video, Disney+, YouTube, …), plus
  your own **custom app list**
- **Status** — is the screen awake, and which app is in front (`switch`, `status`, `currentApp`,
  `currentAppName`)
- **Send any key code or shell command** for anything not covered by a built-in command
- Persistent authenticated connection with automatic reconnect, per-device polling, and watchdogs

## Installation

Install via Hubitat Package Manager (HPM) — search for **Android TV+**.

## Setup

1. On each device: **Settings → Device / My Fire TV → Developer Options → ADB Debugging: ON**
2. **Apps → Add user app → Android TV+**
3. Wait for discovery, check the devices you want, click **Done**. Anything not found automatically can be
   added under **Add a device by IP**.
4. The first time the hub connects, the TV shows an **"Allow USB debugging?"** prompt — choose
   **"Always allow from this computer"**. Authorization is stored on the TV and survives reboots.

If the prompt never appears (or you moved the device to a new hub), run **Generate New Key** on the device
page — it makes a fresh RSA key and reconnects so the TV asks again.

## How it works

Each selected TV becomes a child device under a single **Android TV+** parent, which shows an at-a-glance
summary (how many devices exist, how many are online, what the first one is playing). Poll interval, debug
logging, and the custom app list are set once in the app and pushed down to every device.

Every device keeps one authenticated ADB socket open and runs each command as a short-lived
`shell:<command>` stream. A status poll (default every 30s) asks `dumpsys` for the power state and the
foreground app in a single round trip.

## Custom apps

In the app's **Custom apps** section, one per line:

```
Kodi = org.xbmc.kodi
YouTube TV = com.google.android.youtube.tvunplugged
```

The name is what `currentAppName` reports and what `appOpenByName` accepts. If an app refuses to launch,
append its start command: `Name = pkg | am start -n pkg/.MainActivity`. To find a package name, run
**Send Shell Command** with `pm list packages -3`.

----

## Changes from the original driver

### Architecture

- **App + parent + child** instead of one standalone driver: one install manages every TV in the house,
  with shared settings (poll interval, debug logging, custom apps) pushed down to each device.
- **Auto-discovery** — mDNS (Cast, Android TV remote, Amazon Whisperplay, wireless debugging) plus an SSDP
  DIAL search, deduplicated by IP. The original required typing in an IP by hand.
- **Per-device polling** on a staggered schedule derived from the device id, so a house full of TVs doesn't
  poll in lockstep and one unresponsive device can only ever delay itself.

### Reliability

- **Runtime state moved out of `state{}`.** The rx buffer, connection state, active command, and queue live
  in `@Field static` maps keyed by device id. Hubitat's `state` is not safe for data shared between
  `parse()` and scheduled jobs — writes from one race the other.
- **Command queue** (up to 20, background polls evicted before user keypresses) replaces the single
  `pendingShellCmd` slot, which silently dropped a command if you pressed two buttons in a row.
- **Watchdogs and backoff** — connect/auth timeout, per-command timeout with two retries before the command
  is dropped, a 60s health check that hard-resets after 90s of silence, and exponential reconnect backoff
  capped at 15 minutes. `Disconnect` is now sticky until you run `Reconnect`.
- **Keepalive only when idle** and nothing has been received for 45s, instead of a fixed ping every 4
  minutes.

### Behavior and features

- **Real power state.** A new `status` attribute (awake / asleep / dreaming / dozing) comes from
  `dumpsys power`, and `switch` follows it. The original only ever reported what it had been told to do —
  turning the TV on by remote left Hubitat showing "off".
- **One round trip per poll** for both power state and foreground app.
- **App catalog** is a single table of package → name, aliases, and optional launch command, extensible
  through the app's Custom apps setting. The original hardcoded three separate lists (packages, friendly
  names, `appOpenByName` cases) that had to be kept in sync.
- **Generic app launching** resolves the app's leanback launcher activity via `cmd package resolve-activity`
  and falls back to `monkey`, which is why Prime Video / HBO Max / Apple TV no longer need hardcoded start
  commands. Package names are validated before being sent to the shell.
- **Attributes are enums**, so values are validated and Rule Machine offers a dropdown instead of a text
  box. Status values are consistent English (the original mixed English and Portuguese —
  `erro_conexao`, `aguardando_autorizacao`).
- **Events only fire on change**, instead of re-sending the same value on every poll.
- Removed the Samsung-remote compatibility aliases (`arrowUp`, `Return`, `channelSet`, `guide`,
  `sourceToggle`, `numericKeyPad`, …). `sendKeyEvent` sends any Android key code directly.
- **Logging** is consistently prefixed and debug output auto-disables after 24 hours.

### Protocol correctness

- **Fixed the ADB checksum.** The `data_check` header field is a plain 32-bit sum of the payload bytes, not
  CRC-32. (Harmless outbound — `adbd` ignores the value it receives — but it made inbound validation
  impossible.)
- **One-shot command streams.** Commands now run as `shell:<cmd>` streams that end with a `CLSE`, so
  completion is deterministic and the full output is captured. The original opened an interactive shell and
  guessed a command was done by counting `$`/`#` prompt characters in the output.
- **Frame validation and resync** — magic word, payload length bounds, and hex-decode failures are checked,
  and a corrupt header resyncs the parser instead of derailing it. Output spanning multiple `WRTE` frames is
  reassembled.
- **Secure key generation.** RSA keys come from `SecureRandom` rather than a `Random` seeded with the
  current time, and `gcd(e, phi)` is verified before the key is used.
