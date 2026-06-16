# Life360+ App & Driver — State Reference

Quick-reference map of every setting, state variable, scheduled job, subscription, and child-device attribute used by the Life360+ app (`life360_app.groovy`) and driver (`life360_driver.groovy`). Useful when triaging a misbehaving install or grepping the Hubitat "App Status" / "Device Details" pages.

Sourced from the Hubitat Apps inspector + the source files. Live values shown here are illustrative.

---

## App: Settings (user inputs)

Configured via the app's preferences page. Read at runtime as `settings.<name>` or just `<name>`.

| Setting | Type | Purpose | Example |
| --- | --- | --- | --- |
| `access_token` | text | Life360 bearer token (paste from `life360.com` → DevTools → Network → token packet) | `OTJj...8k` |
| `circle` | enum | Selected Life360 circle ID | `<uuid>` |
| `place` | enum | Life360 place ID that represents HOME for this Hubitat install | `<uuid>` |
| `users` | enum (multi) | Life360 member IDs to create child devices for | `["<uuid>","<uuid>",…]` |
| `pollFreq` | enum | Default poll interval (seconds): `10`/`15`/`30`/`60`/`180`/`300`/`0`=Disabled | `10` |
| `dynamicPolling` | bool | If true, poll faster while any member is in transit. Only engages when `pollFreq > dynamicPollFreq` | `true` |
| `dynamicPollFreq` | enum | Poll interval while a member is in transit: `5`/`10`/`20`/`30` | `20` |
| `notifyTokenExpiry` | bool | Master switch for token-expiry alerts (default on). When off, the device picker + repeat-reminder inputs are hidden and no alerts fire | `true` |
| `notifyDevices` | capability.notification | Devices notified on access-token failure (shown only when `notifyTokenExpiry` is on) | `[<notification device>]` |
| `notifyRepeatHours` | enum | Repeat the token-expiry reminder every `never`/`2`/`6`/`12`/`24`/`48` h (shown only when at least one notify device is set) | `never` |
| `googleMapsApiKey` | text (optional) | If set, `/view` endpoint uses Google Maps; else OpenStreetMap | empty |
| `logEnable` | bool | Verbose debug logging | `false` |
| `logRawPayload` | bool | Verbose raw-API diagnostics — logs sensitive data (GPS, partial token, cookie heads, full payloads). Debug only | `false` |
| `logShowNames` | bool | Include member/place/circle names in logs (default on). Off → UUIDs only, for safe log sharing | `true` |
| `logShowMapsLink` | bool | Include a Google Maps link in the "member moved" info log (default on) | `true` |
| `forceUpdateMember` | enum (transient) | Member targeted by the Force Update button. Fire-once — cleared (`app.removeSetting`) immediately after the request is sent, so the dropdown resets to blank | (blank) |

---

## App: State variables

Persisted via `state.<name>`. Reset on Hubitat reboot only if explicitly cleared (most survive restart).

### Selection / discovery cache

| Variable | Type | Set by | Notes |
| --- | --- | --- | --- |
| `circles` | List | `fetchCircles()` | Raw response from `/v3/circles` for the circle picker |
| `places` | List | `fetchPlaces()` | Raw response from `/v3/circles/<id>/places.json` for the HOME picker |
| `members` | List | `fetchMembers()` | Raw response from `/v3/circles/<id>/members` for the user picker |
| `accessToken` | String | `createAccessToken()` (OAuth) | Token used for the `/view` map endpoint URL |

### Session / HTTP plumbing

