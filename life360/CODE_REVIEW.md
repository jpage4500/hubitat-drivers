# Life360+ Code Review

Files reviewed: [life360_app.groovy](life360_app.groovy), [life360_driver.groovy](life360_driver.groovy)
Companion doc: [STATE_REFERENCE.md](STATE_REFERENCE.md) (every setting, state var, scheduled job, and child-device attribute).

Each finding is a numbered sub-heading (e.g. `2.3`) so you can reference it by number. Format is the same throughout:

- `### N.M  file:line — short title`
- **Problem:** one or two lines
- **Fix:** one line
- optional code block

---

## 1. Fragile API plumbing — treat as load-bearing

Life360 has no public API. The HTTP path is reverse-engineered and the app has been broken-then-rescued multiple times over 2+ years — most recently by the May 2026 iEnam merge (`3f78018`) which restored functionality after a long outage where the app was completely denied from connecting. **What's in master today is a known-working configuration.** Cleanups are welcome, but the wire-level behavior must stay identical and any change here needs soak testing against a live circle.

### 1.1  Triage history

- 2024-Dec `8df7208` user3774 — figured out which cookies Life360 requires; reverted `fetchMembers` to older `/v3` API
- 2024 `e63d574` — tried to match Home Assistant's TLS fingerprint (couldn't change TLS from Hubitat — still an open limitation)
- 2024 `b16d0e1` — try different API on 403
- 2026-May `3f78018` iEnam — the rescue. Switched to `api-cloudfront.life360.com`, started capturing cookies on **every** API call, wrapped capture in try/catch

### 1.2  Load-bearing code — change deliberately

| Function | Why it matters |
| --- | --- |
| `life360BaseUrl()` | Must be `api-cloudfront.life360.com/v3` |
| `getHttpHeaders()` | Exact `User-Agent` / `Accept` / `cache-control`, and the **absence** of other headers — extras trigger 403 |
| `captureCookies()` / `captureCookiesAsync()` | `__cf_bm` is Cloudflare's bot-management cookie. Without it: 403 within minutes |
| `captureCookies()` called on **every** API response | Including sync circles/places/members calls — key insight in `3f78018` |
| eTag + `If-None-Match` on per-member calls | 304-not-modified path |
| 5-second throttle in `fetchLocations()` | Avoid rate-limit retaliation |

### 1.3  Cookie tokenizer `tokenize(';|,')` — not a bug

**Problem:** looks like a `,` mis-delimiter.
**Why it's fine:** `__cf_bm` is base64-url-safe (no commas) and `;` always comes first as the `Set-Cookie` segment separator. `tokenize(';|,')?.getAt(0)` and `tokenize(';')?.getAt(0)` produce identical output. Leave it alone.

---

## 2. Bugs — High Priority

### 2.1  `life360_driver.groovy:147` — `installed()` sends wrong event name

**Problem:** `address1prev` is used as a value, not a string literal, so the call becomes `sendEvent(name:"No Data", ...)`.
**Fix:** quote the attribute name.

```groovy
// current
address1prev = "No Data"
sendEvent(name: address1prev, value: address1prev)
// fix
sendEvent(name: "address1prev", value: "No Data")
```

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 2.2  `life360_driver.groovy:579` (`historyClearData`) — undefined variable `logCharCount1`

**Problem:** throws `MissingPropertyException` when the user clicks "Clear History". Event names `numOfCharacters1` / `lastLogMessage1` also don't match what `sendHistory` writes (`numOfCharacters` / `lastLogMessage`), so even if it didn't throw it wouldn't clear the right attributes. The locally-scoped helpers `msgValue`, `logCharCount`, `historyLog` are also assigned without `def` (implicit globals).
**Fix:** drop the `1` suffix on the event names, use the locally-defined `logCharCount` / `msgValue` (declared with `def`), and use `0` as the count.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 2.3  `life360_app.groovy` (`scheduleUpdates`) — `pollFreq=0` (Disabled) silently schedules 1–4s polling

