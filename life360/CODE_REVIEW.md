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

### 2.2  `life360_driver.groovy:579` (`historyClearData`) — undefined variable `logCharCount1`

**Problem:** throws `MissingPropertyException` when the user clicks "Clear History". Event names `numOfCharacters1` / `lastLogMessage1` also don't match what `sendHistory` writes (`numOfCharacters` / `lastLogMessage`), so even if it didn't throw it wouldn't clear the right attributes. The locally-scoped helpers `msgValue`, `logCharCount`, `historyLog` are also assigned without `def` (implicit globals).
**Fix:** drop the `1` suffix on the event names, use the locally-defined `logCharCount` / `msgValue` (declared with `def`), and use `0` as the count.

### 2.3  `life360_app.groovy` (`scheduleUpdates`) — `pollFreq=0` (Disabled) silently schedules 1–4s polling

**Problem:** random jitter is added unconditionally, so "Disabled" becomes `0 + (0..4) = 1..4` seconds.
**Fix:** skip the jitter add when `settings.pollFreq.toInteger() == 0`.

```groovy
Integer refreshSecs = settings.pollFreq.toInteger()  // 0 = Disabled
refreshSecs += random                                  // becomes 0..4 — bug
if (refreshSecs > 0 ...) { schedule(...) }             // schedules ultra-fast polling
```

### 2.4  `life360_driver.groovy:547` (`sendHistory`) — undefined preference `fontSize`

**Problem:** HTML template references `fontSize`, which doesn't exist. The defined preference is `avatarFontSize`.
**Fix:** replace `fontSize` with `avatarFontSize` in the `<table style=...>` string.

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

### 3.2  `life360_driver.groovy:405` — HTML tile `if` always truthy when address is "No Data"

**Problem:** the inner ternary returns the string `"Between Places"` (truthy), so the `if` always fires.
**Fix:** restructure the condition.

```groovy
// current
if (address1 == "No Data" ? "Between Places" : address1 != "Home" && inTransit) { ... }
// fix
if (address1 != "Home" && inTransit) { ... }
```

### 3.3  `life360_app.groovy:633` (`createChildDevices`) — `addChildDevice` hardcodes hub ID `1234`

**Problem:** anyone whose hub ID isn't `1234` gets the child device created on the wrong hub (or fails on multi-hub installs).
**Fix:** pass `null` (Hubitat picks) or `location.hubs[0].id`.

### 3.4  `life360_app.groovy:627` (`createChildDevices`) — dead inner check never matches

