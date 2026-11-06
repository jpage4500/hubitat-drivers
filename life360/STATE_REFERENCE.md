# Life360+ App & Driver — State Reference

Lookup map of every setting, state variable, scheduled job, subscription and child-device attribute in the Life360+ app and driver, for triaging an install or reading the Hubitat "App Status" / "Device Details" pages. Behavior and reasoning live in [APP_SPEC.md](APP_SPEC.md); the code is authoritative.

---

## App: Settings (user inputs)

Configured via the app's preferences page. Read at runtime as `settings.<name>` or just `<name>`.

| Setting             | Type                    | Purpose                                                                                                                                                                                                                                                         |
| ------------------- | ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `access_token`      | text                    | Life360 bearer token (paste from `life360.com` → DevTools → Network → token packet)                                                                                                                                                                             |
| `circle`            | enum                    | Selected Life360 circle ID                                                                                                                                                                                                                                      |
| `place`             | enum                    | Life360 place ID that represents HOME for this Hubitat install                                                                                                                                                                                                  |
| `users`             | enum (multi)            | Life360 member IDs to create child devices for                                                                                                                                                                                                                  |
| `pollFreq`          | enum                    | Default poll interval (seconds): `10`/`15`/`30`/`60`/`180`/`300`/`0`=Disabled                                                                                                                                                                                   |
| `dynamicPolling`    | bool                    | If true, poll faster while any member is in transit. Only engages when `pollFreq > dynamicPollFreq`                                                                                                                                                             |
| `dynamicPollFreq`   | enum                    | Poll interval while a member is in transit: `5`/`10`/`20`/`30`                                                                                                                                                                                                  |
| `notifyTokenExpiry` | bool                    | Master switch for token-expiry alerts (default on). When off, the device picker + repeat-reminder inputs are hidden and no alerts fire                                                                                                                          |
| `notifyDevices`     | capability.notification | Devices notified on access-token failure (shown only when `notifyTokenExpiry` is on)                                                                                                                                                                            |
| `notifyRepeatHours` | enum                    | Repeat the token-expiry reminder every `never`/`2`/`6`/`12`/`24`/`48` h (shown only when at least one notify device is set)                                                                                                                                     |
| `googleMapsApiKey`  | text (optional)         | If set, `/view` endpoint uses Google Maps; else OpenStreetMap                                                                                                                                                                                                   |
| `logEnable`         | bool                    | Verbose debug logging                                                                                                                                                                                                                                           |
| `logRawPayload`     | bool                    | Verbose raw-API diagnostics — logs sensitive data (GPS, partial token, cookie heads, full payloads). Debug only                                                                                                                                                 |
| `logShowNames`      | bool                    | Include member/place/circle names in logs (default on). Off → UUIDs only, for safe log sharing                                                                                                                                                                  |
| `logHeartbeat`      | bool                    | Log one heartbeat line per periodic roster refresh (default on)                                                                                                                                                                                                 |
| `logDrivingTransitions` | bool                | "Driving Logging" (default on): log every poll of a member in motion; off = log only when a member starts or stops moving                                                                                                                                    |
| `logShowMapsLink`   | bool                    | Include a Google Maps link in the "member moved" info log (default on)                                                                                                                                                                                          |
| `logUnits`          | enum                    | Units for speed/distance in logs and device attributes: `"life360"` (follow account pref), `"hubitat"` (hub °F/°C), `"imperial"`, `"metric"`. Applies to every device whose own `unitOverride` is `"life360"`; a device set to anything else keeps its own choice (the app's help text overstates this) |
| `forceUpdateMember` | enum (transient)        | Member targeted by the Force Update button. Fire-once — cleared (`app.removeSetting`) immediately after the request is sent, so the dropdown resets to blank                                                                                                    |

---

## App: State variables

Persisted via `state.<name>`. Reset on Hubitat reboot only if explicitly cleared (most survive restart).

### Selection / discovery cache

| Variable      | Type   | Set by                        | Notes                                                                                                                                                                                                                                                                                          |
| ------------- | ------ | ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `circles`     | List   | `fetchCircles()`              | Response from `/v4/circles` for the circle picker                                                                                                                                                                                                                                              |
| `places`      | List   | `fetchPlaces()`               | Response from `/v3/circles/<id>/places` for the HOME picker                                                                                                                                                                                                                                    |
| `members`     | List   | `fetchMembers()`              | **Trimmed** to `[id, firstName, lastName, issues.disconnected]` per member, sorted by id. See APP_SPEC §4.2 |
| `accessToken` | String | `createAccessToken()` (OAuth) | Token used for the `/view` map endpoint URL                                                                                                                                                                                                                                                    |

---

## App: Account preferences (fetched from Life360)

| Variable        | Type   | Set by                                                                                   | Notes                                                                                                                                                                                                                                                        |
| --------------- | ------ | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `unitOfMeasure` | String | `captureUnitOfMeasure()` (called from `checkToken()`, `handleUserSettingsResponse()` and the token probe) | Life360 account units preference: `"i"` = imperial (miles/mph), `"m"` = metric (km/kph). Fetched on `installed()`, `updated()`, and Check Token. Read by `getUnitIsMiles()` and forwarded to child devices. Null until the first successful `GET /users/me`. |

### Session / HTTP plumbing

| Variable                      | Type            | Set by                                                                                               | Cleared by                                             | Notes                                                                                                                                   |
| ----------------------------- | --------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| `cookies`                     | String          | `captureCookies` / `captureCookiesAsync` (via `mergeCookie`)                                         | `clearSessionCache()` (auth errors)                    | `Cookie` header value (`__cf_bm` + `_cfuvid`) sent on subsequent requests. Merged by name so a rotating `__cf_bm` never drops `_cfuvid` |
| `etag-<memberId>`             | String          | `handleMemberLocationResponse` on 200 OK                                                             | `clearSessionCache()`; etag drops by Done, refresh, Fetch Locations/Places | Per-member `If-None-Match` value; an unchanged response is skipped (APP_SPEC §4.1)                                                                         |
| `transientCount-<memberId>`   | Integer         | `handleMemberLocationResponse` on 408, any 5xx except 501/505, or a network-level error (no status) | 200/304 success, `clearSessionCache()`                 | Consecutive-transient-error streak; drives exponential backoff (shift exponent capped at 6 to avoid `long` overflow)                    |
| `transientUntilMs-<memberId>` | Long (epoch ms) | `handleMemberLocationResponse` on 5xx or network error                                               | 200/304 success, `clearSessionCache()`                 | Skip this member's fetch until this time (capped 300s backoff)                                                                          |

### Polling / scheduling

| Variable               | Type    | Set by                                    | Notes                                                                                                                                                            |
| ---------------------- | ------- | ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lastSuccessMs`        | Long    | `markFetchSuccess` (at most once a minute) | Watchdog input; triggers warn if stale > `max(pollFreq×10, 10min)`. Removed by `resetWatchdog()` (Done, token recovery) |
| `inTransit-<memberId>` | Boolean | `notifyChildDevice` (echo from driver)    | Per-member moving flag: the driver's return value (`isMoving`: Life360 `inTransit` and Life360 still sending new data; key name kept for compatibility; identity-scoped; survives re-auth). Used by `ensureFastChain` to decide whether to seed a fast-poll chain for this member            |
| `fastChain-<memberId>` | Boolean | `ensureFastChain()`                       | True while a per-member `runIn` fast-poll chain is active. Dropped by `scheduleSlowTimer()` (which calls `unschedule()`) and cleared when a chain ends naturally |

### Membership / discovery polling

| Variable            | Type    | Set by                      | Notes                                                                                                                                                                                                                                                                                                              |
| ------------------- | ------- | --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ~~`memberCount`~~   | Integer | removed | Obsolete — v4 `/circles` no longer returns it; the count is `state.members.size()`. Deleted by `removeObsoleteStateKeys()` |
| `lastMembersPollMs` | Long | MEMORY-ONLY (`RUNTIME` map), not in `state` — `handleSlowTimer` | Throttles the periodic roster refresh to once per `max(pollFreq, 900)` s (~5 min while no roster). Stamped by `updated()`, cleared by `initialize()` |
| `etagChangedMs-<memberId>` | Long | MEMORY-ONLY (`RUNTIME` map), not in `state` — `handleMemberLocationResponse` | When this member's etag last changed; drives the stale-etag stop (APP_SPEC §13). Removed by `cleanupMemberState()` and `uninstalled()`; lost on reboot, which only restarts the clock |
| `noEtagWarnMs` | Long | MEMORY-ONLY (`RUNTIME` map), not in `state` — `handleMemberLocationResponse` | When the "no l360-etag header" warning last fired (one per `MEMBERS_REFRESH_SECS`, app-wide). Cleared by `uninstalled()` |
| `locTs-<memberId>` | Long | MEMORY-ONLY (`RUNTIME` map), not in `state` — `handleMemberLocationResponse` | Last location timestamp Life360 sent; used only by the debug `FALSE POSITIVE` line. Removed by `cleanupMemberState()` and `uninstalled()` |

### UI one-shot status (button results)

Each `*Status` holds an HTML result string; the paired `*StatusPending` flag survives the single page re-render after the button press, then `mainPage()` clears the status on the next open.

| Variable                                         | Type             | Set by                                                                    | Notes                                                 |
| ------------------------------------------------ | ---------------- | ------------------------------------------------------------------------- | ----------------------------------------------------- |
| `tokenStatus` / `tokenStatusPending`             | String / Boolean | `checkToken()` (Check Token button)                                       | Token-validity result shown under STEP 1              |
| `forceUpdateStatus` / `forceUpdateStatusPending` | String / Boolean | `forceMemberUpdate()` / `handleForceUpdateResponse` (Force Update button) | Force-update result shown in the Force Update section |

### Error / health

| Variable             | Type    | Set by                                                           | Cleared by                                                     | Notes                                                                                                                                                                                                                                                                                       |
| -------------------- | ------- | ---------------------------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `failCount`          | Integer | `handleException` / `handleMemberLocationResponse` on 401/403    | 200-OK response, `updated()`                                   | Auth-failure streak; triggers `tokenLikelyExpired` at ≥3                                                                                                                                                                                                                                    |
| `tokenLikelyExpired` | Boolean | `handleException` / `handleMemberLocationResponse` on 401/403 ×3 | `handleTokenProbeResponse` on 200 (auto-recovery), `updated()` | When true, polling slows to 5 min and each tick probes `/users/me`; a 200 auto-recovers (APP_SPEC §5). |
| `rateLimitedUntilMs` | Long    | `handleException` / `handleMemberLocationResponse` on 429        | 200-OK response, `updated()`                                   | Honors `Retry-After`; polling skipped until this time                                                                                                                                                                                                                                       |
| `watchdogWarned`     | Boolean | `handleSlowTimer` watchdog (rising edge)                         | 200/304 success, `resetWatchdog()`                                                | Prevents the "no successful update" warning from spamming every tick                                                                                                                                                                                                                        |
| `message`            | String  | various error paths                                              | `markFetchSuccess`, `fetchCircles`/`fetchPlaces` 200, non-periodic `fetchMembers` 200, token probe 200, `updated()`; `resetWatchdog()` clears only a `WATCHDOG` banner. The periodic roster refresh never touches it | Red banner shown on the app's main page                                                                                                                                                                                                                                                     |
| `placesEmptyWarned`  | Boolean | `notifyChildDevice` (rising edge)                                | `fetchPlaces()` success                                        | Prevents the "state.places is empty" error from spamming every tick while places are missing                                                                                                                                                                                                |

---

## App: Scheduled jobs

| Handler                   | Schedule                                                           | Set by                                   | Purpose                                                                                                                                                                                                                                                                    |
| ------------------------- | ------------------------------------------------------------------ | ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `handleSlowTimer`         | `0/<baseSecs> * * * * ? *` (sub-minute) or `0 */<min> * * * ? *`   | `scheduleSlowTimer()`                    | Each tick: watchdog, roster refresh (APP_SPEC §4.2), then `fetchLocations()` (or the `/users/me` probe when `tokenLikelyExpired`) |
| `fastPollMember`          | one-shot `runIn(<dynamicPollFreq>s)`, self-rescheduling per member | `ensureFastChain()` / `fastPollMember()` | Per-member fast-poll chain for in-transit members (APP_SPEC §4.3); ends when the member stops moving, is deselected, or `scheduleSlowTimer()` is called                                                               |
| `sendTokenExpiryReminder` | one-shot `runIn(<notifyRepeatHours>h)`, self-rescheduling          | `scheduleTokenExpiryReminder()`          | Repeats the token-expiry notification while `tokenLikelyExpired` stays true; chain stops once the token is refreshed or `notifyRepeatHours = never`                                                                                                                        |

`baseSecs` = `pollFreq`, forced to 300 when `tokenLikelyExpired`.

---

## App: Subscriptions

| Source               | Event         | Handler                                                                                                                   |
| -------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `location` (Hubitat) | `systemStart` | `initialize` — calls `clearSessionCache()` (drops session caches left from before the restart) then re-schedules polling |

---

## App: HTTP endpoints (mappings)

| Path    | Method | Handler      | Notes                                                                                                                              |
| ------- | ------ | ------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| `/view` | GET    | `renderView` | Renders all selected members on an OSM (or Google) map. Requires OAuth enabled on the app and `?access_token=<state.accessToken>`. |

---

## Driver: Settings (per child device)

| Setting            | Type   | Default     | Purpose                                                                                                                                                                                                                                                                                                                                                                                                         |
| ------------------ | ------ | ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `unitOverride`     | enum   | `"life360"` | Per-device units override: `"life360"` (follow account preference), `"hubitat"` (follow hub's °F/°C), `"imperial"` (miles/mph), `"metric"` (km/kph). When set to anything other than `"life360"`, the app-level `logUnits` setting and Life360 account preference are both ignored for this device. **Migrated** from the old `isMiles` bool in `updated()` (if `isMiles == false` → `unitOverride = "metric"`) |
| `generateHtml`     | bool   | false       | Build `html` / `avatarHtml` tile attributes                                                                                                                                                                                                                                                                                                                                                                     |
| `transitThreshold` | number | 0           | Override Life360's `inTransit` — flip true at this speed (in display units); `0` = use Life360                                                                                                                                                                                                                                                                                                                  |
| `drivingThreshold` | number | 0           | Override Life360's `isDriving` — flip true at this speed; `0` = use Life360                                                                                                                                                                                                                                                                                                                                     |
| `avatarFontSize`   | number | 15          | Font size in the HTML tile                                                                                                                                                                                                                                                                                                                                                                                      |
| `avatarSize`       | number | 75          | Avatar size (%) in the HTML tile                                                                                                                                                                                                                                                                                                                                                                                |
| `saveHistory`      | bool   | false       | Append each new lat/lng to `state.locationHistory` (capped at 100) and write the compact `history` attribute                                                                                                                                                                                                                                                                                                    |
| `logEnable`        | bool   | false       | Verbose debug logging                                                                                                                                                                                                                                                                                                                                                                                           |

---

## Driver: Attributes (per child device)

Set from `generatePresenceEvent`, with plain `sendEvent` (the hub drops unchanged values itself). Read via `device.currentValue("<name>")` or in Rule Machine.

### Capabilities (standard Hubitat)

| Capability          | Attribute      | Values                    | Meaning                                                                                                                                   |
| ------------------- | -------------- | ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Presence Sensor     | `presence`     | `present` / `not present` | Inside HOME radius                                                                                                                        |
| Battery             | `battery`      | 0–100                     | Phone battery %                                                                                                                           |
| Power Source        | `powerSource`  | `dc` / `battery`          | Charging source (Hubitat canonical enum values; the HTML tile displays `DC`/`BTRY` for compactness but the attribute itself is lowercase) |
| Switch              | `switch`       | `on` / `off`              | **Repurposed:** WiFi connected (on) or not (off). Declared as `enum ["on","off"]`                                                         |
| Contact Sensor      | `contact`      | `open` / `closed`         | **Repurposed:** charging (open) or on battery (closed). Declared as `enum ["open","closed"]`                                              |
| Acceleration Sensor | `acceleration` | `active` / `inactive`     | **Repurposed:** in transit OR driving OR not at home. Declared as `enum ["active","inactive"]`                                            |

### Location

| Attribute            | Type          | Meaning                                                                          |
| -------------------- | ------------- | -------------------------------------------------------------------------------- |
| `latitude`           | number        | Current latitude                                                                 |
| `longitude`          | number        | Current longitude                                                                |
| `accuracy`           | number        | GPS uncertainty radius in meters (lower = better fix)                            |
| `address1`           | string        | Current place/address (`"Home"` when inside HOME radius; `"No Data"` if missing) |
| `address1prev`       | string        | Previous value of `address1` when it changes                                     |
| `lastLocationUpdate` | date          | When `address1` last changed (NOT every poll)                                    |
| `lastUpdated`        | date          | When this driver event batch last ran (every poll that reaches the driver; polls whose etag is unchanged are skipped, so it moves when the member's data changes) |
| `since`              | number        | Epoch seconds — Life360's "at current address since" timestamp                   |
| `status`             | string        | `"At Home"` or `"<x.x> miles from Home"`                                         |
| `distance`           | number        | Distance from HOME in user units (mi/km)                                         |
| `savedPlaces`        | string (JSON) | Declared but never emitted (the app no longer passes places to the driver)       |
| `history`            | string        | Compact location history (when `saveHistory` is on), format in APP_SPEC §7.4. Capped at 1024 chars. |

### Motion / driving

| Attribute      | Type                | Meaning                                                                                                                                                                                                 |
| -------------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `inTransit`    | enum `true`/`false` | From Life360, optionally overridden by `transitThreshold`                                                                                                                                               |
| `isDriving`    | enum `true`/`false` | From Life360, optionally overridden by `drivingThreshold`                                                                                                                                               |
| `isMoving`     | enum `true`/`false` | Derived: `inTransit` AND Life360 still sending new data (APP_SPEC §13); goes `false` after 240 s without new data, usually well before Life360 clears `inTransit`, and is what the app uses for the fast-poll decision. With a Minimum Transit Speed set it equals `inTransit` |
| `speed`        | number              | Current speed in user units (mph/kph)                                                                                                                                                                   |
| `userActivity` | string              | Life360 activity hint (`unknown`/`biking`/`running`/`vehicle`); the `os_` prefix is stripped. Sent on every update (the hub drops unchanged values); `unknown` when Life360 sends none. The value is only meaningful when the API payload includes it (often blank on V3) |

### Phone / connectivity

| Attribute       | Type                | Meaning                                          |
| --------------- | ------------------- | ------------------------------------------------ |
| `charge`        | enum `true`/`false` | Phone is charging                                |
| `wifiState`     | enum `true`/`false` | Phone is on WiFi                                 |
| `shareLocation` | string              | Life360 member sharing flag (`"true"`/`"false"`) |

### Identity

| Attribute    | Type   | Meaning                                        |
| ------------ | ------ | ---------------------------------------------- |
| `memberName` | string | First + last name                              |
| `avatar`     | string | URL of Life360 profile photo                   |
| `phone`      | string | From Life360 `communications` (channel: Voice) |
| `email`      | string | From Life360 `communications` (channel: Email) |

### HTML tiles (only when `generateHtml` is true)

| Attribute    | Type          | Meaning                                                                        |
| ------------ | ------------- | ------------------------------------------------------------------------------ |
| `avatarHtml` | string (HTML) | `<img>` of the avatar                                                          |
| `html`       | string (HTML) | Full dashboard tile (avatar + status + address + battery + WiFi + last-update) |

### External-integration attributes

These are written by the "Life360 Tracker" external app via the driver's `sendHistory`/`sendTheMap` commands, not by `generatePresenceEvent`.

| Attribute         | Type          | Meaning                                          |
| ----------------- | ------------- | ------------------------------------------------ |
| `bpt-history`     | string (HTML) | Rolling 10-line log table (set by `sendHistory`) |
| `numOfCharacters` | number        | Length of `bpt-history`                          |
| `lastLogMessage`  | string        | Most recent `sendHistory` line                   |
| `lastMap`         | string        | Google Maps URL set by `sendTheMap`              |

---

## Driver: State variables

| Variable          | Type                      | Set by                                             | Notes                                                                                                               |
| ----------------- | ------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `locationHistory` | List `[timeMs, lat, lng]` | `saveLocationHistory()` (when `saveHistory` is on) | Most-recent-first, capped at 100 entries; source for the compact `history` attribute. Cleared by `historyClearData` |
| `list1`           | List (String)             | `sendHistory()` (external Tracker app)             | Most-recent-first log lines, capped at ~10; source for the `bpt-history` tile. Cleared by `historyClearData`        |

---

## Driver: Commands

| Command            | Args   | Purpose                                                                                                                                             |
| ------------------ | ------ | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `refresh`          | —      | Calls `parent.refresh()` → drops the stored etags and forces an immediate `fetchLocations()` (the schedule and any fast-poll chains are left running)                                                          |
| `arrived`          | —      | Manually flip `presence` to `present`                                                                                                               |
| `departed`         | —      | Manually flip `presence` to `not present`                                                                                                           |
| `sendHistory`      | string | External integration — prepends a line to `state.list1` → `bpt-history` tile                                                                        |
| `sendTheMap`       | string | External integration — sets `lastMap`                                                                                                               |
| `historyClearData` | —      | Clears the entire driver-side history surface: `state.locationHistory` + `state.list1`, and resets `bpt-history`/`numOfCharacters`/`lastLogMessage` |