| Variable | Type | Set by | Cleared by | Notes |
| --- | --- | --- | --- | --- |
| `cookies` | String | `captureCookies` / `captureCookiesAsync` (via `mergeCookie`) | `clearSessionCache()` (auth errors) | `Cookie` header value (`__cf_bm` + `_cfuvid`) sent on subsequent requests. Merged by name so a rotating `__cf_bm` never drops `_cfuvid` |
| `deviceId` | String (UUID) | `getHttpHeaders()` first call | never | Stable per-install identifier sent to Life360 |
| `etag-<memberId>` | String | `handleMemberLocationResponse` on 200 OK | `clearSessionCache()` | Per-member `If-None-Match` value for 304-not-modified responses |
| `inflight-<memberId>` | Long (epoch ms) | `fetchMemberLocation` before `asynchttpGet` | `handleMemberLocationResponse` / `clearSessionCache()` | In-flight marker; prevents async-HTTP pile-up |
| `transientCount-<memberId>` | Integer | `handleMemberLocationResponse` on 5xx (502/503/504/520) | 200/304 success, `clearSessionCache()` | Consecutive-transient-error streak; drives exponential backoff |
| `transientUntilMs-<memberId>` | Long (epoch ms) | `handleMemberLocationResponse` on 5xx | 200/304 success, `clearSessionCache()` | Skip this member's fetch until this time (capped 300s backoff) |

### Polling / scheduling

| Variable | Type | Set by | Notes |
| --- | --- | --- | --- |
| `lastUpdateMs` | Long | `fetchLocations()` | Throttle: rejects calls < 5s apart |
| `lastSuccessMs` | Long | `handleMemberLocationResponse` on 200/304 | Watchdog input; triggers warn if stale > `max(pollFreq×10, 10min)` |
| `scheduledBaseSecs` | Integer | `scheduleUpdates()` | Last base interval armed (no jitter). No-op guard: skips re-arming the cron when the rate hasn't changed. Reset to `null` by `installed`/`updated`/`initialize` to force a re-arm |
| `pollIntervalSecs` | Integer | `scheduleUpdates()` | Current effective poll interval; used for the per-request HTTP timeout and 5xx backoff base |
| `dynamicPollingActive` | Boolean | `scheduleUpdates()` | True while polling at `dynamicPollFreq` (a member is in transit) |
| `memberInTransit` | Boolean | `dynamicPolling()` | True if ANY tracked member's `inTransit-<id>` is true |
| `inTransit-<memberId>` | Boolean | `notifyChildDevice` (echo from driver) | Per-member in-transit flag; drives `dynamicPolling`. On a flip, `notifyChildDevice` re-evaluates the poll rate immediately |

### Membership / discovery polling

| Variable | Type | Set by | Notes |
| --- | --- | --- | --- |
| `memberCount` | Integer | `handleCirclesPollResponse` | Baseline circle member count; a change vs the circles poll triggers `fetchMembers()`. Reset to `null` by `updated()` to re-baseline |
| `lastCirclesFetchMs` | Long | `handleTimerFired` | Throttles the `/circles` membership poll to at most once per minute |

### UI one-shot status (button results)

Each `*Status` holds an HTML result string; the paired `*StatusPending` flag survives the single page re-render after the button press, then `mainPage()` clears the status on the next open.

| Variable | Type | Set by | Notes |
| --- | --- | --- | --- |
| `tokenStatus` / `tokenStatusPending` | String / Boolean | `checkToken()` (Check Token button) | Token-validity result shown under STEP 1 |
| `forceUpdateStatus` / `forceUpdateStatusPending` | String / Boolean | `forceMemberUpdate()` / `handleForceUpdateResponse` (Force Update button) | Force-update result shown in the Force Update section |
| `accessToken` | String | `createAccessToken()` (OAuth) | Token embedded in the `/view` map endpoint URL |

### Error / health

| Variable | Type | Set by | Cleared by | Notes |
| --- | --- | --- | --- | --- |
| `failCount` | Integer | `handleException` / `handleMemberLocationResponse` on 401/403 | 200-OK response, `updated()` | Auth-failure streak; triggers `tokenLikelyExpired` at ≥3 |
| `tokenLikelyExpired` | Boolean | `handleException` / `handleMemberLocationResponse` on 401/403 ×3 | 200-OK response, `updated()` | When true, polling is short-circuited and slowed to 5 min; user-facing banner shown |
| `rateLimitedUntilMs` | Long | `handleException` / `handleMemberLocationResponse` on 429 | 200-OK response, `updated()` | Honors `Retry-After`; polling skipped until this time |
| `watchdogWarned` | Boolean | `handleTimerFired` watchdog (rising edge) | 200/304 success | Prevents the "no successful update" warning from spamming every tick |
| `message` | String | various error paths | success / `updated()` | Red banner shown on the app's main page |

