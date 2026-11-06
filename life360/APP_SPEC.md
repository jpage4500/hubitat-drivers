# Life360+ — App Specification

> **Purpose.** What the Life360+ Hubitat integration does, the contracts it must keep, and why it is built
> the way it is. The code in `life360_app.groovy` / `life360_driver.groovy` is the final authority; this
> file records what the code cannot say: wire facts, frozen names, and the reasons behind decisions.
> Companions: [STATE_REFERENCE.md](STATE_REFERENCE.md) (every setting, state key, attribute),
> [CHANGELOG.md](CHANGELOG.md) (release notes), [README.md](README.md) (users).

The integration tracks the members of a Life360 circle as Hubitat presence/location devices. Two files
are installed on the hub: `life360_app.groovy` (parent app: talks to Life360, owns scheduling and child
devices) and `life360_driver.groovy` (one child device per tracked member, exposing location and phone
state as attributes).

There is no public Life360 API. Everything on the wire was reverse-engineered, and the integration has
gone dark several times when Life360 changed their backend. The design goal is the **least fragile,
fewest-moving-parts** implementation that still does the job.

---

## 0. The one rule that overrides everything: DO NOT BREAK THE COOKIE PATH

> The wire behavior below is a **known-working configuration**. Treat the bytes on the wire (URL,
> headers, cookie capture/replay) as load-bearing. Anything may be restructured _around_ it, but the
> wire contract stays byte-for-byte identical.

### 0.1 The wire contract

| Element              | Required value                                                 | Why                                                                                                      |
| -------------------- | -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Base URL             | `https://api-cloudfront.life360.com/v{version}`                | The cloudfront host is what works. `api.life360.com` is a documented fallback only.                      |
| API version          | `3` for member/location/place/force-update calls; `4` only for the setup-time `/circles` picker | v4 `/circles` lists circles but no longer returns `memberCount`.                 |
| `Accept`             | `application/json`                                             | —                                                                                                        |
| `cache-control`      | `no-cache`                                                     | —                                                                                                        |
| `User-Agent`         | `com.life360.android.safetymapd/KOKO/23.50.0 android/13`       | Exact mobile-app fingerprint. An alternate is kept commented in `getHttpHeaders`.                         |
| `Authorization`      | `Bearer <access_token>`                                        | —                                                                                                        |
| **No other headers** | —                                                              | Extra headers (`X-Application`, `circleid`) have triggered 403s.                                          |
| `Cookie`             | replayed from the captured jar                                 | **The Cloudflare gate** (§0.2).                                                                           |

`life360Params(path, version = 3)` returns `[uri, headers: getHttpHeaders(), timeout: 30]`. Callers add
the `Cookie` header from `state.cookies`.

### 0.2 Cookie capture/replay

- Cloudflare sets `__cf_bm` (bot management, rotates about every 30 min) and `_cfuvid` (stable).
  **Without `__cf_bm` you get a 403 within minutes.**
- **Capture cookies on EVERY response** (circles, members, location, force-update, `/users/me`, token
  probe). Both capture paths run: `captureCookies` (sync `httpGet`) and `captureCookiesAsync`
  (`asynchttp*` callbacks).
- **Replay the accumulated jar on EVERY request.**
- **The jar is a name→value upsert, never a whole-jar overwrite.** `mergeCookie()` parses `state.cookies`,
  upserts the one incoming cookie by name, and re-serializes. Overwriting the jar on rotation silently
  drops `_cfuvid` and causes a delayed 403, the failure that has killed the integration before.
- The cookie value is the first segment before `;` (`tokenize(';')[0]`). `__cf_bm` is base64-url-safe, so
  there are no commas; splitting on `,` was wrong. A well-formed `name=value` is required before upserting.
- `cookieJarSummary()` logs cookie **names** only (8-character value heads under `logRawPayload`), and runs
  on auth failure so a token problem (jar intact) can be told from a cookie problem (jar missing a cookie).

> **Soak test:** any change to §0 must run against a live circle for several hours. Cloudflare 403s appear
> minutes to hours after a bad change.

---

## 1. Design principles

1. **Per-member location poll, skipped when nothing changed.** Each selected member is fetched with
   `GET /circles/{id}/members/{mid}` and that member's `If-None-Match`. Life360 answers with a full `200`
   carrying the same etag, so the app skips the parse and driver call itself (§4.1). Locations come only
   from this poll.
2. **Async.** Every per-member fetch is `asynchttpGet`. The HTTP timeout is capped at the member's own
   poll interval (max 30 s), so a hung request ends before its next tick; there is no in-flight guard.
3. **Trust the payload; don't outsmart Life360.** `inTransit` / `isDriving` and the rest of `location` pass
   straight through. The only override is the user's explicit speed thresholds (`transitThreshold` /
   `drivingThreshold`). The one derived value is `isMoving` and the boolean the driver returns for the
   fast-poll decision: both mean "Life360 is still sending new data" (§13), or just `inTransit` when a Minimum Transit Speed is set. `inTransit` / `isDriving` are
   never altered.
4. **Force-update for freshness, not inference.** When a fresh fix is needed, ask the phone (§6).
5. **Fail loud, fail safe.** On auth failure: clear session, flag the token, slow polling, notify once.
   Never hammer a dead endpoint.
