# Life360+ Changelog

One file covering both [life360_app.groovy](life360_app.groovy) and [life360_driver.groovy](life360_driver.groovy). Hubitat has no built-in way to surface a changelog inside the hub UI, so this file replaces the per-file `Changes:` comment blocks that used to live at the top of each script.

Versions below `5.1.x` come from the original per-file change logs and are preserved verbatim, including the cases where the app and driver carried different version numbers. New releases are versioned together going forward.

Last published release on [the community thread](https://community.hubitat.com/t/life360/118544/929): **app 5.1.3, driver 5.1.4** (May–June 2026).

---

## Y.Y.Y — unreleased

Significant update from the 5.1.3 / 5.1.4 baseline. Core theme: **the integration now stays online** through Cloudflare cookie rotations and transient Life360 outages that previously caused permanent silent failures.

### Reliability

**Cloudflare cookie bug fixed.** Life360 sits behind Cloudflare, which issues two cookies: `_cfuvid` (stable) and `__cf_bm` (rotates every ~30 min). The async cookie handler was overwriting the entire cookie jar with just the fresh rotating cookie each time `__cf_bm` refreshed — silently dropping `_cfuvid`. Without both, Cloudflare returns 403 within minutes and polling goes completely dark. The failure was delayed and silent, which made it look like a Life360 outage. Fixed: cookie updates now merge by name so only the rotating entry is replaced. See [Cookie handling](README.md#cookie-handling-load-bearing--dont-break-this) in the README.

**Auto-recovery from token-expired state.** Three consecutive 401/403 errors still flag the token as likely expired and slow polling to 5-minute ticks — but the app now probes `/users/me` on each of those ticks. The moment Life360 responds normally (service healed, transient blip cleared), polling resumes at the normal rate automatically. Previously the integration was permanently dead until you manually re-pasted a token.

### New capabilities

- **Force Update** — settings-page button that POSTs a location-refresh request to a member's phone. Life360 signals the device for a fresh GPS fix (~5 seconds), which the next poll cycle picks up.
- **Check Token** — validates your access token with an inline success/failure result on the settings page, including a "Hi, name!" confirmation. Also re-reads the account's units preference.
- **Units auto-detected** — speed and distance follow your Life360 account's imperial/metric preference, fetched automatically from `/users/me`. The per-device `isMiles` toggle is a fallback only.
- **Dynamic polling reacts immediately** — when a member starts or stops moving, the polling rate switches on the same tick instead of lagging a full cycle.

### Settings page

"Other Options" reorganized into focused **Polling**, **Notifications**, **Logging**, and **Map View** sections. New **STEP 5: Verify Connectivity** shows how long ago the last successful fetch was. Map View has explicit Generate/Revoke buttons for the OAuth link.

### Notifications

Token-expiry alerts now have a master enable/disable toggle plus a configurable repeat reminder (2 h / 6 h / 12 h / 24 h / 48 h / never). Reminders stop automatically when a fresh token is pasted or auto-recovery succeeds.

### Privacy

Two opt-in logging toggles under app settings → Logging, both default on: **Include Names and Places in Logs** (off = UUIDs only, safe for sharing) and **Include Google Maps Link in Logs** (off = coordinates omitted from the "moved" log line).

### Performance

All member location fetches are now async, with a per-member in-flight guard so a slow Life360 response can't pile up duplicate requests. Exponential backoff on repeated 5xx errors (capped at 5 min) instead of hammering the API at full rate. Places context is built once per poll cycle and shared across all member fetches.

### Bug fixes

Against the 5.1.3 / 5.1.4 baseline:

- `pollFreq = 0` (Disabled) now reliably disables polling — unconditional jitter was causing it to run at 1–4s regardless.
- `scheduleUpdates()` no longer stacks overlapping timers when called from `updated()` / `initialize()` / `handleTimerFired` in the same cycle.
- `installed()` correctly seeds `address1prev` as `"No Data"` (was crashing with wrong attribute name and value).
- `historyClearData` no longer throws `MissingPropertyException` and now clears the attributes it's supposed to.
- "Has arrived" / "has left" in `descriptionText` now generates correctly (operator precedence fix).
- HTML tile speed display fixed (same precedence issue).
- Orphan child devices removed when a member is deselected from `settings.users`.
- `addChildDevice` no longer passes hardcoded hub ID `1234`.
- Watchdog warning fires only on the rising edge instead of repeating every tick.
- Null guards added for `settings.users`, stale `home` place, and missing `location.since` — these previously caused NPE-aborted updates in normal operation.
- Metric "km from Home" status now has correct spacing.

### Documentation

[README.md](README.md) rewritten. [CODE_REVIEW.md](CODE_REVIEW.md) added (findings by severity). [STATE_REFERENCE.md](STATE_REFERENCE.md) added (every setting, state var, scheduled job, and attribute). Developer-level change details in [CHANGES_TECHNICAL.md](CHANGES_TECHNICAL.md).

---

## Historical — App (5.1.3 and earlier)

Versions below come directly from the `Changes:` block previously at the top of [life360_app.groovy](life360_app.groovy).

- `5.1.3`  - 05/09/26 - add `/view` endpoint to view members on a map
- `5.1.2`  - 05/01/26 - merge in changes by iEnam: API change (`api-cloudfront.life360.com`); better cookie handling
- `5.1.1`  - 05/01/26 - add device notification when token expires
- `5.1.0`  - 05/01/26 - hardening: HTTP timeouts; classify 401/403/429/5xx in `handleException`; clear cookies + etags on auth error; backoff on rate-limit; watchdog warns when no successful update in N minutes; loud banner when token expired
- `5.0.15` - 12/31/24 - minor fixes
- `5.0.14` - 12/24/24 - Dynamic Polling
- `5.0.13` - 12/24/24 - restore original scheduling routine
- `5.0.10` - 12/21/24 - add some randomness
- `5.0.9`  - 12/19/24 - try a different API when hitting 403 error
- `5.0.8`  - 12/18/24 - added cookies found by @user3774
- `5.0.7`  - 12/11/24 - try to match Home Assistant
- `5.0.6`  - 12/05/24 - return to older API version (keeping eTag support)
- `5.0.5`  - 11/12/24 - support eTag for locations call
- `5.0.4`  - 11/09/24 - use newer API
- `5.0.2`  - 11/03/24 - restore webhook
- `5.0.0`  - 11/01/24 - fix Life360+ support (requires manual entry of `access_token`)
- `4.0.0`  - 02/08/24 - implement new Life360 API

---

## Historical — Driver (5.1.4 and earlier)

Versions below come directly from the `Changes:` block previously at the top of [life360_driver.groovy](life360_driver.groovy). The driver had its own version stream that ran parallel to (and sometimes diverged from) the app; from `X.X.X` onward the two files share a single version line.

- `5.1.4`  - 06/30/26 - add history preference
- `5.0.15` - 12/31/24 - minor fixes
- `5.0.12` - 12/24/24 - Dynamic Polling (mpalermo73)
- `5.0.12` - 12/24/24 - Improve Randomness (mpalermo73 / @user3774)
- `5.0.11` - 12/22/24 - bugfix when polling > 1 min
- `5.0.10` - 12/21/24 - add some randomness
- `5.0.9`  - 12/19/24 - try a different API when hitting 403 error
- `5.0.8`  - 12/18/24 - added cookies found by @user3774
- `5.0.7`  - 12/11/24 - try to match Home Assistant
- `5.0.6`  - 12/05/24 - return to older API version (keeping eTag support)
- `5.0.5`  - 11/12/24 - support eTag for locations call
- `5.0.4`  - 11/09/24 - use newer API
- `5.0.2`  - 11/03/24 - restore webhook
- `5.0.0`  - 11/01/24 - fix Life360+ support (requires manual entry of `access_token`)
- `4.0.0`  - 02/08/24 - implement new Life360 API

---

## Pre-history

Life360+ began as "Life360 with States" — see the [original community thread](https://community.hubitat.com/t/release-life360-with-states-track-all-attributes-with-app-and-driver-also-supports-rm4-and-dashboards/18274) for the full pre-4.0 history of this app and driver.
