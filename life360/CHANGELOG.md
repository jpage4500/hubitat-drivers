- [Life360+ Changelog](#life360-changelog)
  - [5.2.5 (testing)](#525-testing)
  - [5.2.4](#524)
  - [5.2.3](#523)
  - [5.2.2](#522)
  - [5.2.1](#521)
  - [5.2.0](#520)
    - [Reliability](#reliability)
    - [New capabilities](#new-capabilities)
    - [Settings page](#settings-page)
    - [Notifications](#notifications)
    - [Privacy](#privacy)
    - [Performance](#performance)
    - [Documentation](#documentation)
  - [Historical — App (5.1.3 and earlier)](#historical--app-513-and-earlier)
  - [Historical — Driver (5.1.4 and earlier)](#historical--driver-514-and-earlier)
  - [Pre-history](#pre-history)

# Life360+ Changelog

## 5.2.5 (testing)

**Fresh, hub-untested changes — try on a spare/low-risk setup first.** Goal: less I/O on small hubs, minimal functional change.

- **Driver sends events only when a value changed** (was: every attribute on every update). `lastUpdated` and the dashboard tile (when HTML Fields is on) still send on every poll that reaches the driver — intentional, so they keep showing liveness. When Life360 sends no `userActivity`, the driver now reports Life360's own `unknown` instead of an empty value. Note: the hub already filters events whose value did not change, so this mainly saves the per-call overhead, not stored events.
- **Fewer state writes in the app:** in-flight markers, the last-success time and the roster-refresh time are kept in memory instead of `state`; unchanged etag/cookie/roster values are no longer rewritten.
- **The per-minute v4 `/circles` poll is gone.** The member roster (`/members`) is re-read at most every 15 minutes instead (and immediately when you press Done; about every 5 minutes while no roster has loaded yet); it picks up joins and leaves (devices are reconciled; no locations are pushed from the routine refresh). v4 `/circles` is only used by the setup picker, since it no longer returns `memberCount`.
- **Heartbeat log is now once per roster refresh (~15 min) instead of every poll,** and there is a new **Heartbeat Logging** setting (default on) to turn it off.
- **Watchdog fixes:** it restarts after a reboot, Done or token recovery, no longer fires when nothing is configured, and a routine roster refresh no longer erases a watchdog or error banner.
- A roster response with no member list no longer wipes the saved roster (Done / Fetch Members shows a banner), and a deleted circle (HTTP 404 from `/members`) now logs "circle not found" and, on Done / Fetch Members, shows a banner telling you to reselect the circle.
- With several phone numbers or emails on a member, the driver now settles on the last of each (as before) instead of re-sending each one on every update. A speed below zero (the phone has no reading) counts as zero.
- **A phone that has stopped no longer holds fast polling on.** Life360 can keep `inTransit` / `isDriving` (and even the last speed) set for a phone that is sitting still, which kept the faster Dynamic Polling rate running indefinitely. If a member Life360 calls in transit has not left the spot where it was first seen (beyond the phone's own reported accuracy) for longer than two default refresh cycles, and at least 2 minutes, the driver now tells the app to stop fast polling. The `inTransit` / `isDriving` attributes still show exactly what Life360 reports; any real movement restarts the timer, and the rule is skipped when a Minimum 'Transit' / 'Driving' Speed is set on the device.
- The routine slow poll skips a member who was fast-polled a moment ago, instead of requesting them twice. Manual fetches still poll everyone.
- With "Include Names and Places in Logs" off, the driver's debug lines hide coordinates and addresses.
- **New `isMoving` attribute** (true/false): `inTransit` AND the phone has actually changed position recently. It goes false a few minutes after a phone stops, long before Life360 clears `inTransit` (about 26 minutes later in one test), so a rule that wants "parked" can trigger on it. `inTransit` and `isDriving` still show exactly what Life360 reports. The "started moving" / "stopped moving" info lines (Driving Logging off) now follow `isMoving`. The HTML tile, the map view and the per-poll "moving @" line follow it too. With a Minimum Transit Speed set it equals `inTransit`. Driving-only states (Life360 `isDriving` true with `inTransit` false) no longer produce "started/stopped moving" lines.
- The driver drops its last-sent cache when the device is removed or when you press arrived/departed (presence only).
- The unused places data is no longer built or passed to the driver.

## 5.2.4

**HTTP 408 is now treated as a transient error.** Life360 sometimes answers a slow request with 408 Request Timeout, and the next poll succeeds. It used to fall through to the generic error path and log a red `unexpected response:408`. It now gets the same handling as a 5xx: a warning, per-member exponential backoff, and a reset on the next successful fetch.

## 5.2.3

**Any 5xx is now treated as a transient error.** Retryable statuses were an explicit allow-list that omitted 500 — which Life360 routinely returns for a single member while the rest of the circle succeeds. A 500 therefore fell through to the generic error path: red `unexpected response:500` in the log, no per-member backoff, retried at full poll rate. The check is now "any 5xx except the permanently-fatal 501/505", so unanticipated codes (Cloudflare has a dozen in the 520s) get exponential backoff instead of log spam.

## 5.2.2

**Fixed the heartbeat log always showing `memberCount:0`** on the newer `/circles` API endpoint, which no longer returns `memberCount`; the count now comes from the `/members` roster.

**Added a "Driving Logging" option.** When off, a member in motion is logged only when they start and stop moving, instead of on every poll. Reduces log spam when several members are travelling.

## 5.2.1

**Fixed `captureCookiesAsync` throwing `MissingMethodException` on `tokenize()`**, which repeated every poll cycle.

## 5.2.0

Significant update from the 5.1.3 / 5.1.4 baseline. Core theme: **the integration now stays online** through Cloudflare cookie rotations and transient Life360 outages that previously caused permanent silent failures.

### Reliability

**Auto-recovery from token-expired state.** Three consecutive 401/403 errors still flag the token as likely expired and slow polling to 5-minute ticks — but the app now probes `/users/me` on each of those ticks. The moment Life360 responds normally (service healed, transient blip cleared), polling resumes at the normal rate automatically. Previously the integration was permanently dead until you manually re-pasted a token.

**Polling survives hub reboots cleanly.** Previously, a hub reboot while a location fetch was in-flight would permanently block polling for those members until the app was re-saved. Fixed.

**Token-expiry notifications stay armed.** Changing the poll frequency while the token is expired no longer silently kills the repeat-notification schedule.

**Cloudflare cookie bug fixed.** Life360 sits behind Cloudflare, which issues two cookies: `_cfuvid` (stable) and `__cf_bm` (rotates every ~30 min). The async cookie handler was overwriting the entire cookie jar with just the fresh rotating cookie each time `__cf_bm` refreshed — silently dropping `_cfuvid`. Without both, Cloudflare returns 403 within minutes and polling goes completely dark. The failure was delayed and silent, which made it look like a Life360 outage. Fixed: cookie updates now merge by name so only the rotating entry is replaced. See the [Privacy & security](README.md#privacy--security) section in the README and [Cookie management](CHANGES_TECHNICAL.md#cookie-management) in CHANGES_TECHNICAL.md.

### New capabilities

- **Force Update** — settings-page button that POSTs a location-refresh request to a member's phone. Life360 signals the device for a fresh GPS fix (~5 seconds), which the next poll cycle picks up.
- **Check Token** — validates your access token with an inline success/failure result on the settings page, including a "Hi, name!" confirmation. Also re-reads the account's units preference.
- **Units auto-detected** — speed and distance follow your Life360 account's imperial/metric preference, fetched automatically from `/users/me`. The per-device `unitOverride` setting is a fallback only (old `isMiles` bool is migrated automatically).
- **Dynamic polling reacts immediately** — when a member starts or stops moving, the polling rate switches on the same tick instead of lagging a full cycle.
- **Membership auto-sync** — the member roster is re-read at most every 15 min (and immediately on Done); when the set of members changes, child devices are reconciled (new members get a device, devices for deselected members are removed) and existing devices get refreshed names/avatars — no need to reopen the app.

### Settings page

"Other Options" reorganized into focused **Polling**, **Notifications**, **Logging**, and **Map View** sections. New **STEP 5: Verify Connectivity** shows how long ago the last successful fetch was. Map View has explicit Generate/Revoke buttons for the OAuth link.

### Notifications

Token-expiry alerts now have a master enable/disable toggle plus a configurable repeat reminder (2 h / 6 h / 12 h / 24 h / 48 h / never). Reminders stop automatically when a fresh token is pasted or auto-recovery succeeds.

### Privacy

Two opt-in logging toggles under app settings → Logging, both default on: **Include Names and Places in Logs** (off = UUIDs only, safe for sharing) and **Include Google Maps Link in Logs** (off = coordinates omitted from the "moved" log line).

**Map view security hardened.** Member names and place names in the map view are now HTML-escaped, preventing a maliciously crafted Life360 profile from injecting content into the page.

### Performance

All member location fetches are now async, with a per-member in-flight guard so a slow Life360 response can't pile up duplicate requests. Exponential backoff on repeated 5xx errors (capped at 5 min) instead of hammering the API at full rate. Places context is built once per poll cycle and shared across all member fetches.

### Documentation

[README.md](README.md) rewritten. [STATE_REFERENCE.md](STATE_REFERENCE.md) added (every setting, state var, scheduled job, and attribute). [APP_SPEC.md](APP_SPEC.md) records the clean-room design (the code is authoritative). Developer-level change details in [CHANGES_TECHNICAL.md](CHANGES_TECHNICAL.md).

---

## Historical — App (5.1.3 and earlier)

Versions below come directly from the `Changes:` block previously at the top of [life360_app.groovy](life360_app.groovy).

- `5.1.3` - 05/09/26 - add `/view` endpoint to view members on a map
- `5.1.2` - 05/01/26 - merge in changes by iEnam: API change (`api-cloudfront.life360.com`); better cookie handling
- `5.1.1` - 05/01/26 - add device notification when token expires
- `5.1.0` - 05/01/26 - hardening: HTTP timeouts; classify 401/403/429/5xx in `handleException`; clear cookies + etags on auth error; backoff on rate-limit; watchdog warns when no successful update in N minutes; loud banner when token expired
- `5.0.15` - 12/31/24 - minor fixes
- `5.0.14` - 12/24/24 - Dynamic Polling
- `5.0.13` - 12/24/24 - restore original scheduling routine
- `5.0.10` - 12/21/24 - add some randomness
- `5.0.9` - 12/19/24 - try a different API when hitting 403 error
- `5.0.8` - 12/18/24 - added cookies found by @user3774
- `5.0.7` - 12/11/24 - try to match Home Assistant
- `5.0.6` - 12/05/24 - return to older API version (keeping eTag support)
- `5.0.5` - 11/12/24 - support eTag for locations call
- `5.0.4` - 11/09/24 - use newer API
- `5.0.2` - 11/03/24 - restore webhook
- `5.0.0` - 11/01/24 - fix Life360+ support (requires manual entry of `access_token`)
- `4.0.0` - 02/08/24 - implement new Life360 API

---

## Historical — Driver (5.1.4 and earlier)

Versions below come directly from the `Changes:` block previously at the top of [life360_driver.groovy](life360_driver.groovy). The driver had its own version stream that ran parallel to (and sometimes diverged from) the app; from `X.X.X` onward the two files share a single version line.

- `5.1.4` - 06/30/26 - add history preference
- `5.0.15` - 12/31/24 - minor fixes
- `5.0.12` - 12/24/24 - Dynamic Polling (mpalermo73)
- `5.0.12` - 12/24/24 - Improve Randomness (mpalermo73 / @user3774)
- `5.0.11` - 12/22/24 - bugfix when polling > 1 min
- `5.0.10` - 12/21/24 - add some randomness
- `5.0.9` - 12/19/24 - try a different API when hitting 403 error
- `5.0.8` - 12/18/24 - added cookies found by @user3774
- `5.0.7` - 12/11/24 - try to match Home Assistant
- `5.0.6` - 12/05/24 - return to older API version (keeping eTag support)
- `5.0.5` - 11/12/24 - support eTag for locations call
- `5.0.4` - 11/09/24 - use newer API
- `5.0.2` - 11/03/24 - restore webhook
- `5.0.0` - 11/01/24 - fix Life360+ support (requires manual entry of `access_token`)
- `4.0.0` - 02/08/24 - implement new Life360 API

---

## Pre-history

Life360+ began as "Life360 with States" — see the [original community thread](https://community.hubitat.com/t/release-life360-with-states-track-all-attributes-with-app-and-driver-also-supports-rm4-and-dashboards/18274) for the full pre-4.0 history of this app and driver.