6. **Read-only and unobtrusive.** No webhooks (disabled by Life360); no writes except force-update;
   minimal state; minimal scheduler churn. Hubitat state writes are costly, so each is skipped when it
   would change nothing.
7. **PII-aware logging.** Two orthogonal axes, verbosity and redaction (§10), let users share logs safely.
8. **Device-facing backward compatibility is non-negotiable.** Rules, dashboards and webCoRE pistons bind
   to child-device attributes, capabilities and commands. Every device-facing name and value is frozen (§7.3).

---

## 2. Architecture

Three cadences, all driven from the single `handleSlowTimer` scheduled job plus per-member `runIn` chains:

- **Slow timer** (`handleSlowTimer`) at the user's Default Refresh Rate (`pollFreq`): watchdog, then the
  roster refresh, then `fetchLocations()` (or the token probe while the token is flagged).
- **Per-member fast-poll chains** (`ensureFastChain` / `fastPollMember`) at `dynamicPollFreq` for members
  in transit, when dynamic polling is on (§4.3).
- **Roster refresh** (`fetchMembers(true)`), floored at 15 minutes, riding the slow tick (§4.2).

Other entry points: the Done button (`updated()`), `refresh()` (driver Refresh / preference save),
`forceMemberUpdate()` (button), and the `/view` map endpoint.

---

## 3. API surface

Base path `https://api-cloudfront.life360.com/v{version}`.

| Purpose                     | Method + path                                     | Ver | Notes                                                                                                                                                                              |
| --------------------------- | ------------------------------------------------- | --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Poll one member**         | `GET /circles/{circle}/members/{member}`          | 3   | **Core call**, one per selected member per tick, with `If-None-Match`. `200` + changed `l360-etag` = new data; `200` + same etag = unchanged (Life360 never sends `304`).             |
| **Roster / membership**     | `GET /circles/{circle}/members`                   | 3   | `fetchMembers()`: periodic (≥15 min, ~5 min while no roster), on Done, and the Fetch Members button. Names and membership only; its locations are never pushed to devices (they were stale). |
| Force fresh GPS fix         | `POST /circles/{circle}/members/{member}/request` | 3   | Body exactly `{"type":"location"}`, `Content-Type: application/json`, cookies required.                                                                                              |
| Validate token / get units  | `GET /users/me`                                   | 3   | Check Token button, `installed()`/`updated()` (async), and the 5-min probe while the token is flagged expired.                                                                       |
| List circles                | `GET /circles`                                    | 4   | Setup only (Fetch Circles). v4 no longer returns `memberCount`; the count is `state.members.size()`.                                                                                 |
| List places                 | `GET /circles/{circle}/places`                    | 3   | Setup only (Fetch Places, HOME picker).                                                                                                                                              |

**Not used:** `POST /oauth2/token.json` (the app takes a pasted bearer token, never credentials), webhooks
and push subscriptions (disabled by Life360), and any V5/activity endpoint (`userActivity` is parsed if a
v3 payload carries it, never requested).

### 3.1 Key response shapes

`GET /circles/{circle}/members/{member}` returns a member object: `id`, `firstName`, `lastName`, `avatar`,
`communications[]` (`{channel: Voice|Email, value}`), `features.shareLocation`, and `location`:

- `latitude`, `longitude`, `accuracy` (strings); `battery` (%); `charge` and `wifiState` (`"0"`/`"1"`);
  `speed` (number; iPhone can report `-1`, treated as 0); `inTransit` and `isDriving` (`"0"`/`"1"`);
  `since` (epoch seconds, **may be absent**); `name` / `address1` / `address2` / `shortaddress`;
  `userActivity` (often blank on v3; `os_` prefix stripped); `issues.disconnected`.
- All booleans arrive as `"0"`/`"1"` and numbers may arrive as strings: parse with the driver's null-safe
  `toDouble` / `toBool`.

`GET /users/me` returns `firstName`, `lastName`, `id`, `loginEmail` and `settings.unitOfMeasure`
(`"i"` = miles/mph, `"m"` = km/kph), captured by `captureUnitOfMeasure()` and read via `getUnitIsMiles()`.

### 3.2 Token acquisition — the ONLY known working method (PRESERVE)

There is no supported programmatic login. The integration only works with a bearer token lifted from a
browser session already signed in to Life360. These steps must survive in `README.md` and, in short form,
in the app's STEP 1 help text.

1. In a desktop browser, go to `https://www.life360.com` and **sign in** (complete any MFA).
2. Open **Developer Tools** (F12 / right-click → Inspect) → **Network**.
3. Reload so API requests appear; filter for `life360.com`.
4. Click an API request → **Request Headers** → `Authorization: Bearer <long-token>`.
5. Copy **only the token** (no `Bearer`, no quotes or spaces).
6. Paste it into **STEP 1: Access Token** and click **Check Token** (`GET /users/me`; a `200` with your
   name confirms it).

The token is a secret equivalent to a Life360 login. It expires server-side with no fixed lifetime; after
3 consecutive `401`/`403` the app flags `tokenLikelyExpired`, and clicking **Done** clears the flag. The
token and the cookie jar are independent gates: both must be valid.