---

## App: Scheduled jobs

| Handler | Schedule | Set by | Purpose |
| --- | --- | --- | --- |
| `handleTimerFired` | `0/<refreshSecs> * * * * ? *` (sub-minute) or `0 */<min> * * * ? *` | `scheduleUpdates()` | Each tick: run the stale-data watchdog, poll `/circles` at most once per minute to detect membership changes (via the `memberCount` diff → `fetchMembers()`), then call `fetchLocations()` |
| `sendTokenExpiryReminder` | one-shot `runIn(<notifyRepeatHours>h)`, self-rescheduling | `notifyTokenExpired()` / `scheduleTokenExpiryReminder()` | Repeats the token-expiry notification while `tokenLikelyExpired` stays true; chain stops once the token is refreshed or `notifyRepeatHours = never` |

`refreshSecs` = `pollFreq` (or `dynamicPollFreq` when dynamic polling is active) + 0–4s random jitter. The fixed periodic `fetchMembers` timer was removed — membership is now detected via the once-per-minute `/circles` poll-count diff in `handleTimerFired`.

---

## App: Subscriptions

| Source | Event | Handler |
| --- | --- | --- |
| `location` (Hubitat) | `systemStart` | `initialize` (re-schedules polling on hub reboot) |

---

## App: HTTP endpoints (mappings)

| Path | Method | Handler | Notes |
| --- | --- | --- | --- |
| `/view` | GET | `renderView` | Renders all selected members on an OSM (or Google) map. Requires OAuth enabled on the app and `?access_token=<state.accessToken>`. |

---

## Driver: Settings (per child device)

| Setting | Type | Default | Purpose |
| --- | --- | --- | --- |
| `isMiles` | bool | true | Distance/speed units: miles+mph (true) or km+kph (false) |
| `generateHtml` | bool | false | Build `html` / `avatarHtml` tile attributes |
| `transitThreshold` | number | 0 | Override Life360's `inTransit` — flip true at this speed (in display units); `0` = use Life360 |
| `drivingThreshold` | number | 0 | Override Life360's `isDriving` — flip true at this speed; `0` = use Life360 |
| `avatarFontSize` | number | 15 | Font size in the HTML tile |
| `avatarSize` | number | 75 | Avatar size (%) in the HTML tile |
| `saveHistory` | bool | false | Append each new lat/lng to `state.locationHistory` (capped at 100) and write the compact `history` attribute |
| `logEnable` | bool | false | Verbose debug logging |

---

## Driver: Attributes (per child device)

Set via `sendEvent` from `generatePresenceEvent`. Read via `device.currentValue("<name>")` or in Rule Machine.

### Capabilities (standard Hubitat)

| Capability | Attribute | Values | Meaning |
| --- | --- | --- | --- |
| Presence Sensor | `presence` | `present` / `not present` | Inside HOME radius |
| Battery | `battery` | 0–100 | Phone battery % |
| Power Source | `powerSource` | `DC` / `BTRY` | Charging source |
| Switch | `switch` | `on` / `off` | **Repurposed:** WiFi connected (on) or not (off) |
| Contact Sensor | `contact` | `open` / `closed` | **Repurposed:** charging (open) or on battery (closed) |
| Acceleration Sensor | `acceleration` | `active` / `inactive` | **Repurposed:** in transit OR driving OR not at home |

### Location

| Attribute | Type | Meaning |
| --- | --- | --- |
| `latitude` | number | Current latitude |
| `longitude` | number | Current longitude |
| `accuracy` | number | GPS uncertainty radius in meters (lower = better fix) |
| `address1` | string | Current place/address (`"Home"` when inside HOME radius; `"No Data"` if missing) |
| `address1prev` | string | Previous value of `address1` when it changes |
| `lastLocationUpdate` | date | When `address1` last changed (NOT every poll). Emitted via `sendEvent` but not declared in the driver's `attribute` block |
| `lastUpdated` | date | When this driver event batch last ran (every poll that reaches the driver) |
| `since` | number | Epoch seconds — Life360's "at current address since" timestamp |
| `status` | string | `"At Home"` or `"<x.x> miles from Home"` |
| `distance` | number | Distance from HOME in user units (mi/km) |
| `savedPlaces` | string (JSON) | All Life360 places in the circle |
| `history` | string | Compact encoded location history (when `saveHistory` is true). Format: `"1|s,lat,lng|ds,dlat,dlng|…"` — first entry absolute, rest are signed deltas in units of `0.00001°`. Capped at 1024 chars. GPS jitter (<5m) suppressed. |

