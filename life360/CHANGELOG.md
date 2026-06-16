# Life360+ Changelog

One file covering both [life360_app.groovy](life360_app.groovy) and [life360_driver.groovy](life360_driver.groovy). Hubitat has no built-in way to surface a changelog inside the hub UI, so this file replaces the per-file `Changes:` comment blocks that used to live at the top of each script.

Versions below `5.1.x` come from the original per-file change logs and are preserved verbatim, including the cases where the app and driver carried different version numbers. New releases are versioned together going forward.

Last published release on [the community thread](https://community.hubitat.com/t/life360/118544/929): **app 5.1.3, driver 5.1.4** (May–June 2026).

---

## Y.Y.Y — feature/async-member-fetch (unreleased)

Performance, reliability, and operator-visibility work on top of `X.X.X`.

### Performance
- Per-member location fetches converted from blocking `httpGet` to `asynchttpGet`, with a per-member in-flight guard so a slow Life360 response can't pile up requests when the next poll tick fires.
- Per-member exponential backoff on transient 5xx errors (1×, 2×, 4× the poll interval, capped at 5 min) instead of hammering the API at full rate.
- `fetchMembers()` converted to async.
- `buildPlacesContext()` runs once per poll cycle instead of rebuilding the sorted places map per member.
- `savedPlaces` JSON serialized once in the app and passed through to the driver, instead of re-serializing for every member every tick.
- `createChildDevices()` hoists `getChildDevices()` outside the loop.

### Polling / scheduling
- Circles endpoint polled once per poll tick (rate-limited to ≤1/min) to detect membership changes; replaces the fixed unconfigurable `fetchMembers()` timer.

### New endpoints / capabilities
- **Force Update** button on the settings page — POSTs `/circles/<id>/members/<id>/request` to push the member's phone for a fresh GPS fix. Confirmed working through Cloudflare WAF once cookies are present.
- **Check Token** button (`GET /users/me`) with inline status in STEP 1, so a freshly-pasted token can be validated without waiting for a poll cycle to fail.

### Notifications
- Token-expiry alerts now have a master enable toggle plus a configurable repeat interval (off / 2h / 6h / 12h / 24h / 48h); reminders self-cancel once a fresh token is pasted.

### Reliability — Cloudflare cookie handling (load-bearing)
- **Fixed a cookie-jar bug that could 403 the whole integration.** The async cookie capture replaced the entire jar with a single cookie whenever an incoming cookie name was already present — exactly the `__cf_bm` rotation case — silently dropping `_cfuvid`. Without both Cloudflare cookies, Life360 returns 403 within minutes and all polling stops; it failed silently and on a delay. Cookie capture now merges per cookie name (`mergeCookie()`: parse jar → upsert by name → re-serialize), so a rotating `__cf_bm` updates only its own entry and `_cfuvid` survives. See the README "Cookie handling" section.
- Added cookie-jar diagnostics: `captureCookiesAsync` logs `added/updated '<name>'; jar now [...]` (debug), and auth failures log `jar-at-failure [...]` so a token problem (jar intact) is distinguishable from a cookie-path problem (jar missing a Cloudflare cookie). Cookie **names** are logged by default; values only under *Log Raw Life360 Payloads*.

### Driver behavior changes
- Driver no longer applies the in-house speed/distance heuristic override for `inTransit` / `isDriving` — Life360's flags are trusted directly. Manual `transitThreshold` / `drivingThreshold` settings remain as explicit user overrides.
- Manual `sendEvent` dedup guards in the driver removed; Hubitat's built-in value-based deduplication is now relied on.
- New driver helper `displayMember()` honors the parent app's "Include Names in Logs" privacy toggle for log output.

### Diagnostics
- **Log Raw Life360 Payloads** toggle (default off) — logs the raw `location` map and the post-threshold computed state per member per poll. Used during the §2.5 heuristic-revert investigation; leave off in normal use.

### Documentation
- CODE_REVIEW updated through §4 / §5 / §7 / §9 with status markers, the §2.5 revert history, and §9 (undocumented Life360 API capabilities: force-update, single-circle fetch, `/users/me` validation).
- README expanded; STATE_REFERENCE kept in sync with new state vars and settings.

---

## X.X.X — fix/life360-bugs-cleanup-docs (unreleased)

First documentation pass plus a sweep of bugs, code-quality items, and UX defects against the published 5.1.3 / 5.1.4 baseline.

### Bug fixes (see CODE_REVIEW §2 / §3)
- `pollFreq = 0` (Disabled) no longer silently runs at 1–4 second polling because of unconditional jitter.
- Scheduler churn fixed: `scheduleUpdates()` tracks the currently-armed base rate and short-circuits no-op re-arms instead of stacking overlapping timers from `updated()` / `initialize()` / `handleTimerFired`.
- Dynamic-polling lockup against a stale Life360 `inTransit` flag mitigated (root cause documented in CODE_REVIEW §2.5; force-update endpoint added later in `Y.Y.Y` as the real fix).
- `installed()` no longer sends an event with the wrong attribute name; `address1prev` is properly seeded as `"No Data"`.
- `historyClearData` no longer throws `MissingPropertyException` and now clears the attributes it's supposed to clear.
- `sendHistory` HTML template uses the actually-defined `avatarFontSize` preference (was referencing undefined `fontSize`).
- `descriptionText` "has arrived" / "has left" ternary parenthesization fix (was always "arrived").
- HTML tile speed-display condition fix (the inner ternary was always truthy).
- `addChildDevice` no longer hardcodes hub ID `1234`.
- Dead `vcId` check removed from `createChildDevices`.
- Orphan child devices are removed when a member is deselected from `settings.users`.
- Watchdog warning only fires on the rising edge instead of every poll tick.
- `location.since` is null-guarded so a missing field can't abort `generatePresenceEvent` mid-update.
- When the token is flagged expired, polling slows to 5 minutes instead of firing at full rate while every call early-returns.

### Code quality (CODE_REVIEW §8)
- Shared `@Field static final Random` RNG (was allocating per `scheduleUpdates` / `handleTimerFired` tick).
- `getChildDevices()` hoisted out of inner loop.
- Dead code removed: `strToDate`, `state.presence`, `state.status`, `state.update`, duplicate `attribute "battery"` (covered by `capability "Battery"`).
- `haversine` made `static`.
- `int sEpoch` → `long` to avoid Y2K38 overflow in the HTML tile.
- Implicit Groovy globals (`placesMap`, `sortedPlaces`, `sortedMembers`, `thePlaces`, `theMembers`) given `def`.
- Log unit mismatch fixed (seconds reported as `ms`).

### UX
- Settings page restructured: STEP 5 "Verify Connectivity" with last-successful-fetch indicator; "Other Options" split into focused **Polling**, **Notifications**, **Logging**, **Map View** sections.
- "Refesh" → "Refresh" typo.
- Google Maps API key help text moved out of `description:` (placeholder text) into a `paragraph` with a bolded security note.
- Half-row width for all inputs so short enums don't render comically wide.
- Map View: explicit "Generate Map Link" / "Revoke Map Link" buttons with clearer OAuth instructions.
- `/view` HTML response now specifies UTF-8 charset.
- `fetchCircles` and similar log lines include the entity they fetched instead of an empty trailing colon.

### Privacy / security (CODE_REVIEW §6)
- New **Logging** section with two opt-in toggles, both default ON:
  - *Include Names and Places in Logs* — when off, member/place/circle names are replaced with UUIDs.
  - *Include Google Maps Link in Logs* — when off, the "moved" info log omits the satellite-view URL.
- README and inline app-page warnings call out that the `/view` link contains a live access token and that the Google Maps API key is necessarily embedded in page HTML (lock it down with HTTP-referrer + API restrictions in Google Cloud Console).

### Documentation (new)
- [README.md](README.md) rewritten.
- [CODE_REVIEW.md](CODE_REVIEW.md) added — findings ranked by severity with status markers.
- [STATE_REFERENCE.md](STATE_REFERENCE.md) added — every setting, state var, scheduled job, subscription, and child-device attribute.

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