---

## 4. The loops

`handleSlowTimer` is the single scheduled handler. Each tick, in order:

1. **Watchdog.** If `state.lastSuccessMs` is older than `max(pollFreq×10, 10 min)`, the token isn't flagged,
   and a circle and members are selected, log `WATCHDOG` once on the rising edge (`state.watchdogWarned`) and
   set a banner. `lastSuccessMs` is written at most once a minute. `resetWatchdog()` (Done, token recovery,
   reboot) removes it, so there is no watchdog until the next success, and clears the warning and banner.
2. **Roster refresh** (§4.2).
3. **Locations.** If `tokenLikelyExpired`, run `probeTokenAfterExpiry()` (§5) and return; else `fetchLocations()`.

### 4.1 Location poll and the unchanged-etag skip

`fetchLocations()` runs on each slow tick, the Fetch Locations button, `refresh()`, and after a force-update.
It builds one shared `ctx` map (home place, cookies, `showNames`, `showMapsLink`, `useMiles`, log flags)
and fires one `asynchttpGet` per selected member. `fetchMemberLocation()` skips a member inside its
transient-backoff window, and everything while a `429` window is open (this also stops the 20 s fast chain).

`handleMemberLocationResponse` on a `200`:

- **Unchanged** means the response's `l360-etag` equals either the tag that request sent or the stored
  `etag-<id>` (the sent tag keeps an older response arriving after a newer one from rolling the etag back).
  The header is read **case-insensitively** (`headers.find { it.key?.equalsIgnoreCase("l360-etag") }`),
  because the async headers Map is case-sensitive.
- Unchanged and not in transit, or unchanged for less than `STALE_ETAG_SECS` (240 s): no parse,
  no driver call; `markFetchSuccess`; for a moving member `ensureFastChain` is called so a lost chain
  comes back.
- Unchanged, in transit, and past that window: one driver call with `ctx + [staleSecs: n]` (a copy, since
  `ctx` is shared across members), so the driver returns `isMoving` false and the fast chain ends (§13).
- Changed (or no etag was sent): parse, `notifyChildDevice`, `markFetchSuccess`, store the etag. The
  `etagChangedMs-<id>` clock (memory-only) restarts.
- Done, `refresh()`, Fetch Locations and Fetch Places drop the stored etags, so the next poll reaches the
  driver (changed settings, a refreshed home place, new attributes). `refresh()` does not touch the
  schedule, so running fast chains survive a device Refresh.

`markFetchSuccess(memberId)` clears `failCount`, `tokenLikelyExpired`, `rateLimitedUntilMs`, `message`,
`watchdogWarned` and that member's backoff, and stamps `lastSuccessMs`, skipping every write that would
change nothing.

`notifyChildDevice` calls `generatePresenceEvent(member, null, home, ctx)` on the member's child device
(DNI `"<app.id>.<memberId>"`), stores the returned boolean in `state["inTransit-<id>"]`, and, when it is
true and dynamic polling is on, calls `ensureFastChain`. `home` is resolved once per tick by
`buildPlacesContext()`; the driver uses only `home`.

### 4.2 Roster refresh

`fetchMembers(periodic)` is an async `GET /circles/{id}/members`.

- **When:** every slow tick once `max(pollFreq, 900)` s has passed since the memory-only
  `lastMembersPollMs` (5 min while there is no roster); skipped while the token is flagged, rate-limited
  or unset. Done stamps it and refreshes immediately; a reboot clears it.
- **What it stores:** `state.members` trimmed to `[id, firstName, lastName, issues.disconnected]`, sorted
  by id, written only when different. A `200` with no or an empty list keeps the old roster.
- **What it triggers:** Done and Fetch Members run `syncChildDevices()`; a periodic refresh runs it only
  when the member ids changed. Names and avatars on existing devices update from the per-member poll.
- **Heartbeat:** a periodic refresh logs `heartbeat - <circle>: memberCount:N | name:connected/disconnected ...`
  when `logHeartbeat` is on (default). It uses data already fetched and costs no request.
- **Failures:** `401`/`403` → "access denied" (the circle may also be gone); `404` → "circle not found".
  Both set a banner only on Done / Fetch Members; the periodic refresh logs only.

### 4.3 Dynamic polling (optional)

With `dynamicPolling` on and `dynamicPollFreq < pollFreq`, a member in transit also gets a per-member
`runIn` chain at `dynamicPollFreq` while the slow timer keeps polling everyone at `pollFreq`.

