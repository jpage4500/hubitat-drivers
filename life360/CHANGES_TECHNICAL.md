# Life360+ — Technical Change Summary

*Developer-level delta from the 5.1.3/5.1.4 baseline (upstream jpage4500/master).*

---

## App (`life360_app.groovy`)

### Async fetch architecture
- `fetchMemberLocation` converted from synchronous `httpGet` to `asynchttpGet` → `handleMemberLocationResponse`. Per-member in-flight guard (`state["inflight-${memberId}"]`) skips re-entry if a prior request is still pending (timeout + 2s window).
- `fetchMembers` converted from synchronous `httpGet` to `asynchttpGet` → `handleMembersResponse`.
- `places` context built once per poll cycle in `buildPlacesContext()` (sorted places map + serialized JSON + home reference) and passed to each member fetch as `data.ctx`, rather than rebuilt per-member per-tick inside `notifyChildDevice`.
- `savedPlaces` JSON serialized once in the app and passed as a string to the driver; previously the driver re-serialized a raw Map N times per tick.

### Scheduling
- `scheduleUpdates()` tracks `state.scheduledBaseSecs` and short-circuits no-op re-arms; previously stacked overlapping timers on every `updated()` / `initialize()` / `handleTimerFired` call.
- Polling randomness jitter (`±0–4s`) removed — was a workaround for an old communications issue; no longer serves a purpose.
- `pollFreq = 0` (Disabled) now reliably disables polling; previously the jitter addition caused it to schedule at 1–4s regardless.
- Polling slows to 300s when `tokenLikelyExpired` is set, regardless of user's poll setting.
- Dynamic polling mode transition now happens immediately in `notifyChildDevice` when `inTransit` flips, instead of after a full poll cycle in `fetchLocations` (which ran before async responses returned).

### Token / session recovery
- **Auto-recovery from `tokenLikelyExpired`:** `handleTimerFired` now calls `probeTokenAfterExpiry()` → async `GET /users/me` → `handleTokenProbeResponse` when the flag is set, instead of returning immediately. A 200 response clears `tokenLikelyExpired`, resets `failCount`, cancels reminder schedules, and calls `scheduleUpdates()` to restore normal polling. Previously the app was dead until manual intervention.
- New **Check Token** button calls synchronous `GET /users/me`, populates `state.tokenStatus` with a success/failure span, and captures units preference. One-shot flag (`state.tokenStatusPending`) ensures the result persists through the one page re-render after the button press.
- `refreshUserSettings()` fires async `GET /users/me` on `installed()` and `updated()` to capture the account's `settings.unitOfMeasure` (`"i"` / `"m"`). `getUnitIsMiles()` exposes this to the driver.
- Token expiry notifications: added master `notifyTokenExpiry` toggle and `notifyRepeatHours` repeat schedule. `scheduleTokenExpiryReminder()` / `sendTokenExpiryReminder()` implement the repeat chain; `unschedule("sendTokenExpiryReminder")` cancels on `updated()`.

### Error handling
- Auth failures (`401`/`403`) in `handleMemberLocationResponse` now log `jar-at-failure [...]` (cookie names) before clearing session, distinguishing a token problem from a cookie-path problem.
- Per-member exponential transient backoff for `502`/`503`/`504`/`520`: `state["transientCount-${memberId}"]` increments on each consecutive error; delay = `min(pollSecs * 2^(count-1), 300s)`. Bit-shift exponent capped at 6 to prevent `long` overflow. Clears on success.
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
- **Circles poll:** `handleTimerFired` fires `asynchttpGet` to `GET /circles` once per minute (rate-limited via `state.lastCirclesFetchMs`). `handleCirclesPollResponse` compares `circle.memberCount` against `state.memberCount`; triggers `fetchMembers()` on change or on first-seen baseline.

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
