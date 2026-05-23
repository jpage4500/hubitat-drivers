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
| `dynamicPolling` | bool | If true, poll faster while any member is in transit | `true` |
| `dynamicPollFreq` | enum | Poll interval while a member is in transit: `5`/`10`/`20`/`30` | `20` |
| `notifyDevices` | capability.notification | Devices notified on access-token failure | `[<notification device>]` |
| `googleMapsApiKey` | text (optional) | If set, `/view` endpoint uses Google Maps; else OpenStreetMap | empty |
| `logEnable` | bool | Verbose debug logging | `false` |

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
| `cookies` | String | `captureCookies` / `captureCookiesAsync` | `clearSessionCache()` (auth errors) | `Cookie` header value sent on subsequent requests |
| `deviceId` | String (UUID) | `getHttpHeaders()` first call | never | Stable per-install identifier sent to Life360 |
| `etag-<memberId>` | String | `handleMemberLocationResponse` on 200 OK | `clearSessionCache()` | Per-member `If-None-Match` value for 304-not-modified responses |
| `inflight-<memberId>` | Long (epoch ms) | `fetchMemberLocation` before `asynchttpGet` | `handleMemberLocationResponse` / `clearSessionCache()` | In-flight marker; prevents async-HTTP pile-up |

### Polling / scheduling

| Variable | Type | Set by | Notes |
| --- | --- | --- | --- |
| `lastUpdateMs` | Long | `fetchLocations()` | Throttle: rejects calls < 5s apart |
| `lastSuccessMs` | Long | `handleMemberLocationResponse` on 200/304 | Watchdog input; triggers warn if stale > `max(pollFreq×10, 10min)` |
| `updateTimeMs` | Long | `handleTimerFired` | Next time to re-jitter the schedule and re-fetch the member list |
| `dynamicPollingActive` | Boolean | `scheduleUpdates()` | True while polling at `dynamicPollFreq` (a member is in transit) |
| `memberInTransit` | Boolean | `dynamicPolling()` | True if ANY tracked member's `inTransit-<id>` is true |
| `inTransit-<memberId>` | Boolean | `notifyChildDevice` (echo from driver) | Per-member in-transit flag; drives `dynamicPolling` |

### Error / health

| Variable | Type | Set by | Cleared by | Notes |
| --- | --- | --- | --- | --- |
| `failCount` | Integer | `handleException` / `handleMemberLocationResponse` on 401/403 | 200-OK response, `updated()` | Auth-failure streak; triggers `tokenLikelyExpired` at ≥3 |
| `tokenLikelyExpired` | Boolean | `handleException` / `handleMemberLocationResponse` on 401/403 ×3 | 200-OK response, `updated()` | When true, polling is short-circuited; user-facing banner shown |
| `rateLimitedUntilMs` | Long | `handleException` / `handleMemberLocationResponse` on 429 | 200-OK response, `updated()` | Honors `Retry-After`; polling skipped until this time |
| `message` | String | various error paths | success / `updated()` | Red banner shown on the app's main page |

---

## App: Scheduled jobs

| Handler | Schedule | Set by | Purpose |
| --- | --- | --- | --- |
| `handleTimerFired` | `0/<refreshSecs> * * * * ? *` (sub-minute) or `0 */<min> * * * ? *` | `scheduleUpdates()` | Calls `fetchLocations()`; periodically re-arms schedule + re-fetches member list |

`refreshSecs` = `pollFreq` (or `dynamicPollFreq` if dynamic is active) + 0–4s random jitter.

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
| `lastLocationUpdate` | date | When `address1` last changed |
| `lastUpdated` | date | When this driver event batch last ran |
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
| `userActivity` | string | Life360 V5 activity (`unknown`/`biking`/`running`/`vehicle`) — not currently populated |

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
| `bpt-history` | string (HTML) | Rolling 10-line log table (set by `sendHistory`, called by the external "Life360 Tracker" app) |
| `numOfCharacters` | number | Length of `bpt-history` |
| `lastLogMessage` | string | Most recent `sendHistory` line |
| `lastMap` | string | Google Maps URL set by `sendTheMap` (external integration) |

---

## Driver: Commands

| Command | Args | Purpose |
| --- | --- | --- |
| `refresh` | — | Calls `parent.refresh()` → forces an immediate `fetchLocations()` and re-arms the schedule |
| `arrived` | — | Manually flip `presence` to `present` |
| `departed` | — | Manually flip `presence` to `not present` |
| `sendHistory` | string | External integration — appends to `bpt-history` tile |
| `sendTheMap` | string | External integration — sets `lastMap` |
| `historyClearData` | — | Clears `bpt-history` |

---

## Capability-repurposing cheatsheet

These standard capabilities are repurposed for Life360 data, which is non-obvious in Rule Machine:

| Capability | Real meaning here |
| --- | --- |
| `Switch` on/off | WiFi connected / not |
| `Contact Sensor` open/closed | Charging / on battery |
| `Acceleration Sensor` active/inactive | In transit OR driving OR outside home radius |