- `ensureFastChain(id)`: no-op unless `fastPollEligible()` (dynamic on, dynamic rate faster than the default
  rate, token healthy) and no `fastChain-<id>` marker; schedules `fastPollMember`, then sets the marker
  (after `runIn` succeeds, so a throw can't strand it).
- `fastPollMember(data)`: re-arms **before** the fetch so a failure can't break the chain, only while the
  member is still in transit, selected and eligible; otherwise removes the marker. Then fetches the member.
- `scheduleSlowTimer()` runs `unschedule()` (kills every job) and clears all `fastChain-*` markers; the next
  slow tick re-seeds movers. Callers are listed in §4.5.
- A mover is occasionally polled twice at the same instant (slow tick plus chain); the unchanged-etag test
  makes the second response a no-op.

### 4.4 Child devices

`syncChildDevices()` (from `installed()`, `updated()`, `initialize()`, and the roster paths in §4.2) creates
a device for each selected member that has none (`addChildDevice("jpage4500", "Life360+ Driver", dni, null,
...)`, **null hub id**) and deletes any child whose DNI is not a selected member, then
`cleanupMemberState(id)` drops that member's keys. `getChildDevices()` is read once and reused. A selected
member missing from `state.members` is skipped with a warning. `uninstalled()` clears this app's `RUNTIME`
entries and removes the child devices.

### 4.5 Scheduler

`scheduleSlowTimer()` is the single entry point (`installed`, `updated`, `initialize`, the auth-failure rising
edge, token recovery). It always does a full rebuild: `unschedule()`, clear `fastChain-*`, re-arm the
token-expiry reminder if the token is flagged (the reminder job dies in `unschedule()`), then
`schedule()` at `pollFreq` (300 s while the token is flagged; none when `pollFreq` is 0 = Disabled; cron
seconds below 60 s, minutes otherwise). There is no no-op guard, so there is no hidden schedule state.
`pollFreqSecs()` / `dynamicPollFreqSecs()` read `(settings.pollFreq ?: "60")` null-safely. A reboot re-arms
via `subscribe(location, 'systemStart', initialize)`.

---

## 5. Error handling and health

Each member fetch is judged independently; a few **account-level** flags are shared (an auth failure or
`429` affects every member).

| Condition                                  | Action                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `200`                                      | §4.1. `markFetchSuccess`.                                                                                                                                                                                                                                                                                                               |
| `401`/`403`                                | **Account-level:** `clearSessionCache()`, `failCount++`; at ≥3 set `tokenLikelyExpired`, then `scheduleSlowTimer()` (slows to 300 s) **before** `notifyTokenExpired()`. **Order matters:** `scheduleSlowTimer()` calls `unschedule()`, which would cancel the reminder job `notifyTokenExpired()` registers. Below 3, a transient "will retry" banner. |
| `429`                                      | **Account-level:** `rateLimitedUntilMs = now + (Retry-After ?: 60) + 10 s` (kept with `Math.max`); pauses every fetch including the fast chain.                                                                                                                                                                                       |
| `408`, any `5xx` except `501`/`505`, network error (no status) | **Per-member** exponential backoff (below).                                                                                                                                                                                                                                                                  |
| other                                      | Log an error and set a banner.                                                                                                                                                                                                                                                                                                          |

**Known limitation:** the `failCount` read-modify-write is racy across concurrent 401 callbacks (Hubitat state
writes aren't atomic and the sandbox has no lock), so the threshold may take 5–6 trips instead of 3. That
delays the notification; it never loses it.

**Per-member backoff.** `transientCount-<id>++`; `transientUntilMs-<id> = now + min(pollSecs × 2^(count-1),
300 s)`, exponent capped at 6 (`MAX_BACKOFF_SHIFT`) so it can't overflow `long`. A flaky member backs off
alone. Any success clears both keys.

**Token recovery.** While `tokenLikelyExpired`, the slow tick runs at 300 s and fires
`probeTokenAfterExpiry()` (async `GET /users/me`). A `200` clears the flag, `failCount`, rate limit and
banner, cancels the reminder chain, restores the normal schedule and captures units (the state clears run
**before** the JSON parse, so a garbled `200` can't leave the app stuck). `401`/`403` or a network error
stays quiet and retries.

**`handleException(tag, e)`** classifies exceptions from the synchronous setup calls (`fetchCircles`,
`fetchPlaces`, Check Token) into `TIMEOUT` / `AUTH` / `RATE_LIMIT` / `TRANSIENT` / `OTHER` and applies the
same account-level updates.

**Token-expiry notification.** `notifyTokenExpired()` fires once on the rising edge to `notifyDevices`
(gated by `notifyTokenExpiry`); `scheduleTokenExpiryReminder()` / `sendTokenExpiryReminder()` repeat it
(never/2/6/12/24/48 h) while the flag stays set, and are cancelled by Done and by recovery.

**`clearSessionCache()`** removes `cookies` and the session-scoped keys (`etag-*`, `transientCount-*`,
`transientUntilMs-*`). Identity keys (`inTransit-*`, `fastChain-*`) survive so transit state isn't lost on a
mere token re-paste. `initialize()` calls it first, dropping caches left from before a reboot.

**Banner copy.** Self-resolving conditions (429, transient 5xx, sub-threshold auth) use lowercase "will retry
automatically" so the red banner reads as recoverable.

---

## 6. Force-update

`forceMemberUpdate(memberId)` POSTs exactly `{"type":"location"}` with `Content-Type: application/json` and
the cookie jar to `/v3/circles/{circle}/members/{member}/request` (`asynchttpPost`), then
`runIn(FORCE_UPDATE_FETCH_DELAY_SECS = 6, "fetchLocations")` so Life360 can push a fresh fix. Two things
made it fail before, and must not regress: a wrong or empty body returns 400, and missing cookies return 403.

The surface is a member dropdown plus a **Force Update** button (gated on selected users and a roster),
with no auto-trigger; the dropdown resets after firing. **Accepted gap:** it doesn't guard on
`tokenLikelyExpired` / `rateLimitedUntilMs`, since it is a manual one-shot.

---

## 7. Driver (child device)

Entry point: `generatePresenceEvent(member, places, home, ctx)` (`places` is always `null`; the driver ignores
it). It returns a boolean: `isMoving`, which is Life360 `inTransit` unless the app passed `ctx.staleSecs`.

- Attributes use plain `sendEvent`; the hub drops unchanged values itself, so the driver keeps no
  "last sent" cache. Only `lastUpdated` and the `html` tile differ on every call, which is why the app avoids
  calling the driver when nothing changed (§4.1).
- Returns `false` early (with a log) if `member`, `member.location` or `home` is null (a stale `settings.place`).
  `since` is null-safe.
- **Units**, in priority order: the driver's `unitOverride` when not `life360`; else `ctx.useMiles` from the
  app's `getUnitIsMiles()` (the app's `logUnits` override is applied there); else the hub's `temperatureScale`.
- **Motion:** `inTransit` / `isDriving` come from Life360; non-zero `transitThreshold` / `drivingThreshold`
  override them from `speedUnits`. `isMoving` is `inTransit` and not stale (§13); with `transitThreshold` set it
  equals `inTransit` (`drivingThreshold` doesn't affect it).
- The "moving @ N mph/kph" info line fires only for `inTransit || isDriving` when the member isn't judged
  still; it includes a Google Maps link only when `ctx.showMapsLink` is true.
- The HTML tile (only with `generateHtml`) uses a `long` epoch for `since`. `haversine()` is `static`.

### 7.1 Presence logic

`present` iff `haversine(member, home) <= home.radius`. Inside the radius `address1 = "Home"`; a missing
address gives `"No Data"`. `status` is `"At Home"` or `"<x.x> miles from Home"` / `"<x.x> km from Home"`.

### 7.2 Capability repurposing

| Standard capability                   | Real meaning                            |
| ------------------------------------- | --------------------------------------- |
| `Switch` on/off                       | WiFi connected / not                    |
| `Contact Sensor` open/closed          | Charging / on battery                   |
| `Acceleration Sensor` active/inactive | In transit OR driving OR away from home |

### 7.3 Backward-compatibility contract — DO NOT CHANGE DEVICE-FACING NAMES OR VALUES

Users have automations bound to these exact names, behaviors and values. An in-place update **must not**
rename, retype or repurpose any of them. New code is fine; renames and value changes are not.

- **Capabilities:** `Actuator`, `Presence Sensor`, `Sensor`, `Refresh`, `Battery`, `Power Source`, `Switch`,
  `Contact Sensor`, `Acceleration Sensor`.
- **Attributes** (names, types and value vocabularies in [STATE_REFERENCE.md](STATE_REFERENCE.md)).
  Semantics to preserve, not "fix": booleans are the **strings** `"true"`/`"false"`; `address1` is the
  literal `"Home"` inside the radius and `"No Data"` when missing; `lastLocationUpdate` changes only when
  `address1` changes, while `lastUpdated` changes with every driver call.
- **Commands:** `refresh`, `arrived`, `departed`, `sendHistory(string)`, `sendTheMap(string)`,
  `historyClearData`. (`arrived`/`departed` force `presence` and are overwritten by the next poll;
  `sendHistory`/`sendTheMap` are called by the external "Life360 Tracker" app.)
- **App ↔ driver bridge:** the parent calls `childDevice.generatePresenceEvent(member, null, home, ctx)` and
  reads the boolean (since 5.2.5 `isMoving`, before it Life360's `inTransit`), storing it in
  `state["inTransit-<id>"]` (key name kept). Parent helpers the driver calls: `getShowNamesInLogs`,
  `getShowMapsLink`, `getLogRawPayload`, `getLogDrivingTransitions`, `getUnitIsMiles`, `refresh`. Keeping these
  stable lets a new app drive an old installed driver and vice versa during a staged update.
- **Device Network ID:** `"${app.id}.${memberId}"`. Changing it orphans every child device.
- **Driver preferences:** `generateHtml`, `saveHistory`, `transitThreshold`, `drivingThreshold`,
  `avatarFontSize`, `avatarSize`, `logEnable`, `unitOverride`. Renaming one resets that user's saved choice.
  (`isMiles`, a bool, became the `unitOverride` enum in 5.2.0 with a one-time migration in `updated()`:
  `isMiles == false` → `metric`.)
- **Where improvement is allowed:** additive attributes and commands; changing _how_ a value is computed or
  _how often_ it is sent; adding a clearer alias **in addition to** a legacy attribute, never instead of it.

### 7.4 Location history (`saveHistory`, default off)

Each new lat/lng is appended to `state.locationHistory` (most recent first, capped at 100) and the compact
`history` attribute is rewritten: `"1|s,lat,lng|ds,dlat,dlng|…"`, where `1` is the format version, the first
entry is absolute (epoch seconds, decimal lat/lng) and the rest are signed deltas (seconds; lat/lng in
`0.00001°` units), capped at 1024 characters. Entries within about 5 m of the previous emitted point are
skipped. Lat/lng are rounded to 5 decimals. The separate `bpt-history` / `numOfCharacters` /
`lastLogMessage` surface belongs to the external Tracker app (`sendHistory`); `historyClearData` clears both.

---

## 8. State model

[STATE_REFERENCE.md](STATE_REFERENCE.md) is the per-variable table. The rules that table can't express:

- **Session-scoped** per-member keys (`etag-`, `transientCount-`, `transientUntilMs-`; list
  `MEMBER_STATE_PREFIXES_SESSION`) are dropped by `clearSessionCache()`. **Identity-scoped** keys
  (`inTransit-`, `fastChain-`; `MEMBER_STATE_PREFIXES_IDENTITY`) survive a re-auth. `cleanupMemberState()`
  drops both for a removed member.
- **Memory-only** values live in the `RUNTIME` static map, keyed `"<app.id>-<name>"` (use `rtGet`/`rtSet`;
  keys must be real `String`s, since a GString hashes differently): `lastMembersPollMs` (lost on reboot means
  "refresh the roster now") and `etagChangedMs-<id>` (lost means "restart the unchanged-etag clock").
- **Obsolete keys** removed by `removeObsoleteStateKeys()` on every `updated()`/`initialize()`: `placesJson`,
  `cachedHome`, `scheduledBaseSecs`, `pollIntervalSecs`, `dynamicPollingActive`, `memberInTransit`,
  `lastCirclesFetchMs`, `lastCirclesPollMs`, `memberCount`, and any `inflight-<id>` keys. Do not
  re-introduce them. No client-invented throttle state either: the cron owns the rate.

---

## 9. Settings

The settings list (names, types, defaults) is in [STATE_REFERENCE.md](STATE_REFERENCE.md). The page flows
STEP 1 token (**Check Token**) → STEP 2 circle (**Fetch Circles**) → STEP 3 HOME (**Fetch Places**) →
STEP 4 members (**Fetch Members**) → STEP 5 **Fetch Locations** (shows the age of the last success), then
Polling, Notifications, Logging, Map View and Force Update. A button result that must survive the single
page re-render uses a `*StatusPending` flag that `mainPage()` clears on the next open.

---

## 10. Privacy, PII and security

The integration handles real names, exact GPS, place and home names, addresses, phones, emails and avatars,
any of which can land in logs that users paste into forum posts.

### 10.1 Logging controls — orthogonal axes

| Setting           | Default | Controls                                                                                                          |
| ----------------- | ------- | ----------------------------------------------------------------------------------------------------------------- |
| `logEnable`       | off     | **Verbosity.** Debug on/off.                                                                                      |
| `logRawPayload`   | off     | **Verbosity, sensitive.** Raw payloads, partial token, cookie heads, GPS. Labeled sensitive.                       |
| `logHeartbeat`    | on      | **Verbosity.** One heartbeat line per periodic roster refresh (about 96/day).                                     |
| `logDrivingTransitions` | on  | **Verbosity.** On = log every poll of a member in motion; off = log only start and stop of movement.                |
| `logShowNames`    | on      | **Redaction.** Off = opaque member ids everywhere (app `showNamesInLogs()`, driver `displayMember()`), and the driver's debug lines hide coordinates and addresses. |
| `logShowMapsLink` | on      | **Redaction.** Off = the "moving" log omits the Maps link and so the coordinates.                                  |

Settings use null-safe tests (`!= false` for on-by-default, `== true` for off-by-default), so an upgrader who
hasn't opened the page gets the documented defaults. `logShowNames` off must replace _all_ names with ids.
Never log the **full** bearer token or cookie jar, even behind `logRawPayload`.

### 10.2 Other security surfaces

- The `/view` URL embeds the OAuth `access_token`; rotate it with **Revoke Map Link** or the OAuth toggle.
- The Google Maps key is visible in page HTML; restrict it by referrer and API in Cloud Console.
- Member names are untrusted: `membersJson` is post-processed (`&`, `<`, `>` become the JSON escapes `\u0026`, `\u003c`,
  `\u003e`) so a name containing `</script>` can't execute in the map page, and the Maps key is HTML-encoded before it is
  placed in a `<script src>`.
- The Life360 bearer token is PII-grade (§3.2).

---

## 11. Map view (`/view`)

`renderView` (OAuth + `?access_token=<state.accessToken>`) collects each child device's latitude/longitude
(skipping null and 0,0) with name, avatar, address, battery, presence, last update and `isMoving` (falling
back to `inTransit`), serializes them to JSON, and renders one full-page map: OpenStreetMap (Leaflet) by
default, or Google Maps when `googleMapsApiKey` is set. Both show avatar markers (initial-letter fallback),
a member count, and refresh every 60 s.

---

## 12. Verification checklist

Hubitat has no local test harness: install on a real hub against a real circle and watch the logs.

1. **Wire contract (§0)** first: one `GET /circles/{id}/members/{mid}` returns 200 and cookies accumulate.
2. **Soak the cookie path** for hours; confirm no 403 creep. This gate has killed the integration before.
3. **Unchanged-etag skip:** an idle member logs `SUCCESS (200, etag unchanged -> treated as 304)` and the
   driver runs only when its data changes. A moving member's unchanged polls skip the same way; after 240 s
   without a changed etag while still `inTransit`, debug logging shows
   `no new Life360 data for Ns ... not fast polling` and the fast chain ends.
4. **Before any in-place release (§7.3):** diff the driver's attribute, capability, command and preference
   names and the app's setting names against the previous version; new entries must be purely additive.

---

## 13. Why stillness is decided by the etag

**Decision (2026-11-04).** A member Life360 still calls in transit is judged stopped when Life360's
`l360-etag` for that member has not changed for `STALE_ETAG_SECS` (240 s, whatever the refresh rate). Life360 can keep `inTransit` (and even a stale speed) set after a phone stops, and the value the
driver returns drives the fast poll rate, so this is a polling decision, not a change to Life360's data.

**What the etag is.** Life360 returns a full `200` with an identical etag when nothing changed, never a `304`
(curl, 7 requests with a cookie jar: always 200, identical etag; Home Assistant's life360 library logged a
304 in Oct 2024, so it was honored once). The etag changes exactly when Life360 has a new fix.

**Evidence** (hub log captures, 2026-11-03 18:00 to 2026-11-04 07:00, three members, `pollFreq` 180,
dynamic 20 s):

| Finding | Data |
| --- | --- |
| Location timestamp follows the etag | Raw payloads (three members): `location.timestamp` and `activity.timestamp` moved together on every poll. 372 of 372 unchanged-etag polls carried the same timestamps; 444 of 461 changed-etag polls carried new ones (the 17 exceptions include at least one measurement artifact, see below); with etags logged on a later build, about 98% |
| While moving, a fix arrives about every 40 s | The etag alternates changed/unchanged on 20 s fast polls. Longest unchanged run while someone was moving: 61 s (member A, 2026-11-03 21:38:24 to 21:39:25). Every other moving run was 40 s or less; member C's 18:15 to 18:35 drive never exceeded 3 polls |
| A stopped-but-flagged phone goes quiet | Member A's parks at 22:12:01 and 2026-11-04 05:40:22: Life360 still said `inTransit:true` and the etag had been unchanged for 341 s and 262 s. Member B's four parks and member A's 21:57 park were cleared by Life360 itself |
| Savings | 22:30 to 05:00: 529 member polls skipped, 121 reached the driver (81% skipped). Member A's 75-minute drive (20:42 to 21:57): 145 of 253 polls came back unchanged, each of which used to run the JSON parse and the driver |
| Margin | Window = a flat 240 s, about 4× the 61 s worst moving gap, at every refresh rate. A window tied to the refresh rate would be only 20 to 60 s at the 10, 15 and 30 s settings, shorter than the usual 40 s between fixes; 240 s also keeps a short dead stretch (a tunnel) from flipping `isMoving` |

**Observed on the hub with the rule in place** (2026-11-04 to 2026-11-05, five members, about 30 drive/park
cycles):

- The stop rule fired 11 times, each on a phone Life360 still called `inTransit:true` (a parked phone, or one
  flagged on a single fix at speed 0), once the etag had been unchanged for the whole window (the next 20 s poll
  after it). The fast chain ended on the next poll and `isMoving` went false while `inTransit` stayed true.
- Every other stop was cleared by Life360 itself first; the rule was not needed.
- No false stop during real driving. The longest gap between fixes while driving was 60 to 64 s.
- Life360 sometimes sets `inTransit:true` again for a few minutes at speed 0 and the same position, then clears
  it. That is Life360's own flicker, not the rule.
- About 1 to 2% of changed-etag polls are false positives (below).

**False positives** (a changed etag with an unchanged location timestamp). The app logs
`fetchMemberLocation: FALSE POSITIVE: etag changed but location timestamp did not (<timestamp>)` at debug.
Cost: one extra driver call whose data matches the previous one; the tile's last-updated time moves. Nothing
else is affected, and the rule treats it as new data (it only ever delays a stop).

- In every case the driver's debug line (position, accuracy, battery, wifi, speed, flags) was identical to
  the previous call. One earlier case had a fully logged raw payload, byte-for-byte identical.
- Some followed a real change for the same member by 0.4 s or 2 s (the slow tick and a fast-chain tick landing
  together); the rest were on parked phones one poll (3 min) after the poll that delivered the fix.
- With etags logged (about 2% of changed-etag polls): the new etag
  differs from both the one sent and the one stored, so Life360 really did change it; a missing or
  differently-cased header is ruled out.
- Of the 13 checked, all came either one slow poll (180 s) after the poll that delivered the fix, or 2 s after it
  (a second request in the same tick). The 180 s cases are clean: the request sent the stored etag and got a
  different one back with an identical payload, so Life360 sometimes issues a second etag for the same fix. The
  2 s cases are unexplained: the logged sent and stored etags were equal although the response 2 s earlier
  should already have stored a new one (a state-write race is possible). It is not a movement, battery or
  signal effect: phones were parked, moving and at home in bed alike.
- Decision: leave it. Suppressing it would put a "did the timestamp move" check back in the app, which is
  the extra logic this design removed, to save one driver call in about 50.

**Why not position.** Comparing GPS positions against an anchor would need the driver on every moving poll, a
per-device memory anchor, haversine and an accuracy slack, and still couldn't tell a stationary phone from one
whose fixes jitter inside the slack. The etag already says "Life360 has nothing new", costs one string compare, and lets the
app skip the driver for moving members too.

**Known limits** (documented so nobody rediscovers them):

- Fixes that keep arriving while Life360 still says `inTransit` (jitter) reset the clock, so the stop comes
  later (member A: a fix inside the 50 m accuracy circle during the stop delayed it about 1.5 min).
- If Life360 stops sending `l360-etag`, every poll reaches the driver and the stop rule never fires (a stuck
  member fast-polls as it did before this design). The app logs a WARN, at most once per roster-refresh
  interval, so the condition is visible.
- Done, Refresh, Fetch Locations, Fetch Places and a device preference save drop the etags, which restarts a
  stuck member's window once.
- A phone that stops reporting while genuinely moving looks identical to a stopped one, for any rule. Seen:
  Member C walking 2026-11-03 19:12 to 19:18; Life360 said `inTransit:true` at a frozen 1.72 mph, the etag was
  unchanged for 6 min 20 s, and 20 s polls returned the identical payload. `isMoving` goes false; fast
  polling had nothing to fetch.
- Life360 under-reports walking: over a 30-minute walk the etag changed about every 6 to 9 minutes and
  `inTransit` was never set outside that frozen stretch. No polling strategy here can fix that.
- Untested: a phone that goes quiet in a tunnel while driving. A gap longer than 240 s without a fix can
  briefly flip `isMoving` false, then true on the next fix.
- Life360's member-level `activity` field (`vehicle`/`unknown`, with its own timestamp) exists in raw
  payloads but lagged (still `vehicle` while parked with `inTransit:false`). No `isWalking` / `isFlying` /
  `isBicycling` field appears in the saved API docs or captured payloads. It drives nothing.

**Suggested follow-up (not done here): fewer polling choices.** Fixes arrive 20 to 60 s apart while driving
(403 consecutive moving fixes, binned to the 20 s poll grid: about 18% at 20 s, 68% at 40 s, 13% at 60 s, none
over about 70 s; 20 s is the floor because that is the fast-poll interval). Dynamic Refresh Rate offers 5, 10, 20 and 30 s and Default Refresh
Rate offers 10 and 15 s; polling every 5 or 10 s asks Life360 four to eight times per fix, almost all
unchanged, and raises the 429 risk with several movers. Dropping the 5 and 10 s dynamic choices (and likely
the 10 and 15 s default choices) would lose nothing measurable. Saved values outside a shorter menu keep
working, since `pollFreqSecs()` reads the setting directly. Left alone because it changes settings users may
have chosen and is the maintainer's call.

**Reproduce.** The hub keeps a rolling log buffer: `curl http://<hub>/logs/past/json` returns a JSON array,
oldest first, about 75 entries/min with all devices on debug, so pull at least every 15 min (pull, filter to
the app and Life360 devices, append, `sort -u`). Add a temporary line in the `200` branch before the unchanged
test, `log.info("ETAG-PROBE ${memberName}: same:${eTag == prevTag} inTransit:${isMemberInTransit(memberId)}
etag:${eTag}")`, and turn on Log Raw Payload to see timestamps. A run of `same:true` with `inTransit:true`
is a candidate stop; compare its length with the window.

---

## 14. Platform gotchas worth keeping

- **No computed method names** (`log."${level}"(…)`, `obj."${name}"()`): the sandbox rejects them at compile
  time and the whole app or driver fails to install. Dispatch with a `switch`.
- **Groovy `long / long` is integer division.** `LAT_LNG_PRECISION` is a `long`, so
  `Math.round(x * P) / P` truncated coordinates to whole degrees. Divide by `(P as double)`.
- **`if (object)` treats `0` as false.** Use `object != null` in `toDouble`.
- **Enum settings may arrive as `Integer`.** Use `settings.pollFreq?.toString() ?: "60"` before `.toInteger()`.
- **Async headers Map is case-sensitive**; HTTP header names aren't. Search case-insensitively.
- **`response.getErrorMessage()` can be null**; fall back to `"(no details)"` so logs don't print `"null"`.
- **Pass booleans to `sendEvent` as `.toString()`**, and convert Life360's `"1"`/`"0"` with `toBool` first
  (a raw `"1"` made `shareLocation == "true"` rules never match).
- **`contact`, `acceleration` and `switch` must be declared `enum`** for Hubitat's dashboard tiles to bind.
- **`unschedule()` with no argument cancels every job**, including the token-expiry reminder (§4.5, §5).
- **`since` can be absent**, and an epoch needs `new Date(sEpoch * 1000L)` (not a duration).
- **`Retry-After` comes from the async headers Map**, not `getFirstHeader`.
- **`ctx` is shared by every member in a tick**; copy it (`ctx + [...]`) before adding a per-member key.