**Problem:** random jitter is added unconditionally, so "Disabled" becomes `0 + (0..4) = 1..4` seconds.
**Fix:** skip the jitter add when `settings.pollFreq.toInteger() == 0`.

```groovy
Integer refreshSecs = settings.pollFreq.toInteger()  // 0 = Disabled
refreshSecs += random                                  // becomes 0..4 — bug
if (refreshSecs > 0 ...) { schedule(...) }             // schedules ultra-fast polling
```

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — `scheduleUpdates()` now returns early when `baseSecs <= 0`, logging `polling DISABLED` and skipping both the jitter add and `schedule()`.

### 2.4  `life360_driver.groovy:547` (`sendHistory`) — undefined preference `fontSize`

**Problem:** HTML template references `fontSize`, which doesn't exist. The defined preference is `avatarFontSize`.
**Fix:** replace `fontSize` with `avatarFontSize` in the `<table style=...>` string.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 2.5  `life360_app.groovy:715` (`dynamicPolling`) — dynamic polling never exits when Life360 keeps a stale `inTransit` flag

**Problem:** `dynamicPolling()` decides whether to stay in fast-poll mode purely by reading `state["inTransit-<memberId>"]`, which is whatever Life360's API reported for that member. Life360 routinely keeps `inTransit=true` for many minutes after a member has actually stopped moving (or sometimes indefinitely if their phone hands off WiFi/cell oddly). When that happens for any one of N members, the app stays locked in dynamic-poll mode forever — e.g. polling every 20s instead of every 180s, ~9× the API call rate, ~9× the app-busy contribution.

Observed in the wild: one member showed `prevInTransit:true` continuously for the entire log window with zero matching "moved" lines (driver-side jitter filter correctly suppressing because the member is stationary). Dynamic polling held at 20s the whole time.

**Fix:** gate the in-transit flag on actual motion before letting it drive dynamic-polling mode. In the driver where `inTransit` is decided / returned (and saved into `state["inTransit-<memberId>"]` by the app), require **both** Life360's `inTransit` flag **and** either a non-trivial speed or a recent position change. Rough shape:

```groovy
// in the driver, before returning inTransit to the app:
double movedMeters = haversine(prevLat, prevLng, latitude, longitude) * 1000.0
double accuracyM   = Math.max((prevAccuracy ?: 0) as double, (accuracy ?: 0) as double)
boolean reallyMoving = speedUnits >= 1.0 || movedMeters > accuracyM
if (inTransit && !reallyMoving) {
    inTransit = false  // ignore stale Life360 in-transit flag
}
```