### Motion / driving

| Attribute | Type | Meaning |
| --- | --- | --- |
| `inTransit` | enum `true`/`false` | From Life360, optionally overridden by `transitThreshold` |
| `isDriving` | enum `true`/`false` | From Life360, optionally overridden by `drivingThreshold` |
| `speed` | number | Current speed in user units (mph/kph) |
| `userActivity` | string | Life360 activity hint (`unknown`/`biking`/`running`/`vehicle`); the `os_` prefix is stripped. Emitted every poll, but the value is only meaningful when the API payload includes it (often blank on V3) |

### Phone / connectivity

| Attribute | Type | Meaning |
| --- | --- | --- |
| `charge` | enum `true`/`false` | Phone is charging |
| `wifiState` | enum `true`/`false` | Phone is on WiFi |
| `shareLocation` | string | Life360 member sharing flag |

### Identity

| Attribute | Type | Meaning |
| --- | --- | --- |
| `memberName` | string | First + last name |
| `avatar` | string | URL of Life360 profile photo |
| `phone` | string | From Life360 `communications` (channel: Voice) |
| `email` | string | From Life360 `communications` (channel: Email) |

### HTML tiles (only when `generateHtml` is true)

| Attribute | Type | Meaning |
| --- | --- | --- |
| `avatarHtml` | string (HTML) | `<img>` of the avatar |
| `html` | string (HTML) | Full dashboard tile (avatar + status + address + battery + WiFi + last-update) |

### External-integration attributes (emitted, not declared)

These are written by the "Life360 Tracker" external app via the driver's `sendHistory`/`sendTheMap` commands, not by `generatePresenceEvent`. None are declared in the driver's `attribute` block — they exist only because something calls `sendEvent` with that name.

| Attribute | Type | Meaning |
| --- | --- | --- |
| `bpt-history` | string (HTML) | Rolling 10-line log table (set by `sendHistory`) |
| `numOfCharacters` | number | Length of `bpt-history` |
| `lastLogMessage` | string | Most recent `sendHistory` line |
| `lastMap` | string | Google Maps URL set by `sendTheMap` |

---

## Driver: State variables

| Variable | Type | Set by | Notes |
| --- | --- | --- | --- |
| `locationHistory` | List `[timeMs, lat, lng]` | `saveLocationHistory()` (when `saveHistory` is on) | Most-recent-first, capped at 100 entries; source for the compact `history` attribute. Cleared by `historyClearData` |
| `list1` | List (String) | `sendHistory()` (external Tracker app) | Most-recent-first log lines, capped at ~10; source for the `bpt-history` tile. Cleared by `historyClearData` |

---

## Driver: Commands

| Command | Args | Purpose |
| --- | --- | --- |
| `refresh` | — | Calls `parent.refresh()` → forces an immediate `fetchLocations()` and re-arms the schedule |
| `arrived` | — | Manually flip `presence` to `present` |
| `departed` | — | Manually flip `presence` to `not present` |
| `sendHistory` | string | External integration — prepends a line to `state.list1` → `bpt-history` tile |
| `sendTheMap` | string | External integration — sets `lastMap` |
| `historyClearData` | — | Clears the entire driver-side history surface: `state.locationHistory` + `state.list1`, and resets `bpt-history`/`numOfCharacters`/`lastLogMessage` |

---

## Capability-repurposing cheatsheet

These standard capabilities are repurposed for Life360 data, which is non-obvious in Rule Machine:

| Capability | Real meaning here |
| --- | --- |
| `Switch` on/off | WiFi connected / not |
| `Contact Sensor` open/closed | Charging / on battery |
| `Acceleration Sensor` active/inactive | In transit OR driving OR outside home radius |
