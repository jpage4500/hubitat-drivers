# Life360+ Changelog

## 5.2.5

**Less hub I/O, a new `isMoving` attribute, and a fix for phones that keep fast polling after they stop.**

- **Fewer state writes:** the roster-refresh time is kept in memory instead of `state`, `lastSuccessMs` is written at most once a minute, and unchanged etag/cookie/roster values are no longer rewritten.
- **Member roster:** the per-minute v4 `/circles` poll (which no longer returns `memberCount`) is replaced by re-reading the roster (`/members`) at most every 15 minutes (about every 5 minutes until a roster has loaded), and immediately on Done. It picks up joins and leaves; the roster is used for names and membership only, and its location is never pushed to devices (locations come from the per-member poll). A response with no (or an empty) member list no longer wipes the saved roster. A deleted circle (404) or denied access (401/403) now says what to do.
- **Heartbeat Logging:** new setting (default on). The heartbeat is logged once per roster refresh instead of every poll.
- **Watchdog:** stays idle when no circle or members are selected, restarts after Done or token recovery, and a routine roster refresh no longer erases a watchdog or error banner.
- **Unchanged responses are skipped.** Life360 no longer answers `If-None-Match` with a 304 (checked with curl: it returns a full 200 carrying the same etag). The app treats a 200 whose etag matches the one it sent as unchanged and skips the JSON parse and the driver call, for parked and moving members alike (an unchanged etag means Life360 has no new fix: none of 211 unchanged-etag polls carried a new location timestamp, and about 1 to 2% of changed-etag polls carry none). A phone's `lastUpdated` and tile timestamp therefore move when its data changes (about every 40 seconds while driving, every few minutes when parked) instead of every poll. Done, refresh, Fetch Locations and Fetch Places drop the stored etags, so the next poll reaches the driver (changed settings, new attributes).
- **Simpler request path:** the per-member in-flight guard is removed (the HTTP timeout already ends a hung request before the member's next poll), and a device Refresh no longer cancels running fast-poll chains.
- **Rate limits:** the 20-second fast-poll chain now pauses while Life360 is rate-limiting (HTTP 429), like the normal poll, instead of continuing to request.
- **A phone that has stopped no longer holds fast polling on.** Life360 can keep `inTransit` (and even the last speed) set for a phone that is sitting still. If a member Life360 calls in transit has sent no new data (the etag has not changed) for 240 seconds, the app makes one last driver call that stops fast polling. This has no effect when a Minimum Transit Speed is set (the driver ignores it). The longest gap between new fixes seen while someone was actually moving was about 60 seconds, so 240 seconds leaves a wide margin; a longer silence while driving (a tunnel) can briefly flip `isMoving`. The evidence behind this choice is in APP_SPEC.md ("Why stillness is decided by the etag").
- **New `isMoving` attribute** (true/false): `inTransit` AND Life360 has sent new data (a changed etag) within that window. It goes false when that check passes (240 seconds without new data), usually well before Life360 clears `inTransit`, so a rule that wants "parked" can trigger on it. `inTransit` and `isDriving` still show exactly what Life360 sends; with a Minimum Transit Speed set, `isMoving` equals `inTransit`. The HTML tile label, the map page and the "started moving" / "stopped moving" info lines (Driving Logging off) follow it, and the "moving @" line stops once the phone is judged still. A state where Life360 has `isDriving` true but `inTransit` false no longer shows "Driving" on the tile or logs start/stop lines.
- **Debug lines:** the driver's per-update debug line now starts with the member's name (its misleading "changed:" label is gone), shows `isMoving` next to `inTransit` and `isDriving`, and coordinates and addresses are hidden when "Include Names and Places in Logs" is off.
- **Smaller driver changes:** `userActivity` reports `unknown` when Life360 sends none; a negative speed (no reading) counts as zero; with several phone numbers or emails on a member, the last of each is used, decided once.
- The unused places data is no longer built or passed to the driver.

## 5.2.4

**HTTP 408 is now treated as a transient error.** Life360 sometimes answers a slow request with 408 Request Timeout, and the next poll succeeds. It used to fall through to the generic error path and log a red `unexpected response:408`. It now gets the same handling as a 5xx: a warning, per-member exponential backoff, and a reset on the next successful fetch.

## 5.2.3

**Any 5xx is now treated as a transient error.** Retryable statuses were an explicit allow-list that omitted 500 — which Life360 routinely returns for a single member while the rest of the circle succeeds. A 500 therefore fell through to the generic error path: red `unexpected response:500` in the log, no per-member backoff, retried at full poll rate. The check is now "any 5xx except the permanently-fatal 501/505", so unanticipated codes (Cloudflare has a dozen in the 520s) get exponential backoff instead of log spam.

## 5.2.2

**Fixed the heartbeat log always showing `memberCount:0`** on the newer `/circles` API endpoint, which no longer returns `memberCount`.

**Added a "Driving Logging" option.** When off, a member in motion is logged only when they start and stop moving, instead of on every poll. Reduces log spam when several members are travelling.

## 5.2.1

**Fixed `captureCookiesAsync` throwing `MissingMethodException` on `tokenize()`**, which repeated every poll cycle.

## 5.2.0

Significant update from the 5.1.3 / 5.1.4 baseline. Core theme: **the integration now stays online** through Cloudflare cookie rotations and transient Life360 outages that previously caused permanent silent failures.

- **Auto-recovery from an expired-token state.** After 3 consecutive 401/403 the app slows to 5-minute ticks and probes `/users/me`; the moment Life360 answers normally, polling resumes. Previously it stayed dead until you re-pasted a token. Token-expiry notifications now have a master toggle and a repeat reminder (2 / 6 / 12 / 24 / 48 h / never) and stay armed when you change the poll frequency.
- **Hub reboots no longer block polling** for members whose fetch was in flight.
- **Cloudflare cookie fix.** The async cookie handler overwrote the whole jar with the freshly rotated `__cf_bm`, silently dropping `_cfuvid`, so polling went dark within minutes. Cookie updates now merge by name.
- **Force Update** button (asks a member's phone for a fresh GPS fix) and **Check Token** button (inline result, re-reads the account's units preference).
- **Units follow your Life360 account** (imperial/metric); the per-device `unitOverride` is a fallback (the old `isMiles` bool is migrated).
- **Dynamic polling** reacts on the same tick a member starts or stops moving.
- **Membership auto-sync:** a circle poll noticed joins and leaves and reconciled child devices (replaced in 5.2.5 by the periodic roster refresh).
- **Settings page** reorganized into Polling, Notifications, Logging and Map View, plus STEP 5 (Verify Connectivity) and Generate/Revoke buttons for the map link.
- **Privacy:** two logging toggles, both default on: _Include Names and Places in Logs_ (off = ids only) and _Include Google Maps Link in Logs_. Member and place names in the map view are HTML-escaped.
- **Performance:** member fetches are async; repeated 5xx errors back off exponentially (capped at 5 min); the places context is built once per poll cycle.
- Docs added: [README.md](README.md), [STATE_REFERENCE.md](STATE_REFERENCE.md), [APP_SPEC.md](APP_SPEC.md).

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