(Same idea could live in `dynamicPolling()` instead, by also reading per-member speed/lastMoved state — but the driver already has the prev-position context, so it's the cleaner home.) Also worth: if `inTransit` has been true for > 15 min and the member hasn't moved more than `accuracy`, force a reset.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — driver `generatePresenceEvent` now overrides `inTransit=true` to `false` when `speedUnits < 0.5` and movement since last update is within GPS accuracy. Logged as `<name>: ignoring stale Life360 inTransit flag (...)`.

### 2.6  `life360_app.groovy` (`scheduleUpdates` / `handleTimerFired`) — overlapping timer instances fire on top of each other

**Problem:** `scheduleUpdates()` is called from `updated()`, `initialize()`, and from inside `handleTimerFired()` itself (when switching between standard and dynamic poll rates). It calls `schedule(...)` without first `unschedule`-ing the existing job in all paths. After an `updated:` event, the previously-scheduled timer can still fire alongside the newly-scheduled one — observed in the wild as three `handleTimerFired` invocations within the same second (e.g. `12:36:00.044`, `12:36:00.149`, `12:36:00.383`) after an `updated:` at 12:35:21.

Each extra fire runs `fetchMembers()` → another HTTP round-trip to Life360 and another full member iteration. Combined with §2.5 (stuck dynamic polling), this multiplies hub load substantially: 3× the API calls per tick at 3× the tick rate = ~9× nominal.

**Fix:** in `scheduleUpdates()` (and anywhere else that calls `schedule(...)` for the polling job), always `unschedule("handleTimerFired")` first. Better: track the currently scheduled interval in state and short-circuit if the desired rate hasn't changed, so we don't churn the scheduler from inside `handleTimerFired` at all. (§8.8 covers the no-op-rebuild half; this entry adds the unschedule discipline.)

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — `scheduleUpdates()` now tracks `state.scheduledBaseSecs` and returns early when the desired base rate hasn't changed and the polling mode hasn't flipped. `installed()`/`updated()`/`initialize()` clear `state.scheduledBaseSecs` first to force a (re)arm on lifecycle events. Polling-mode flips are logged at `info` level.

### 2.7  `life360_app.groovy` (`dynamicPolling`) — mode-switch log shows pre-change state, reads as wrong

**Problem:** the `log.debug` calls in `dynamicPolling()` fired **before** `scheduleUpdates()`, so the logged variables still reflected the prior state. A standard→dynamic transition (or vice versa) showed `dynamicPollingActive: true` on the line announcing the switch to standard polling, which reads as "the code just set the flag wrong" when in fact the flag was about to be cleared by `scheduleUpdates()` a microsecond later. Audited the variable: `state.dynamicPollingActive` is written in exactly one place (`scheduleUpdates()` line ~626) and the logic is correct — only the log presentation was misleading.

**Fix:** move the `log.debug(...)` after `scheduleUpdates()` and rewrite as past tense (`switched X -> Y`) so the values reflect the new state.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

---

## 3. Bugs — Medium Priority

### 3.1  `life360_driver.groovy:283` — `descriptionText` operator precedence: always "arrived"

**Problem:** the ternary's "condition" is the entire concatenated string, which is always truthy.
**Fix:** parenthesize the ternary.

```groovy
// current — parsed as (displayName + " has " + boolean) ? "arrived" : "left"
String d = device.displayName + " has " + (memberPresence == "present") ? "arrived" : "left"
// fix
String d = device.displayName + " has " + ((memberPresence == "present") ? "arrived" : "left")
```

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 3.2  `life360_driver.groovy:405` — HTML tile `if` always truthy when address is "No Data"

**Problem:** the inner ternary returns the string `"Between Places"` (truthy), so the `if` always fires.
**Fix:** restructure the condition.

```groovy
// current
if (address1 == "No Data" ? "Between Places" : address1 != "Home" && inTransit) { ... }
// fix
if (address1 != "Home" && inTransit) { ... }
```

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 3.3  `life360_app.groovy:633` (`createChildDevices`) — `addChildDevice` hardcodes hub ID `1234`

**Problem:** anyone whose hub ID isn't `1234` gets the child device created on the wrong hub (or fails on multi-hub installs).
**Fix:** pass `null` (Hubitat picks) or `location.hubs[0].id`.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — passing `null` so Hubitat picks the correct hub.

### 3.4  `life360_app.groovy:627` (`createChildDevices`) — dead inner check never matches

**Problem:** `childList.find { it.data.vcId == "${member}" }` stringifies the whole member map and compares against `vcId` (which doesn't exist on these devices). The outer `if (!deviceWrapper)` already protects creation.
**Fix:** delete the inner `if (childList.find ...)` block.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 3.5  `life360_app.groovy` (`updated`) — orphan devices when a member is deselected

**Problem:** `updated()` calls `createChildDevices()` to add but never removes children for members deselected from `settings.users`. Deselected members leave dangling child devices showing stale state.
**Fix:** diff `settings.users` against `getChildDevices()*.deviceNetworkId` and call `deleteChildDevice` on the difference.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — `createChildDevices()` now also removes any child whose `deviceNetworkId` isn't `${app.id}.${memberId}` for some currently-selected member.

### 3.6  `life360_app.groovy` (`handleTimerFired`) — watchdog warning spams every tick

**Problem:** once `state.lastSuccessMs` is older than `max(pollFreq×10, 10min)`, `log.warn("WATCHDOG: ...")` fires on every poll (every 10s on 10s polling).
**Fix:** add a `state.watchdogWarned` flag, log only on the rising edge, clear on the next 200/304.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 3.7  `life360_driver.groovy:189` — `location.since.toLong()` NPE

**Problem:** if Life360 omits the `since` field (rare but seen), `.toLong()` throws and aborts `generatePresenceEvent` mid-update — no events get sent for that poll.
**Fix:** `location.since?.toLong() ?: 0L`

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs.

### 3.8  `life360_app.groovy` (`fetchLocations`) — timer keeps firing at full rate when token is known-expired

**Problem:** when `state.tokenLikelyExpired` is true, `fetchLocations()` early-returns but the timer still fires at the user's poll interval.
**Fix:** `unschedule()` (or back off to a slow 5-min check) when the token is bad; re-`schedule()` on `updated()` after a fresh token is pasted.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — when `handleException` flips `tokenLikelyExpired` to true for the first time, it calls `scheduleUpdates()`, which now overrides `baseSecs` to 300 (5 min) while the token is bad. `updated()` already clears the flag and re-arms the normal rate.

---

## 4. Performance

### 4.1  App (`fetchLocations`) — member HTTP fetches are serial

**Problem:** loops `settings.users` and calls blocking `httpGet` per member. With 5 members and 1–2s round-trips, the whole poll cycle holds the scheduler thread for 5–10s — visible as high app-busy.
**Fix:** switch to `asynchttpGet` with a dedicated response handler.
**Caveat:** parallel async requests can pile up when Life360 gets slow — see Section 7 for the in-flight-guard pattern that's required when going async.

**Status:** FIXED in branch: feature/async-member-fetch — `fetchMemberLocation` now fires `asynchttpGet`; `handleMemberLocationResponse` handles 200/304/401/403/429/5xx inline. In-flight guard (§7.1) prevents pile-up.

### 4.2  App (`notifyChildDevice`) — places map rebuilt per-member, per-poll

**Problem:** for each member on each tick, the app re-sorts `state.places` and rebuilds a `LinkedHashMap`, even though the places list rarely changes.
**Fix:** extract `buildPlacesContext()` returning `{placesMap, home}`, call once per poll cycle in `fetchLocations()`, thread the context through to `notifyChildDevice`.

**Status:** FIXED in branch: feature/async-member-fetch — `buildPlacesContext()` added; called once in `fetchLocations()` and threaded through `fetchMemberLocation` → `handleMemberLocationResponse` → `notifyChildDevice`.

### 4.3  App — `fetchMembers()` is synchronous

**Problem:** `fetchMembers()` used blocking `httpGet`, blocking the scheduler thread for the duration of the round-trip.
**Fix:** convert to `asynchttpGet`. (The periodic forced timer that originally spawned this finding was replaced by circles-based on-demand detection — see §4.7.)

**Status:** FIXED in branch: feature/async-member-fetch — `fetchMembers` now fires `asynchttpGet`; `handleMembersResponse` handles 200 and errors.

### 4.4  App (`createChildDevices`) — `getChildDevices()` called inside the `.each` loop

**Problem:** `getChildDevices()` walks the hub's full device list each call — O(N²) inside the loop.
**Fix:** hoist outside the `.each`. Wall-clock impact is small (N≈5, only runs on install/preferences save), but it's a real perf hit not a style nit.

**Status:** FIXED in branch: feature/async-member-fetch — `getChildDevices()` called once at top, result stored in `childMap`; both creation loop and orphan cleanup use the map.

### 4.5  Driver (`generatePresenceEvent`) — `savedPlaces` JSON sent on every member update

**Problem:** `new groovy.json.JsonBuilder(thePlaces).toString()` runs every poll for every member; the places list rarely changes. 5 members × 6 polls/min = 30 JSON serializations + event writes per minute for static data.
**Fix:** compare to `device.currentValue('savedPlaces')`; skip the event when unchanged.

**Status:** FIXED in branch: feature/async-member-fetch — serialize to string first, skip `sendEvent` if value matches `device.currentValue('savedPlaces')`.

### 4.6  Driver (`generatePresenceEvent`) — `memberName` / `avatar` re-sent every poll

**Problem:** Hubitat dedupes by value before persisting, but the function-call overhead is wasted.
**Fix:** gate behind "first run or value changed".

**Status:** FIXED in branch: feature/async-member-fetch — `memberName` and `avatar` both gated on `device.currentValue()` check before `sendEvent`.

### 4.7  App (`handleTimerFired`) — `fetchMembers()` fired on a fixed 5–10 min timer unrelated to actual membership changes

**Problem:** the "changing things up" block called `fetchMembers()` every 5–10 minutes regardless of whether anything changed. Timer interval was disconnected from the user's poll frequency and could not be configured.
**Fix:** poll `/circles` (tiny ~300-byte payload) with every standard poll tick, rate-limited to at most once per minute. If `memberCount` on the selected circle changes, fire `fetchMembers()` immediately. Remove the fixed timer entirely.

**Status:** FIXED in branch: feature/async-member-fetch — `handleCirclesPollResponse` compares `circle.memberCount` to `state.memberCount`; logs DEBUG on no-change, INFO on change, calls `fetchMembers()` only when needed. `updated()` clears `state.memberCount` / `state.lastCirclesFetchMs` to re-baseline after config saves.

---

## 5. Functional / UX

### 5.1  Distance-moved logging at `info`

**Problem:** the only sign-of-life log is the per-poll HTTP 200 trace, which is invisible at default log levels.
**Fix:** add a `"<member>: moved 0.37 mi @ 32.5 mph"` log when a member's position changes meaningfully.
**Caveat:** guard against GPS jitter — when not in transit / not driving and `speed < 0.5`, suppress moves smaller than `max(prevAccuracy, accuracy)` (typical indoor GPS is 50–200m of noise).

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — `generatePresenceEvent` logs moved distance + speed at `info` when `movedMeters > accuracyMeters && (inTransit || isDriving)`. Uses `displayMember()` for privacy. Google Maps link gated on `logShowMapsLink` toggle (§6.4).

### 5.2  Token-expiry notification fires only once

**Problem:** the `wasExpired` flag prevents repeat notifications. If the user misses the first one (phone silenced), they won't get another.
**Fix:** add a periodic reminder while still expired (e.g. once per hour).

**Status:** FIXED in branch: feature/async-member-fetch — two new settings in the Notifications section: **Enable Token Expiry Notifications** (bool master toggle, default ON) silences all alerts without removing configured devices; **Repeat Reminder Every** (enum: Never / 2h / 6h / 12h / 24h / 48h, default Never) schedules `sendTokenExpiryReminder` via `runIn`. The reminder re-checks `state.tokenLikelyExpired` and the master toggle before firing, then self-reschedules. `updated()` calls `unschedule("sendTokenExpiryReminder")` so pasting a fresh token stops the chain immediately.

### 5.3  No exponential backoff for transient errors

**Problem:** 502/503/504 errors just wait for the next normal poll tick.
**Fix:** progressive delay (1×, 2×, 4× the poll interval, cap at 5 min) reduces hammering on an already-struggling API.

**Status:** FIXED in branch: feature/async-member-fetch — `handleMemberLocationResponse` now tracks `state["transientCount-${memberId}"]` and `state["transientUntilMs-${memberId}"]`. On each 5xx the count increments and the backoff doubles (`pollSecs * 2^(count-1)`, capped at 300s). `fetchMemberLocation` skips firing if still in the backoff window. Both cleared on 200/304. `clearSessionCache()` drops `transientCount-*` and `transientUntilMs-*` alongside `etag-*` and `inflight-*`.

### 5.4  Capability repurposing is non-obvious

**Problem:** standard capabilities are repurposed for Life360 data, which confuses Rule Machine authors.

| Standard capability | What it actually means here |
| --- | --- |
| `Switch` on/off | WiFi connected / not |
| `Contact Sensor` open/closed | Charging / on battery |
| `Acceleration Sensor` active/inactive | In transit OR driving OR away from home |

**Fix:** document prominently in the README and add inline comments in the driver. Also captured in [STATE_REFERENCE.md](STATE_REFERENCE.md).

### 5.5  Settings page layout defects  **(FIXED)**

**Problem:** several UX defects on `mainPage()` made the app harder to use than it should be:
- "Fetch Locations" — the post-setup connectivity check — was buried inside a catch-all "Other Options" section, far below STEP 4, with no indication it was the verification step.
- The "Other Options" header had grown stale: it now contained polling + notifications + everything else.
- Typo: "Dynamic Refesh Rate" in the polling description.
- Google Maps API key help text was passed via `description:`, which Hubitat renders as placeholder text *inside* the input — the long security warning was clipped on most screen widths.
- All inputs rendered full-row width, including enums for "10 seconds" / "1 minute" — short values looked comically wide and the page felt visually inconsistent.

**Fix:**
- Promote Fetch Locations to its own **STEP 5: Verify Connectivity** section with a "✓ Last successful fetch: <age>" indicator (uses existing `state.lastSuccessMs`).
- Split "Other Options" into focused **Polling**, **Notifications**, **Logging**, **Map View** sections.
- Fix "Refesh" → "Refresh".
- Move Maps API key help out of `description:` and render as a `paragraph` below the field with bolded red `Security:` callout.
- Standardize all inputs to `width: 6` (half-row) for consistent left/right edges across the page.

---

## 6. Security / Privacy

### 6.1  App (`/view` endpoint) — `state.accessToken` exposed in the URL

**Problem:** `${getFullLocalApiServerUrl()}/view?access_token=...` is shown on the main config page; anyone with LAN access plus that URL can view all members' live coordinates.
**Fix:** add a paragraph in the README warning the user not to share screenshots and to revoke the OAuth token (via the source-code editor) if compromised.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — added a *Privacy & security notes* section to the README and an inline `<small>` red-text warning under the Map View URL in the app config page. No code change to the endpoint itself (token in URL is how Hubitat OAuth works).

### 6.2  Driver / app — Google Maps API key visible in rendered HTML

**Problem:** `buildGoogleMapHtml` embeds the raw key in page source.
**Fix:** add a one-liner in the `googleMapsApiKey` setting description telling the user to restrict the key with HTTP referrer limits in Google Cloud Console.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — extended the `googleMapsApiKey` setting description with a *Security:* note and added the same guidance to the README *Privacy & security notes* section. The key still appears in HTML (unavoidable for the Maps JS API); the mitigation is referrer + API restrictions in Google Cloud Console.

### 6.3  App / driver — member first names and Life360 place names leak into logs

**Problem:** `fetchCircles`, `fetchPlaces`, `fetchMembers`, `fetchMemberLocation`, `notifyChildDevice`, `createChildDevices` (app) and the stale-inTransit / "moved" info logs (driver) all emit member first names and place labels at `info`/`debug` level. For users who share their hub logs for debugging — or whose logs are captured by a remote logging integration — this leaks household members' identities and the names of private places ("Mom's House", "Work", etc.).
**Fix:** add an app-level `logShowNames` preference (default ON to preserve current behavior) and gate every name/place log line on it. When OFF, log opaque IDs (memberId / circleId / placeId) instead. Driver reads the parent setting via `parent?.getShowNamesInLogs()`.

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — new `Logging` section in app preferences adds **Include Names and Places in Logs** (default ON). Driver helper `displayMember(firstName)` falls back to the memberId parsed from `device.deviceNetworkId` when the toggle is OFF.

### 6.4  Driver — exact coordinates and Google Maps URL emitted at `info`

**Problem:** the "moved" info log (added in §5.1) appends `https://www.google.com/maps/search/?api=1&query=<lat>,<lng>` with the member's live coordinates. Anyone who can read the logs gets a one-click satellite view of where each tracked person was, every time they move. Same disclosure concern as 6.3 but with precise location instead of identity.
**Fix:** add an app-level `logShowMapsLink` preference (default ON). When OFF, the driver's "moved" log omits the Maps URL (distance + speed still logged so users can see polling is working).

**Status:** FIXED in branch: fix/life360-bugs-cleanup-docs — **Include Google Maps Link in Logs** toggle added alongside §6.3. Driver checks `parent?.getShowMapsLink()` before appending the URL.

### 6.5  App / driver — real member names flow through the entire system; privacy is log-only

**Problem:** member first/last names from the Life360 API are stored in `state.members`, passed as function arguments (`memberObj.firstName`, `notifyChildDevice`, `generatePresenceEvent`), and written to device attributes throughout the app and driver. The current privacy controls (§6.3, `logShowNames`) only gate what appears in *log output* — real names exist everywhere else. A privacy-first design would pass member UUIDs internally and resolve to human-readable names only at the last moment (display/logging), so that names are never incidentally exposed through state dumps, debug output, or future code paths that don't know to call `displayMember()`.

**Fix:** refactor internal APIs to pass `memberId` (UUID) rather than name strings. Look up the display name from `state.members` only at the point of user-visible output (logs, device labels, UI). Device attributes that are user-facing (e.g. `memberName`) are intentional and remain as-is.

---

## 7. Async HTTP pile-up — forward-looking guidance

Not a bug in current master (the fork still uses synchronous `fetchLocations`), but flagged for **whoever does the sync-to-async port** described in 4.1. Once per-member fetches go async, a slow Life360 response lets the next poll tick fire a second batch on top of the still-pending one. With 5 members and 10s polling, in-flight requests can reach 9–16 within a minute. Hubitat warns at 9+ pending.

### 7.1  Mitigation pattern

1. Per-member in-flight marker `state["inflight-${memberId}"] = startMs` set immediately before `asynchttpGet`, cleared as the first line of the response handler.
2. `fetchMemberLocation` skips firing a new request for a member whose marker is younger than `(httpTimeout + 2s)`.
3. Adapt HTTP `timeout` to the active poll interval — `clamp(activePollSecs, 5, 30)` — so a stuck request times out before the next tick, but a fast-polling install (5–10s) doesn't pay 30s of latency on every failure.
4. `clearSessionCache()` should drop `inflight-*` keys alongside `etag-*` and `cookies`, so a fresh token-paste isn't blocked by stale markers.

**Status:** FIXED in branch: feature/async-member-fetch — all four points implemented alongside §4.1/§4.2.

---

## 8. Code Quality / Minor (one-liners)

| ID | File | Issue |
| --- | --- | --- |
| 8.1 | App | ~~`log.debug("fetchPlaces:")` not guarded by `if (logEnable)` — inconsistent with rest of app~~ — FIXED (gated, and given context) |
| 8.2 | App | ~~`handleTimerFired` creates two `new Date()` objects; capture once as `long now`~~ — FIXED |
| 8.3 | App | ~~`lastAttempt` is in seconds but the message says `"last:${lastAttempt}ms"`~~ — FIXED (unit changed to `s`) |
| 8.4 | App | ~~`state.memberInTransit = false` set at top of `dynamicPolling()` then redundantly set again in one branch~~ — FIXED |
| 8.5 | App | ~~`placesMap`, `sortedPlaces`, `sortedMembers`, `thePlaces`, `theMembers` used without `def` — implicit Groovy globals~~ — FIXED |
| 8.6 | App | ~~`new Random()` instantiated on every `scheduleUpdates()` / `handleTimerFired`~~ — FIXED with `@Field static final Random RNG = new Random()` |
| 8.7 | App | ~~`captureCookiesAsync` only saves the first `Set-Cookie` value; sync `captureCookies` joins multiples with `;`~~ — N/A (the fork no longer has `captureCookiesAsync`; only the sync path exists) |
| 8.8 | App | ~~`scheduleUpdates()` re-armed from inside `handleTimerFired` every 5–10 min — check whether rate actually changed before re-scheduling~~ — FIXED with §2.6 |
| 8.9 | Driver | ~~`strToDate()` defined but never called — dead code~~ — FIXED (removed) |
| 8.10 | Driver | ~~`state.presence`, `state.status`, `state.update` set but never read — wasted state~~ — FIXED (removed) |
| 8.11 | Driver | ~~`attribute "battery", "number"` duplicates what `capability "Battery"` already provides~~ — FIXED (removed) |
| 8.12 | Driver | ~~`haversine()` is pure math — should be `static`~~ — FIXED |
| 8.13 | Driver | ~~HTML `int sEpoch = device.currentValue('since')` — overflows signed int in 2038 (Y2K38); use `long`~~ — FIXED |

---

## 9. Ideas — Undocumented API Capabilities

Discovered from the unofficial Life360 API spec at `krconv.github.io/life360-api-docs` (OpenAPI YAML in `github.com/krconv/life360-api-docs`). These are not bugs — they are API capabilities that the Life360 mobile app uses but this Hubitat integration does not yet.

### 9.1  Force a member's phone to report a fresh GPS fix

**Endpoint:** `POST /circles/{circle}/members/{member}/request`
**Response:** `{ "requestId": "uuid", "isPollable": "1" }`
**Poll result:** `GET /circles/members/request/{requestId}` → returns `{ requestId, groupId, location }` where `location` is the full location object (same fields as a normal member fetch).

Life360's server pushes a location-update request to the member's phone. If `isPollable == "1"` the result can be polled; otherwise the update happens asynchronously and the next normal fetch will see the fresh data.

**Why this matters:** Directly addresses the stuck-`inTransit` bug (§2.5). Rather than waiting for Life360 to stop returning stale 304s, we can force a fresh 200. Also enables:
- A **"Force Update"** button per member in the Hubitat device UI (driver command)
- Auto-trigger in the app: if `inTransit` has been true for > N minutes with no position change, fire a force-update POST for that member

**Implementation sketch:**
1. App: `asyncHttpPost("handleForceUpdateResponse", life360Params("/circles/${circleId}/members/${memberId}/request"))`
2. Handler reads `requestId` and `isPollable`; if pollable, `runIn(5, "pollForceUpdate")` with `requestId` in state
3. Poll handler: `asynchttpGet` to `/circles/members/request/${requestId}`, process returned `location` through normal `notifyChildDevice` path
4. Driver: add `command "refreshLocation"` which calls `parent.forceMemberUpdate(device.deviceNetworkId)`

### 9.2  Get all member locations in one call instead of N per-member calls

**Endpoint:** `GET /circles/{circle}` (the single-circle detail endpoint, not `/circles`)
**Response:** full `CircleInformation` object (id, name, memberCount, etc.) **plus** an embedded `members[]` array, each with the full `location` object.

This means one HTTP request can replace all N per-member fetches. For a 5-member circle: 5 round-trips → 1. The payload is larger but the API call count drops 80%.

**Tradeoff vs current approach:**
- **Lose:** per-member eTags — currently each member gets `If-None-Match` so unchanged members return 304 at near-zero cost. With a single circle call there is one eTag for the whole response; if any one member moves, all members are re-processed.
- **Lose:** per-member in-flight guard and per-member exponential backoff — these become moot when it's one call.
- **Gain:** atomic snapshot of all members at the same instant; dramatically simpler poll loop; far fewer open HTTP connections on the hub.
- **Gain:** `memberCount` comes back in the same response, so the separate circles poll (§4.7) could be folded in too.

This is a larger architectural refactor and should be treated as a separate branch/effort. Worth evaluating whether Life360's Cloudflare layer returns 304 on the circle endpoint when nothing changed — if yes, this is essentially free when the circle is quiet.

### 9.3  Cheap token validation via `GET /users/me`

**Endpoint:** `GET /users/me`
**Response:** authenticated user's profile (id, firstName, lastName, loginEmail, avatar, settings, etc.)

Tiny call. Currently the app only discovers a bad token when a member fetch 401s (after potentially 3 failures). `GET /users/me` on startup or immediately after a token is pasted in `updated()` would give instant confirmation or a clear error — before the first poll tick fires.

**Implementation:** fire async `GET /users/me` from `updated()` after a token change; on 200 log `"token valid: ${firstName} ${lastName}"`; on 401/403 immediately flip `state.tokenLikelyExpired` and notify.
