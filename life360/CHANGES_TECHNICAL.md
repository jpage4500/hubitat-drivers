- [Life360+ — Technical Change Summary](#life360--technical-change-summary)
  - [Security](#security)
  - [Bug fixes](#bug-fixes)
  - [Error handling](#error-handling)
  - [API / attribute correctness](#api--attribute-correctness)
  - [Groovy / platform](#groovy--platform)
  - [Named constants](#named-constants)
  - [Dead code](#dead-code)
  - [App (`life360_app.groovy`)](#app-life360_appgroovy)
    - [Async fetch architecture](#async-fetch-architecture)
    - [Scheduling](#scheduling)
    - [Token / session recovery](#token--session-recovery)
    - [Error handling](#error-handling-1)
    - [Cookie management](#cookie-management)
    - [New endpoints](#new-endpoints)
    - [Child device management](#child-device-management)
    - [Settings page](#settings-page)
    - [Dead code removed](#dead-code-removed)
  - [Driver (`life360_driver.groovy`)](#driver-life360_drivergroovy)
    - [Units](#units)
    - [Motion state](#motion-state)
    - [Presence event](#presence-event)
    - [Places](#places)
    - [Privacy / logging](#privacy--logging)
    - [HTML tile](#html-tile)
    - [`capability "Battery"`](#capability-battery)
    - [`installed()`](#installed)
    - [`historyClearData()`](#historycleardata)
    - [`sendHistory()`](#sendhistory)
    - [`haversine()`](#haversine)
    - [Dead code removed](#dead-code-removed-1)

# Life360+ — Technical Change Summary

### Security

- **Stored XSS in map view (SEC-1).** `membersJson` was injected raw into `<script>` blocks in `buildMapHtml` and `buildGoogleMapHtml`. `JsonBuilder` does not HTML-escape `<`/`>`, so a Life360 member name containing `</script>` could execute arbitrary JS in any browser loading the map page. Fixed: post-process `membersJson` with `.replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e")` — valid JSON unicode escapes that the HTML parser never sees as tag boundaries.
- **API key injection (SEC-2).** Google Maps API key embedded in `<script src>` attribute without HTML-encoding. A `"` in the key could break out of the attribute. Fixed: encode `&`/`"`/`<`/`>` before embedding.

### Bug fixes

- **`sEpoch.seconds` wrong epoch calculation (DRIVER-BUG-3).** `use(TimeCategory) { new Date(0) + sEpoch.seconds }` treated the Unix timestamp as a duration, producing dates ~55,000 years in the future. Fixed: `new Date(sEpoch * 1000L)`. _(Note: claimed fixed in `41f8549` but the change was never actually applied; corrected in the subsequent commit.)_
- **Integer division truncated lat/lng coordinates to whole degrees (DRIVER-BUG-4).** `Math.round(toDouble(lat) * LAT_LNG_PRECISION) / LAT_LNG_PRECISION` — `LAT_LNG_PRECISION` is a `long`, so `long / long` in Groovy is integer division, truncating e.g. `37.12345` to `37`. This caused the Google Maps link in the "moving @" info log to point to the wrong location (city center instead of actual position), and corrupted history storage and haversine distance calculations. Same bug existed in `saveLocationHistory`. Fixed: `/ (LAT_LNG_PRECISION as double)` to force floating-point division.
- **`unschedule()` killed the token-expiry reminder chain (BUG-SCHED).** `scheduleUpdates()` calls `unschedule()` (no argument), which cancels every scheduled job. If the token was already expired when the user clicked Done with a different poll frequency, the reminder chain was silently killed and never re-armed. Fixed: `if (state.tokenLikelyExpired) notifyTokenExpired()` immediately after `unschedule()`.
- **In-flight keys survived hub restart (STATE-2).** `inflight-<memberId>` is set before `asynchttpGet` and cleared in the callback. A hub reboot mid-request meant the callback never fired and the key was never cleared, permanently blocking that member's poll loop. Fixed: `initialize()` now calls `clearSessionCache()` before re-arming the schedule.
- **`handleTokenProbeResponse` could stay stuck in slow-poll on a garbled 200 (ERR-2).** `captureUnitOfMeasure(response.json)` ran before `state.tokenLikelyExpired = false`. A Cloudflare interstitial on a 200 would throw and leave the flag set despite a valid auth response. Fixed: all state clears moved before the JSON parse.

### Error handling

- **`handleUserSettingsResponse` missing try/catch on `response.json` (ERR-1).** Every other async callback's 200 path wraps `response.json` in try/catch; this one was missed. Fixed.
- **`response.getErrorMessage()` returning null (ERR-4).** Six handlers embedded the result directly in GStrings; when null, it rendered as the literal string `"null"` in logs and the UI banner. Fixed: `?: "(no details)"` on all call sites.

### API / attribute correctness

- **Implicit Boolean coercion in `sendEvent` (API-1).** `inTransit`, `isDriving`, `charge`, `wifiState` were passed as Groovy `Boolean` to `sendEvent`. Hubitat coerces to string, but it's implicit. Fixed: `.toString()` explicit on each.
- **`shareLocation` sent as `"1"`/`"0"` (API-5).** Life360 API returns `"1"`/`"0"` for this field; it was passed through raw. Any Rule Machine rule comparing `shareLocation == "true"` silently never matched. Fixed: `toBool(member.features.shareLocation).toString()`.
- **`contact`, `acceleration`, `switch` declared as `"string"` (API-4).** Hubitat's built-in dashboard tile support for Contact Sensor, Acceleration Sensor, and Switch capabilities requires the attribute declared as `enum`. Fixed: proper `enum` declarations with canonical value lists.

### Groovy / platform

- **`toDouble(0)` falsy-zero (GROOVY-1).** `if (object) return object.toDouble()` — Groovy evaluates integer `0` as false, so a legitimately-zero speed value took the null branch. Functionally harmless (result was still `0.0`) but logically wrong. Fixed: `if (object != null)`.
- **`settings.pollFreq.toInteger()` on Integer (GROOVY-2).** Hubitat sometimes returns enum settings as `Integer`; calling `.toInteger()` on an `Integer` fails in the sandbox. Fixed: `settings.pollFreq?.toString() ?: "60"` before `.toInteger()`.

### Named constants

Added to eliminate magic literals: `FORCE_UPDATE_FETCH_DELAY_SECS = 6`, `CIRCLES_API_VERSION = 4`, `MAX_BACKOFF_SHIFT = 6` (app); `EARTH_RADIUS_KM = 6372.8`, `KM_PER_MI = 1.609344` (driver; renamed from `KM_TO_MI`). Replaced `/ 100000.0` in `getHistory()` with `(LAT_LNG_PRECISION as double)`. Bool `input` `defaultValue:` changed from string literals (`"false"`) to boolean literals.

### Dead code

- Single-arg `displayMember(String firstName)` overload had no call sites (all callers use the two-arg form with pre-resolved `showNames`). Deleted.
- Renamed `binTransita` → `motionLabel` (legacy naming artifact).
- Removed commented-out log lines in `dynamicPolling()`.
- Removed unreachable `else { listSize1 = 0 }` branch in `sendHistory()` (list is guaranteed non-null by the guard two lines above).

---

## App (`life360_app.groovy`)

### Async fetch architecture

- `fetchMemberLocation` converted from synchronous `httpGet` to `asynchttpGet` → `handleMemberLocationResponse`. Per-member in-flight guard (`state["inflight-${memberId}"]`) skips re-entry if a prior request is still pending (timeout + 2s window).
- `fetchMembers` converted from synchronous `httpGet` to `asynchttpGet` → `handleMembersResponse`.
- `places` context built once per poll cycle in `buildPlacesContext()` (sorted places map + serialized JSON + home reference) and passed to each member fetch as `data.ctx`, rather than rebuilt per-member per-tick inside `notifyChildDevice`.
- `savedPlaces` JSON serialized once in the app and passed as a string to the driver; previously the driver re-serialized a raw Map N times per tick.

### Scheduling

- `scheduleSlowTimer()` replaces `scheduleUpdates()` (renamed and simplified). The old no-op guard (`state.scheduledBaseSecs` tracking) was removed — the rebuild is now always a full `unschedule()` + `schedule()` so there is no hidden scheduling state to go stale. `scheduledBaseSecs`, `pollIntervalSecs`, `dynamicPollingActive`, and `memberInTransit` are all obsolete and removed by `removeObsoleteStateKeys()`.
- Polling randomness jitter (`±0–4s`) removed — was a workaround for an old communications issue; no longer serves a purpose.
- `pollFreq = 0` (Disabled) now reliably disables polling; previously the jitter addition caused it to schedule at 1–4s regardless.
- Polling slows to 300s when `tokenLikelyExpired` is set, regardless of user's poll setting.
- **Dynamic polling** for in-transit members now uses per-member `runIn` chains (`ensureFastChain` / `fastPollMember`) rather than switching the slow-timer rate. Each in-transit member gets its own independent chain at `dynamicPollFreq`; the slow timer continues polling all members at `pollFreq`. The chain ends when the member stops moving or `scheduleSlowTimer()` is called. The old `dynamicPolling()` function (global rate switch) is gone.

### Token / session recovery

- **Auto-recovery from `tokenLikelyExpired`:** `handleSlowTimer` now calls `probeTokenAfterExpiry()` → async `GET /users/me` → `handleTokenProbeResponse` when the flag is set, instead of returning immediately. A 200 response clears `tokenLikelyExpired`, resets `failCount`, cancels reminder schedules, and calls `scheduleSlowTimer()` to restore normal polling. Previously the app was dead until manual intervention.
- New **Check Token** button calls synchronous `GET /users/me`, populates `state.tokenStatus` with a success/failure span, and captures units preference. One-shot flag (`state.tokenStatusPending`) ensures the result persists through the one page re-render after the button press.
- `refreshUserSettings()` fires async `GET /users/me` on `installed()` and `updated()` to capture the account's `settings.unitOfMeasure` (`"i"` / `"m"`). `getUnitIsMiles()` exposes this to the driver.
- Token expiry notifications: added master `notifyTokenExpiry` toggle and `notifyRepeatHours` repeat schedule. `scheduleTokenExpiryReminder()` / `sendTokenExpiryReminder()` implement the repeat chain; `unschedule("sendTokenExpiryReminder")` cancels on `updated()`.

### Error handling

- Auth failures (`401`/`403`) in `handleMemberLocationResponse` now log `jar-at-failure [...]` (cookie names) before clearing session, distinguishing a token problem from a cookie-path problem.
- Per-member exponential transient backoff for `502`/`503`/`504`/`520`/`522`/`525` **and** network-level failures (no HTTP status): `state["transientCount-${memberId}"]` increments on each consecutive error; delay = `min(pollSecs * 2^(count-1), 300s)`. Bit-shift exponent capped at 6 to prevent `long` overflow. Clears on success.
- Rate-limit (`429`) handling extracts `Retry-After` header from async response headers (Map lookup, not `getFirstHeader`), adds 10s margin.
- `clearSessionCache()` extended to also remove `inflight-*`, `transientCount-*`, `transientUntilMs-*` keys in addition to `etag-*` and `cookies`.
- Watchdog fires only on the rising edge (`state.watchdogWarned` flag); previously logged on every tick after threshold exceeded.

### Cookie management

- `captureCookiesAsync()` now calls `mergeCookie()` instead of replacing the entire jar. `mergeCookie()` parses the existing jar, upserts the incoming cookie by name, and re-serializes — preserving `_cfuvid` when `__cf_bm` rotates.
- `cookieJarSummary()` added: logs cookie **names** by default (safe to always log), optionally with 8-char value heads under `logRawPayload`. Used at auth failure and in `clearSessionCache`.
- `captureCookies()` (sync path) logs raw `Set-Cookie` values under `logRawPayload`.
- `state["etag-${memberId}"]` lookup switched from `response.headers?.get("l360-etag")` to case-insensitive `response.headers?.find { it.key?.equalsIgnoreCase("l360-etag") }?.value` — HTTP headers are case-insensitive by spec but the async headers Map is not.

### New endpoints

- **Force Update:** `POST /circles/<circleId>/members/<memberId>/request` with `body: {type: "location"}` via `forceMemberUpdate()` → `asynchttpPost` → `handleForceUpdateResponse`. Schedules `fetchLocations` 6 seconds later on success. UI: member dropdown + **Force Update** button in a section gated on `!isEmpty(settings.users)`.
- **Circles poll:** `handleSlowTimer` fires `asynchttpGet` to `GET /circles` (v4, `CIRCLES_API_VERSION`) at most once per `max(pollFreq, 60)` seconds (rate-limited via `state.lastCirclesPollMs`). `handleCirclesPollResponse` compares `circle.memberCount` against `state.memberCount`; triggers `fetchMembers()` on change or on first-seen baseline. v4 is used for both the setup button and the steady-state poll. The `lastCirclesFetchMs` key is obsolete and removed by `removeObsoleteStateKeys()`.
- **Membership → device reconciliation:** `handleMembersResponse` (the `fetchMembers` callback) now calls `createChildDevices()` on a 200 — creating devices for newly selected members, removing orphans — and pushes refreshed name/avatar/location to every selected member that has a device. Previously the membership poll refreshed only `state.members`; device create/cleanup happened solely on `installed()`/`updated()`.

### Child device management

- `createChildDevices()` hoists `getChildDevices()` once into a `Map childMap` keyed by DNI (was O(N²) hub device-list walks).
- Added null guard: skips members not found in `state.members` with a `log.warn` rather than throwing NPE.
- Orphan cleanup added: after create pass, removes child devices whose DNI is not in `settings.users`.
- `addChildDevice` call no longer passes hardcoded hub ID `1234`.
- Dead `vcId` check removed.

### Settings page

- One-shot pending flags (`state.tokenStatusPending`, `state.forceUpdateStatusPending`) prevent stale results from persisting across page navigations while ensuring button results survive the single re-render after a button press.
- All short `input` elements get `width: 6` (half-width) to avoid comically wide dropdowns.
- "Other Options" section split into **Polling**, **Notifications**, **Logging**, **Map View**.
- New **STEP 5: Verify Connectivity** shows age of `state.lastSuccessMs` as "Ns ago / N min ago / N hr ago".
- **Revoke Map Link** button added; `revokeViewLink()` nulls `state.accessToken`.
- OAuth instructions moved from `description:` (placeholder text) to a `paragraph`.

### Dead code removed

- `state.deviceId` generation (was written, never read or sent in any header).
- `@Field static final Random RNG` and `import groovy.transform.Field` (served only the removed jitter).

---

## Driver (`life360_driver.groovy`)

### Units

- Driver calls `parent?.getUnitIsMiles()` to get the account-level units preference; falls back to local `isMiles` toggle if the app hasn't fetched it yet.
- `isMiles` preference description updated to clarify it is a fallback.

### Motion state

- `inTransit` / `isDriving` now trust Life360's flags directly. `transitThreshold` / `drivingThreshold` are explicit user overrides, not the default path. Previously, the speed-based heuristic was applied unconditionally when non-zero; now it's only applied when the user has set a non-zero threshold.
- Speed-based motion log (`"moving @ N mph/kph"`) now fires only when `inTransit || isDriving`, not on every poll.
- Threshold-override debug log only fires when a threshold is actually active.

### Presence event

- Manual `sendEvent` dedup guards removed; Hubitat's built-in value-based deduplication relied on instead.
- `home == null` guard added: returns `false` with a `log.warn` if the selected home place is not found (stale `settings.place`), instead of NPE-crashing the entire update.
- `location.since` null-guarded: `.toLong()` called only when non-null; defaults to `0L`.
- `descriptionText` "has arrived" / "has left" ternary parenthesization fixed (operator precedence bug caused it to always produce "arrived").
- `state.presence`, `state.status`, `state.update` redundant state writes removed.

### Places

- `thePlaces` parameter accepts either a pre-serialized JSON string (from app's `buildPlacesContext`) or a raw Map (backward compat). String path skips re-serialization.

### Privacy / logging

- `displayMember(firstName)` helper added: returns `firstName` when parent's `getShowNamesInLogs()` is true, otherwise parses the memberId from `device.deviceNetworkId` (`"<appId>.<memberId>"`).
- Google Maps link in "moving" log gated behind `parent?.getShowMapsLink()`.
- Raw payload log (`parent?.getLogRawPayload()`) gates the full `location` map dump.

### HTML tile

- `int sEpoch` → `long` to avoid Y2K38 overflow once `since` epoch value exceeds `2^31` seconds.
- Speed display condition in tile fixed (inner ternary was always truthy due to same precedence issue as `descriptionText`).

### `capability "Battery"`

- Redundant explicit `attribute "battery"` removed; covered by `capability "Battery"`.

### `installed()`

- `sendEvent(name: "address1prev", value: "No Data")` corrected (was `sendEvent(name: address1prev, value: address1prev)` — wrong attribute name, wrong value).

### `historyClearData()`

- Fixed `MissingPropertyException` caused by undefined `msgValue`, `logCharCount`, `logCharCount1`, `historyLog` variables.
- Correct attribute names used (`numOfCharacters` not `numOfCharacters1`, `lastLogMessage` not `lastLogMessage1`).

### `sendHistory()`

- `fontSize` reference corrected to `avatarFontSize` (the defined preference).

### `haversine()`

- Made `static` (pure math, no instance state).

### Dead code removed

- `strToDate()` — unused.
- Redundant `prevBattery` / `prevWifiState` reads and the no-op dedup block that used them.