**Problem:** `childList.find { it.data.vcId == "${member}" }` stringifies the whole member map and compares against `vcId` (which doesn't exist on these devices). The outer `if (!deviceWrapper)` already protects creation.
**Fix:** delete the inner `if (childList.find ...)` block.

### 3.5  `life360_app.groovy` (`updated`) — orphan devices when a member is deselected

**Problem:** `updated()` calls `createChildDevices()` to add but never removes children for members deselected from `settings.users`. Deselected members leave dangling child devices showing stale state.
**Fix:** diff `settings.users` against `getChildDevices()*.deviceNetworkId` and call `deleteChildDevice` on the difference.

### 3.6  `life360_app.groovy` (`handleTimerFired`) — watchdog warning spams every tick

**Problem:** once `state.lastSuccessMs` is older than `max(pollFreq×10, 10min)`, `log.warn("WATCHDOG: ...")` fires on every poll (every 10s on 10s polling).
**Fix:** add a `state.watchdogWarned` flag, log only on the rising edge, clear on the next 200/304.

### 3.7  `life360_driver.groovy:189` — `location.since.toLong()` NPE

**Problem:** if Life360 omits the `since` field (rare but seen), `.toLong()` throws and aborts `generatePresenceEvent` mid-update — no events get sent for that poll.
**Fix:** `location.since?.toLong() ?: 0L`

### 3.8  `life360_app.groovy` (`fetchLocations`) — timer keeps firing at full rate when token is known-expired

**Problem:** when `state.tokenLikelyExpired` is true, `fetchLocations()` early-returns but the timer still fires at the user's poll interval.
**Fix:** `unschedule()` (or back off to a slow 5-min check) when the token is bad; re-`schedule()` on `updated()` after a fresh token is pasted.

---

## 4. Performance

### 4.1  App (`fetchLocations`) — member HTTP fetches are serial

**Problem:** loops `settings.users` and calls blocking `httpGet` per member. With 5 members and 1–2s round-trips, the whole poll cycle holds the scheduler thread for 5–10s — visible as high app-busy.
**Fix:** switch to `asynchttpGet` with a dedicated response handler.
**Caveat:** parallel async requests can pile up when Life360 gets slow — see Section 7 for the in-flight-guard pattern that's required when going async.

### 4.2  App (`notifyChildDevice`) — places map rebuilt per-member, per-poll

**Problem:** for each member on each tick, the app re-sorts `state.places` and rebuilds a `LinkedHashMap`, even though the places list rarely changes.
**Fix:** extract `buildPlacesContext()` returning `{placesMap, home}`, call once per poll cycle in `fetchLocations()`, thread the context through to `notifyChildDevice`.

### 4.3  App (`handleTimerFired`) — `fetchMembers()` is synchronous

**Problem:** every 5–10 min the timer fires `fetchMembers()` using blocking `httpGet`. Blocks the scheduler thread for the duration.
**Fix:** convert to `asynchttpGet` (same pattern as per-member location fetches).

### 4.4  App (`createChildDevices`) — `getChildDevices()` called inside the `.each` loop

**Problem:** `getChildDevices()` walks the hub's full device list each call — O(N²) inside the loop.
**Fix:** hoist outside the `.each`. Wall-clock impact is small (N≈5, only runs on install/preferences save), but it's a real perf hit not a style nit.

### 4.5  Driver (`generatePresenceEvent`) — `savedPlaces` JSON sent on every member update

**Problem:** `new groovy.json.JsonBuilder(thePlaces).toString()` runs every poll for every member; the places list rarely changes. 5 members × 6 polls/min = 30 JSON serializations + event writes per minute for static data.
**Fix:** compare to `device.currentValue('savedPlaces')`; skip the event when unchanged.

### 4.6  Driver (`generatePresenceEvent`) — `memberName` / `avatar` re-sent every poll

**Problem:** Hubitat dedupes by value before persisting, but the function-call overhead is wasted.
**Fix:** gate behind "first run or value changed".

### 4.7  Driver — `generatePresenceEvent` is ~230 lines

**Problem:** single monolithic function. Hard to read, hard to test, hard to change safely.
**Fix:** split into `updateLocation()`, `updateBattery()`, `updateTransitState()`, `buildHtmlTile()`.

---

## 5. Functional / UX

### 5.1  Distance-moved logging at `info`

**Problem:** the only sign-of-life log is the per-poll HTTP 200 trace, which is invisible at default log levels.
**Fix:** add a `"<member>: moved 0.37 mi @ 32.5 mph"` log when a member's position changes meaningfully.
**Caveat:** guard against GPS jitter — when not in transit / not driving and `speed < 0.5`, suppress moves smaller than `max(prevAccuracy, accuracy)` (typical indoor GPS is 50–200m of noise).

### 5.2  Token-expiry notification fires only once

**Problem:** the `wasExpired` flag prevents repeat notifications. If the user misses the first one (phone silenced), they won't get another.
**Fix:** add a periodic reminder while still expired (e.g. once per hour).

### 5.3  No exponential backoff for transient errors

**Problem:** 502/503/504 errors just wait for the next normal poll tick.
**Fix:** progressive delay (1×, 2×, 4× the poll interval, cap at 5 min) reduces hammering on an already-struggling API.

### 5.4  Capability repurposing is non-obvious

**Problem:** standard capabilities are repurposed for Life360 data, which confuses Rule Machine authors.

| Standard capability | What it actually means here |
| --- | --- |
| `Switch` on/off | WiFi connected / not |
| `Contact Sensor` open/closed | Charging / on battery |
| `Acceleration Sensor` active/inactive | In transit OR driving OR away from home |

**Fix:** document prominently in the README and add inline comments in the driver. Also captured in [STATE_REFERENCE.md](STATE_REFERENCE.md).

---

## 6. Security / Privacy

### 6.1  App (`/view` endpoint) — `state.accessToken` exposed in the URL

**Problem:** `${getFullLocalApiServerUrl()}/view?access_token=...` is shown on the main config page; anyone with LAN access plus that URL can view all members' live coordinates.
**Fix:** add a paragraph in the README warning the user not to share screenshots and to revoke the OAuth token (via the source-code editor) if compromised.

### 6.2  Driver / app — Google Maps API key visible in rendered HTML

**Problem:** `buildGoogleMapHtml` embeds the raw key in page source.
**Fix:** add a one-liner in the `googleMapsApiKey` setting description telling the user to restrict the key with HTTP referrer limits in Google Cloud Console.

---

## 7. Async HTTP pile-up — forward-looking guidance

Not a bug in current master (the fork still uses synchronous `fetchLocations`), but flagged for **whoever does the sync-to-async port** described in 4.1. Once per-member fetches go async, a slow Life360 response lets the next poll tick fire a second batch on top of the still-pending one. With 5 members and 10s polling, in-flight requests can reach 9–16 within a minute. Hubitat warns at 9+ pending.

### 7.1  Mitigation pattern

1. Per-member in-flight marker `state["inflight-${memberId}"] = startMs` set immediately before `asynchttpGet`, cleared as the first line of the response handler.
2. `fetchMemberLocation` skips firing a new request for a member whose marker is younger than `(httpTimeout + 2s)`.
3. Adapt HTTP `timeout` to the active poll interval — `clamp(activePollSecs, 5, 30)` — so a stuck request times out before the next tick, but a fast-polling install (5–10s) doesn't pay 30s of latency on every failure.
4. `clearSessionCache()` should drop `inflight-*` keys alongside `etag-*` and `cookies`, so a fresh token-paste isn't blocked by stale markers.

---

## 8. Code Quality / Minor (one-liners)

| ID | File | Issue |
| --- | --- | --- |
| 8.1 | App | `log.debug("fetchPlaces:")` not guarded by `if (logEnable)` — inconsistent with rest of app |
| 8.2 | App | `handleTimerFired` creates two `new Date()` objects; capture once as `long now` |
| 8.3 | App | `lastAttempt` is in seconds but the message says `"last:${lastAttempt}ms"` |
| 8.4 | App | `state.memberInTransit = false` set at top of `dynamicPolling()` then redundantly set again in one branch |
| 8.5 | App | `placesMap`, `sortedPlaces`, `sortedMembers`, `thePlaces`, `theMembers` used without `def` — implicit Groovy globals |
| 8.6 | App | `new Random()` instantiated on every `scheduleUpdates()` / `handleTimerFired` — use `@Field static final Random RNG = new Random()` |
| 8.7 | App | `captureCookiesAsync` only saves the first `Set-Cookie` value; sync `captureCookies` joins multiples with `;` — inconsistency, not biting today |
| 8.8 | App | `scheduleUpdates()` re-armed from inside `handleTimerFired` every 5–10 min — check whether rate actually changed before re-scheduling |
| 8.9 | Driver | `strToDate()` defined but never called — dead code |
| 8.10 | Driver | `state.presence`, `state.status`, `state.update` set but never read — wasted state |
| 8.11 | Driver | `attribute "battery", "number"` duplicates what `capability "Battery"` already provides |
| 8.12 | Driver | `haversine()` is pure math — should be `static` |
| 8.13 | Driver | HTML `int sEpoch = device.currentValue('since')` — overflows signed int in 2038 (Y2K38); use `long` |
