# Life360+ — Clean-Room App Specification

> **Purpose.** This document specifies the Life360+ Hubitat integration in enough detail to
> **recreate it from scratch**. It describes the app and driver *as they are*, not a design under
> debate. The code in `life360_app.groovy` / `life360_driver.groovy` is the final authority; this
> spec is the blueprint. Companion docs: [STATE_REFERENCE.md](STATE_REFERENCE.md) (every
> setting/state var/attribute), [CHANGES_TECHNICAL.md](CHANGES_TECHNICAL.md) (delta from the
> upstream baseline), [CHANGELOG.md](CHANGELOG.md) (release history).

The integration tracks the members of a Life360 circle as Hubitat presence/location devices. Two
files installed on the hub: `life360_app.groovy` (parent app — talks to Life360, owns scheduling and
child devices) and `life360_driver.groovy` (one child device per tracked member, exposing location
and phone state as Hubitat attributes).

There is no public Life360 API. Every byte on the wire was reverse-engineered, and the integration
has gone completely dark several times when Life360 changed their backend. The cookie/Cloudflare path
(§0) is the single most fragile part. The design goal is the **least fragile, fewest-moving-parts**
implementation that still does the job.

---

## 0. The one rule that overrides everything: DO NOT BREAK THE COOKIE PATH

> The integration has been **completely dead** several times when Life360 changed their backend; the
> most recent rescue was the Cloudflare cookie handling. The wire-level behavior below is a
> **known-working configuration**. Treat the bytes on the wire — URL, headers, cookie capture/replay
> — as load-bearing. Anything may be restructured *around* it, but the wire contract must stay
> byte-for-byte identical to what is specified here.

### 0.1 The exact wire contract

| Element | Required value | Why |
| --- | --- | --- |
| Base URL | `https://api-cloudfront.life360.com/v{version}` | The cloudfront host is what works. `api.life360.com` is a documented fallback only (commented in `life360BaseUrl`). |
| API version | `3` for everything **except** the setup-only "Fetch Circles" button, which uses `4` | v4 `/circles` returns richer circle data for the picker, but its list response reports `memberCount:"0"`, so the membership poll must stay on v3. |
| `Accept` | `application/json` | — |
| `cache-control` | `no-cache` | — |
| `User-Agent` | `com.life360.android.safetymapd/KOKO/23.50.0 android/13` | Exact mobile-app fingerprint. An alternate fingerprint is kept commented in `getHttpHeaders` as a fallback. |
| `Authorization` | `Bearer <access_token>` | — |
| **No other headers** | — | Extra headers (e.g. `X-Application`, `circleid`) have triggered 403s. Do not add any unless a future break forces it. |
| `Cookie` | replayed from the captured jar | **The Cloudflare gate.** See §0.2. |

`life360BaseUrl(int version = 3)` builds the base URL; `life360Params(String path, int version = 3)`
returns `[uri, headers: getHttpHeaders(), timeout: 30]`. The `Cookie` header is added by callers from
`state.cookies` when present.

### 0.2 Cookie capture/replay — the load-bearing mechanism

- Cloudflare sets two cookies: `__cf_bm` (bot-management, rotates ~every 30 min) and `_cfuvid`
  (stable). **Without `__cf_bm` you get 403 within minutes.** This is the single most important
  behavior in the whole integration.
- **Capture cookies on EVERY response** — circles, members, location, force-update, `/users/me`,
  token probe, everything. Both the sync path (`captureCookies`, used by `httpGet` calls) and the
  async path (`captureCookiesAsync`, used by `asynchttpGet`/`asynchttpPost` callbacks) must run.
- **Replay the accumulated jar on EVERY request** via the `Cookie` header.
- **The jar is a name→value upsert, never a whole-jar overwrite.** `mergeCookie(cookieVal)` parses
  `state.cookies` into a `name→value` map, upserts the one incoming cookie by name, and
  re-serializes. A rotating `__cf_bm` updates only its own entry and `_cfuvid` survives. Both capture
  paths call `mergeCookie()`. (Overwriting the jar on rotation silently drops `_cfuvid` and causes a
  delayed Cloudflare 403 — the failure that has killed the integration before.)
- The cookie value is taken as the first segment before `;` or `,`:
  `it.value?.tokenize(';|,')?.getAt(0)`. This is correct — `__cf_bm` is base64-url-safe (no commas)
  and `;` is always the first separator. A well-formed `name=value` is required before upserting.

`cookieJarSummary()` logs cookie **names** only by default (safe to always log), optionally with
8-char value heads under `logRawPayload`. It is logged on auth failure (`jar-at-failure [...]`) so a
token problem (jar intact) can be told apart from a cookie-path problem (jar missing a Cloudflare
cookie).

> **Soak-test requirement:** any change to §0.1/§0.2 must be tested against a *live circle for at
> least several hours* — Cloudflare 403s appear minutes-to-hours after a bad change, not immediately.

---

## 1. Design principles

1. **Per-member + etag location loop.** Each selected member is fetched with
   `GET /circles/{id}/members/{mid}` carrying that member's `If-None-Match`. An unchanged member
   returns a near-free `304`; only movers return a `200` body. A **separate, slow** v3 `GET /circles`
   poll detects roster changes via `memberCount` and triggers a membership/name/avatar refresh
   (§4.2).
2. **Async, with a per-member in-flight guard.** Every per-member fetch is `asynchttpGet`, so a
   slow/down Life360 never blocks the hub scheduler thread. A per-member in-flight marker prevents a
   second request for a member whose prior request is still outstanding — a slow service skips that
   member's tick rather than stacking requests.
3. **Trust the payload — don't outsmart Life360.** Pass `inTransit` / `isDriving` and the rest of the
   `location` object straight through. No GPS-jitter heuristics, no "the API is probably wrong so
   correct it" logic. The only sanctioned override is the user's explicit manual speed thresholds
   (`transitThreshold` / `drivingThreshold`). If part of Life360's API is genuinely broken, Life360
   fixes it upstream — the client does not accrete more workarounds.
4. **Force-update for freshness, not inference.** When a fresh fix is genuinely needed, *ask the
   phone* (§6) instead of inferring movement from noisy data.
5. **Fail loud, fail safe.** On auth failure: clear session, flag the token, slow polling, notify
   once. Never hammer a dead endpoint; never silently spin.
6. **Read-only and unobtrusive.** No webhooks (disabled by Life360); no writes except the
   force-update request; minimal state; minimal scheduler churn.
7. **PII-aware logging.** The integration logs member names, coordinates, addresses, phones, and
   emails. Two orthogonal control axes — verbosity (`logEnable`, `logRawPayload`) and PII redaction
   (`logShowNames`, `logShowMapsLink`) — let a user run debug logging and still share logs safely
   (§10).
8. **Device-facing backward compatibility is non-negotiable.** Existing users have Rule Machine
   rules, dashboards, and webCoRE pistons bound to the child-device attributes, capabilities, and
   commands. Every device-facing name and value is frozen (§7.3); improve *behind* the contract,
   never by changing it.

---

## 2. Architecture

```
┌──────────────────────────── Hubitat Hub ────────────────────────────┐
│                                                                      │
│  Life360+ App (parent)                                               │
│   • holds access_token, circle id, home place id, member selection   │
│   • schedule(pollFreq) ──► handleTimerFired() each tick:             │
│   •     watchdog → circles poll (≤1/min) → fetchLocations()          │
│   •   fetchLocations(): for each member → asynchttpGet member+etag   │
│   •     handleMemberLocationResponse: 200 → update child; 304 → noop  │
│   •   handleCirclesPollResponse: memberCount diff → fetchMembers()   │
│   •     handleMembersResponse: refresh state.members + reconcile      │
│   •       child devices (create/remove) + push name/avatar/location   │
│   • forceMemberUpdate(memberId): POST …/request  (manual button)     │
│   • /view endpoint: all-members map (OSM default, Google optional)   │
│   • capture cookies on EVERY response (load-bearing, §0)             │
│                                                                      │
│   ├── Child device: Life360+ Driver (member A)                       │
│   ├── Child device: Life360+ Driver (member B)                       │
│   └── …                                                              │
└──────────────────────────────────────────────────────────────────────┘
```

Two files: `life360_app.groovy` (parent) and `life360_driver.groovy` (child). Two cadences: a **fast
per-member location loop** and a **slow membership refresh**, both driven off the single
`handleTimerFired` scheduled job.

---

## 3. API surface

Base path `https://api-cloudfront.life360.com/v{version}`.

### 3.1 Steady state

| Purpose | Method + path | Version | Notes |
| --- | --- | --- | --- |
| **Poll one member's location** | `GET /circles/{circle}/members/{member}` | 3 | **Core location call.** Per member, with that member's `If-None-Match`. `200` = changed (full member + `location`); `304` = unchanged (near-free). One per selected member per tick. |
| **Detect roster changes** | `GET /circles` | 3 | **Slow cadence (≤1/min).** Returns all circles; the selected circle's `memberCount` is compared against `state.memberCount`. A change triggers `fetchMembers()`. v3 because v4's list response reports `memberCount:"0"`. |
| **Refresh membership / names** | `GET /circles/{circle}/members` | 3 | Fired by `fetchMembers()` when the roster changes (or on first baseline). Refreshes `state.members`, reconciles child devices, pushes refreshed name/avatar/location. |
| Force fresh GPS fix | `POST /circles/{circle}/members/{member}/request` | 3 | Body `{"type":"location"}`, `Content-Type: application/json`. Returns `{requestId, isPollable}`. Cookies required (Cloudflare). |

### 3.2 Setup / on demand

| Purpose | Method + path | Version | Notes |
| --- | --- | --- | --- |
| Validate token | `GET /users/me` | 3 | Cheap. Run on **Check Token**, on `installed()`/`updated()` (to learn the units preference), and on each 5-min tick while the token is flagged expired (auto-recovery probe). |
| List circles | `GET /circles` | **4** | Setup only — the "Fetch Circles" button populates the circle picker. v4 for richer circle data. |
| List places | `GET /circles/{circle}/places` | 3 | Setup only — populate the HOME picker. |

### 3.3 Explicitly NOT used

- `POST /oauth2/token.json` (username/password login). The app takes a **manually-pasted bearer
  token, never credentials** — pasting a token captured from a real signed-in session is the ONLY
  known method that works (Life360's programmatic login is captcha/MFA-gated). See §3.5.
- Webhooks / push subscriptions — disabled by Life360 (dead code left commented).
- Any V5/activity endpoints — `userActivity` is parsed if present in a v3 payload, but never
  requested.

### 3.4 Key response shapes

`GET /circles/{circle}/members/{member}` (and the embedded `members[]` in the list endpoints) →
member object with:

- `id`, `firstName`, `lastName`, `avatar`, `communications[]` (`{channel: Voice|Email, value}`),
  `features.shareLocation`, and `location`:
  - `latitude`, `longitude`, `accuracy` (strings)
  - `battery` (%), `charge` (`"0"`/`"1"`), `wifiState` (`"0"`/`"1"`)
  - `speed` (number; iPhone can report `-1` → treat as 0)
  - `inTransit` (`"0"`/`"1"`), `isDriving` (`"0"`/`"1"`)
  - `since` (epoch seconds; **may be absent** — null-guard it)
  - `name` / `address1` / `address2` / `shortaddress` (place/address labels)
  - `userActivity` (often blank on v3; `os_` prefix stripped when present)

`GET /circles` → `{circles: [{id, name, memberCount (string), …}]}`. The membership poll plucks the
selected circle by id and reads `memberCount`.

> All booleans arrive as stringy `"0"`/`"1"`; all numbers may arrive as strings. Parse defensively
> with the driver's null-safe `toDouble` / `toBool` helpers.

### 3.5 Token acquisition — the ONLY known working method (PRESERVE VERBATIM)

> **Load-bearing, like the cookie path.** There is no supported programmatic login. The integration
> only works with a bearer token lifted from a browser session already authenticated to Life360.
> These directions must survive in `README.md` (Setup) and as a short form in the app's **STEP 1**
> help text.

1. In a desktop browser, go to `https://www.life360.com` and **sign in** (complete any MFA). Stay on
   the logged-in page.
2. Open **Developer Tools** (F12 / right-click → Inspect) → **Network** tab.
3. Reload / navigate so API requests appear; filter for `life360.com` (e.g. `…/v3/circles`).
4. Click an API request → **Request Headers** → find `Authorization: Bearer <long-token>`.
5. Copy **only the token** — the string after `Bearer ` (no `Bearer`, no quotes/spaces).
6. Paste into the app's **STEP 1: Access Token** field and click **Check Token** (calls
   `GET /users/me`; a `200` with your name confirms it's valid).

Notes to keep alongside the steps:

- The token is a **secret credential** equivalent to a Life360 login — treat it like a password.
- Tokens expire/get revoked server-side (no fixed lifetime). On a `tokenLikelyExpired` flag (3×
  consecutive `401`/`403`), repeat the procedure and re-paste; clicking **Done** clears the flag.
- The token and the cookie jar (§0.2) are two independent gates; both must be valid for a request to
  succeed.

---

## 4. The two loops

`handleTimerFired` is the single scheduled handler. Each tick, in order:

1. **Watchdog** — if `lastSuccessMs` is older than `max(pollFreq×10, 10min)` and the token isn't
   flagged, log `WATCHDOG` once on the rising edge (`state.watchdogWarned`) and set a banner.
2. **Circles poll** — at most once per minute (`state.lastCirclesFetchMs`), fire an async v3
   `GET /circles` → `handleCirclesPollResponse` (§4.2).
3. **Locations** — if `tokenLikelyExpired`, fire `probeTokenAfterExpiry()` (§5) and return; otherwise
   call `fetchLocations()` (§4.1).

### 4.1 `fetchLocations()` — the fast per-member location loop

```
fetchLocations():
  if circle unset OR no members selected: return false
  if rateLimitedUntilMs in future: return false       # honor prior 429
  if tokenLikelyExpired: return false                  # don't hammer a dead token
  ctx = buildPlacesContext()                           # serialize places ONCE per tick (§4.5)
  for memberId in settings.users:
    fetchMemberLocation(memberId, ctx)                 # fires one asynchttpGet each
  return true

fetchMemberLocation(memberId, ctx):
  now = nowMs()
  httpTimeout = clamp(pollIntervalSecs, 5, 30)
  # per-member in-flight guard — don't stack a 2nd request for a member still pending
  if inflight[memberId] > 0 AND (now - inflight[memberId]) < (httpTimeout + 2)s: return
  # per-member transient backoff — skip if still inside this member's backoff window
  if transientUntilMs[memberId] > now: return
  inflight[memberId] = now
  params = life360Params("/circles/${circle}/members/${memberId}")
  params.timeout = httpTimeout
  if cookies: params.headers["Cookie"] = cookies
  if etag[memberId]: params.headers["If-None-Match"] = etag[memberId]
  asynchttpGet("handleMemberLocationResponse", params, [memberId, ctx])

handleMemberLocationResponse(response, data):
  inflight.remove(memberId)                            # clear guard FIRST
  status = response.status                             # null/0 = network-level failure
  if !status: onTransientFailure(memberId); return     # per-member backoff (§5)
  captureCookiesAsync(response)                        # EVERY response (§0)
  switch(status):
    200: notifyChildDevice(memberId, response.json, ctx)
         clear failCount/tokenLikelyExpired/rateLimited; clear this member's backoff
         bump lastSuccessMs; clear message/watchdog
         store etag[memberId] from the (case-insensitive) "l360-etag" header
    304: clear this member's backoff; bump lastSuccessMs; clear message/watchdog
    401/403: onAuthFailure()                            # §5
    429: backoff(Retry-After)                           # global rate-limit window, §5
    502/503/504/520/522/525: onTransientFailure(memberId)   # per-member backoff, §5
    else: log error + set message
```

Notes:

- **Per-member etag is the whole efficiency story.** Send `If-None-Match` per member unconditionally;
  trust the result. A stationary member returns `304` (no body, no work). The etag header is read
  **case-insensitively** (`response.headers?.find { it.key?.equalsIgnoreCase("l360-etag") }?.value`)
  because the async headers Map is case-sensitive while HTTP header names are not.
- **Dynamic-polling flags are fresh.** Each member's `inTransit` flag is written in
  `notifyChildDevice`; the rate switch is evaluated there (inline, on a transit-state flip), not in
  `fetchLocations()` which runs before the async responses land.
- **`buildPlacesContext()` once per tick.** Serialize `placesJson` a single time (it's identical for
  every member) and thread it through every `fetchMemberLocation` → `notifyChildDevice` (§4.5).
- **The "polling mode" debug line** is logged inside `fetchLocations()` after all early-return guards,
  so it doesn't fire on no-op ticks.

### 4.2 Membership refresh — the slow loop

```
# in handleTimerFired, rate-limited to ≤once/min via state.lastCirclesFetchMs
asynchttpGet("handleCirclesPollResponse", life360Params("/circles") + cookies)   # v3

handleCirclesPollResponse(response):
  if !status: log + return
  captureCookiesAsync(response)
  if status != 200: return
  circle = response.json.circles.find { it.id == settings.circle }
  if !circle: return
  newCount = (circle.memberCount ?: "0").toInteger()
  if state.memberCount == null:                  # first baseline
    state.memberCount = newCount
    if state.members empty: fetchMembers()        # initial fetch
  elif newCount != state.memberCount:             # roster changed
    state.memberCount = newCount
    fetchMembers()

fetchMembers():                                   # async GET /circles/{id}/members (v3)
  asynchttpGet("handleMembersResponse", life360Params("/circles/${circle}/members"))

handleMembersResponse(response):
  if !status: log + return
  captureCookiesAsync(response)
  if status == 200:
    state.members = response.json.members
    createChildDevices()                          # create new / remove orphaned (§4.4)
    for memberId in settings.users:               # push refreshed name/avatar/location
      m = state.members.find { it.id == memberId }
      if m?.location: notifyChildDevice(memberId, m)
```

This is the integration's membership auto-sync: when the circle's member count changes, the roster is
re-fetched, devices are reconciled, and existing devices get refreshed identity/location — without the
user reopening the app.

### 4.3 Dynamic polling (optional)

Bumps the location-loop rate while any selected member is moving; it does not change the per-member
structure (every member is still fetched with its own etag, just more often). It exists so a
slow-standard-poll user (e.g. 3-min default) gets faster updates during an actual trip while keeping
near-zero load when idle.

- `dynamicPolling()` sets `state.memberInTransit` from the per-member `inTransit-<id>` flags, then
  calls `scheduleUpdates()` only on a STANDARD↔DYNAMIC transition.
- The rate switches to `dynamicPollFreq` while any member is in transit, else `pollFreq`.
- **Engages only when `pollFreq > dynamicPollFreq`** — no point "speeding up" to a slower rate.
- The transit-flip evaluation happens inline in `notifyChildDevice` (using the just-written flag), so
  the rate change lands the same tick instead of lagging a cycle.

### 4.4 Child-device reconciliation — `createChildDevices()`

Called from `installed()`, `updated()`, and `handleMembersResponse` (on a 200). Guards on
`settings.users` and `state.members` both non-empty.

- Hoist `getChildDevices()` **once** into a `dni → device` map; use it for both creation and orphan
  cleanup (avoids an O(N²) hub device-list walk).
- For each selected member without a device: look it up in `state.members`; if missing, `log.warn` and
  skip (no NPE); otherwise `addChildDevice("jpage4500", "Life360+ Driver", "${app.id}.${memberId}",
  null, [...])` — **`null` hub id**, never a hardcoded value.
- Orphan cleanup: delete any child whose DNI is not `"${app.id}.${memberId}"` for a currently-selected
  member.

### 4.5 Scheduler hygiene — `scheduleUpdates()`

- Compute `baseSecs` from `pollFreq` (or `dynamicPollFreq` when dynamic + a member in transit + the
  `pollFreq > dynamicPollFreq` condition). Forced to **300** when `tokenLikelyExpired`. `pollFreq` and
  `dynamicPollFreq` are read null-safely (`(settings.pollFreq ?: "60")` etc.) so a fresh install before
  the first **Done** can't NPE.
- **No-op guard:** track `state.scheduledBaseSecs`; skip the `unschedule()` + `schedule()` rebuild when
  the rate hasn't changed and a schedule already exists. Reset to `null` by `installed`/`updated`/
  `initialize` to force a re-arm. Prevents the triple-fire churn when called from several paths in one
  cycle.
- `baseSecs <= 0` (Disabled) → `unschedule()` and return; polling off.
- `baseSecs < 60` → `schedule("0/${baseSecs} * * * * ? *", handleTimerFired)`; else
  `schedule("0 */${baseSecs/60} * * * ? *", handleTimerFired)`.
- No jitter/randomness — the cron owns the rate.
- Re-arm on hub reboot via `subscribe(location, 'systemStart', initialize)`.

---

## 5. Error handling & health

Each member fetch is judged independently; a small set of **account-level** flags is shared across all
members (an auth failure or 429 affects every member).

| Condition (per member) | Action |
| --- | --- |
| `200` | `notifyChildDevice`; store member etag; clear `failCount`/`tokenLikelyExpired`/`rateLimitedUntilMs` and this member's backoff; bump `lastSuccessMs`; clear `message`/watchdog |
| `304` | clear this member's backoff; bump `lastSuccessMs`; clear `message`/watchdog |
| `401`/`403` | **account-level:** `clearSessionCache()` (drop cookies + all etags + inflight/backoff); `failCount++`; at `failCount >= 3` set `tokenLikelyExpired`, notify once, `scheduleUpdates()` (slows loop to 300s). Banner text names a re-paste; below 3, a transient "will retry" banner. |
| `429` | **account-level:** `rateLimitedUntilMs = now + (Retry-After ?: 60) + 10s` — pauses the whole loop. Transient banner. |
| `502`/`503`/`504`/`520`/`522`/`525` / network error (no status) | **per-member** exponential backoff (below) |

**Per-member transient backoff.** Each member tracks `transientCount-<id>` and
`transientUntilMs-<id>`:

- On any of the transient statuses above, or a network-level failure (no HTTP status):
  `transientCount-<id>++`; `transientUntilMs-<id> = now + min(pollSecs * 2^(count-1), 300s)`. The
  shift exponent is capped at 6 (`min(count-1, 6)`) so the value can't overflow `long` before the
  `Math.min` clamp. `fetchMemberLocation` skips a member still inside its window — a flaky single
  member backs off without penalizing the healthy ones.
- Any `200`/`304` for that member clears both keys.

**Per-member in-flight guard.** `inflight-<id>` is set before `asynchttpGet` and cleared first thing in
the callback; `fetchMemberLocation` skips a member whose prior request is still outstanding
(`now - inflight[id] < (httpTimeout + 2)s`). Prevents pile-up when Life360 is slow.

**Account-level token recovery.** While `tokenLikelyExpired`, the loop runs at 300s and each tick fires
`probeTokenAfterExpiry()` → async `GET /users/me` → `handleTokenProbeResponse`:

- `200`: service healed — clear `tokenLikelyExpired`/`failCount`/`rateLimitedUntilMs`/`message`, cancel
  the reminder chain, `scheduleUpdates()` to restore the normal rate. (Auto-recovery — no user action
  needed if the cause was a transient blip rather than a real expiry.)
- `401`/`403`: still dead — stay quiet, retry next tick.
- other/network: stay quiet, retry next tick.

**`handleException(tag, e)`** classifies synchronous `httpGet`/`httpPost` exceptions
(`TIMEOUT`/`AUTH`/`RATE_LIMIT`/`TRANSIENT`/`OTHER`) and applies the same account-level state updates;
used by the synchronous setup calls (`fetchCircles`, `fetchPlaces`).

**Watchdog.** `lastSuccessMs` is the newest success across all members. If it's older than
`max(pollFreq×10, 10min)` and the token isn't flagged, log `WATCHDOG` once on the rising edge and set a
banner; both clear on the next success.

**Token-expiry notification.** `notifyTokenExpired()` fires once on the rising edge to
`settings.notifyDevices` (gated by the `notifyTokenExpiry` master toggle). `scheduleTokenExpiryReminder()`
/ `sendTokenExpiryReminder()` implement an optional repeat (Never/2/6/12/24/48h), self-rescheduling
while `tokenLikelyExpired` stays true; the chain is cancelled in `updated()` and on auto-recovery.

**`clearSessionCache()`** removes `cookies` and all per-member `etag-*`, `inflight-*`,
`transientCount-*`, `transientUntilMs-*` keys — a clean slate on auth failure.

**Banner copy.** Self-resolving conditions (429, transient 5xx, sub-threshold auth) use lowercase
"will retry automatically" wording so the red banner reads as recoverable, not fatal.

---

## 6. Force-update

```
forceMemberUpdate(memberId):
  url  = ".../v3/circles/${circle}/members/${memberId}/request"
  body = JsonOutput.toJson([type: "location"])     # EXACT payload — wrong/empty body → 400
  params.contentType = "application/json"
  params.body        = body
  params.headers["Cookie"] = state.cookies          # REQUIRED — Cloudflare 403s without cookies
  asynchttpPost("handleForceUpdateResponse", params, [memberId])

handleForceUpdateResponse:
  if !status: report network error; return
  captureCookiesAsync(response)
  if 200: log requestId/isPollable; runIn(6, "fetchLocations")   # pick up the fresh fix next loop
  else: report failure
```

Two things that previously made this fail (now correct — do not regress):

1. Body must be exactly `{"type":"location"}` with `Content-Type: application/json`.
2. Cookies must be forwarded — the `__cf_bm`/`_cfuvid` jar is the Cloudflare gate.

**Surface:** a manual member-dropdown + **Force Update** button in app settings, gated on
`!isEmpty(settings.users)`. No auto-trigger. Result shown via the one-shot `forceUpdateStatus`
pattern. The status dropdown resets after firing (`app.removeSetting("forceUpdateMember")`).

> **Known gap (accepted):** `forceMemberUpdate()` does not guard on `tokenLikelyExpired` /
> `rateLimitedUntilMs` before posting. It's a manual one-shot, so an occasional doomed POST against a
> struggling service is harmless — noted rather than worked around.

---

## 7. Driver (child device)

The driver is the stable part; nearly all change is app-side. Entry point:
`generatePresenceEvent(member, placesJson, home)` → returns a boolean `inTransit` the app reads.

- Null-safe: returns `false` early if `member` or `member.location` is null, or if `home` is null
  (a stale `settings.place` whose Life360 place was removed) — with a `log.warn`, never an NPE.
- Null-safe `since` (`location.since?.toLong() ?: 0L`).
- Parse with the static `toDouble`/`toBool` helpers (stringy `"0"`/`"1"`, numbers-as-strings).
- Units: speed/distance follow the account's `unitOfMeasure` via `parent?.getUnitIsMiles()`; the local
  `isMiles` toggle is a fallback used only until that is known.
- Motion: trust Life360's `inTransit`/`isDriving`; the manual `transitThreshold`/`drivingThreshold`
  override only when set non-zero (compared against `speedUnits` in display units, null-safe).
- The "moving @ N mph/kph" info log fires only when `inTransit || isDriving`, and includes a Google
  Maps link only when `parent?.getShowMapsLink()` is true.
- HTML tile (only when `generateHtml`): uses `long` epoch for `since` (Y2K38-safe), `avatarFontSize`,
  and renders speed units correctly.
- Distance math: `haversine()` is `static` (pure math).

### 7.1 Presence logic

`present` iff `haversine(member, home) <= home.radius`. Inside the radius ⇒ `address1 = "Home"`;
missing place/address ⇒ `"No Data"`. `status` is `"At Home"` or `"<x.x> miles from Home"` /
`"<x.x> km from Home"` (note the leading space in the metric branch).

### 7.2 Capability repurposing (document loudly — README + inline)

| Standard capability | Real meaning |
| --- | --- |
| `Switch` on/off | WiFi connected / not |
| `Contact Sensor` open/closed | Charging / on battery |
| `Acceleration Sensor` active/inactive | In transit OR driving OR away from home |

### 7.3 Backward-compatibility contract — DO NOT CHANGE DEVICE-FACING NAMES OR VALUES

> Load-bearing, like the cookie path (§0) and the token method (§3.5). Users have automations bound to
> these exact attribute names, capability behaviors, command names, and string/enum values. An
> in-place update **must not** rename, retype, or repurpose any of them. New code is fine; renames and
> value changes are forbidden.

**Capabilities (frozen, including the repurposed meanings in §7.2):** `Actuator`, `Presence Sensor`,
`Sensor`, `Refresh`, `Battery`, `Power Source`, `Switch`, `Contact Sensor`, `Acceleration Sensor`.

**Attributes (exact names, types, value vocabularies):**

| Group | Attributes (frozen) |
| --- | --- |
| Presence/power | `presence` (`present`/`not present`), `battery` (0–100), `powerSource` (`DC`/`BTRY`), `switch` (`on`/`off`), `contact` (`open`/`closed`), `acceleration` (`active`/`inactive`) |
| Location | `latitude`, `longitude`, `accuracy`, `address1`, `address1prev`, `lastLocationUpdate`, `lastUpdated`, `since`, `status`, `distance`, `savedPlaces` |
| Motion | `inTransit` (`"true"`/`"false"`), `isDriving` (`"true"`/`"false"`), `speed`, `userActivity` |
| Phone | `charge` (`"true"`/`"false"`), `wifiState` (`"true"`/`"false"`), `shareLocation` |
| Identity | `memberName`, `avatar`, `phone`, `email` |
| HTML / history | `avatarHtml`, `html`, `history`, plus external-app attrs `bpt-history`, `numOfCharacters`, `lastLogMessage`, `lastMap` |

> Subtle semantics to preserve, not "fix": `address1` is the literal `"Home"` inside the home radius
> and `"No Data"` when missing; `lastLocationUpdate` updates only when `address1` changes (`lastUpdated`
> is per-event); booleans are the **strings** `"true"`/`"false"`; `status` is `"At Home"` or
> `"<x.x> miles from Home"`.

**Commands (frozen names + signatures):** `refresh`, `arrived`, `departed`, `sendHistory(string)`,
`sendTheMap(string)`, `historyClearData`. (`arrived`/`departed` force `presence` and are overwritten by
the next poll — intentional. `sendHistory`/`sendTheMap` are called by the external "Life360 Tracker"
app.)

**App ↔ driver bridge (frozen):** parent calls `childDevice.generatePresenceEvent(member, placesJson,
home)` and reads its boolean return. Parent helpers the driver calls: `getShowNamesInLogs`,
`getShowMapsLink`, `getLogRawPayload`, `getLogEnable`, `getUnitIsMiles`, `refresh`. Keeping these
stable lets a new app drive an old installed driver (and vice-versa) during a staged update.

**Device Network ID format (frozen):** `"${app.id}.${memberId}"`. Changing it orphans every existing
child device.

**Driver preferences (frozen names):** `isMiles`, `generateHtml`, `saveHistory`, `transitThreshold`,
`drivingThreshold`, `avatarFontSize`, `avatarSize`, `logEnable`. Renaming any resets that user's saved
choice on update. The same applies to the app's settings vocabulary (§9/§10).

**Where improvement is allowed (behind the contract):** add new attributes/commands (purely additive);
change *how* a value is computed or *how often* it's sent (Hubitat dedupes by value); alias a
badly-named legacy attribute with a clearer new one **in addition to** — never instead of — the legacy
one.

### 7.4 Location history (driver-side, `saveHistory`, default OFF)

When ON, each new lat/lng is appended to `state.locationHistory` (most-recent-first, capped at 100) and
the compact `history` attribute is written:

- Format v1: `"1|s,lat,lng|ds,dlat,dlng|…"` — leading `1` is the version; first entry absolute
  (epoch-seconds + decimal lat/lng); subsequent entries are **signed deltas** (seconds delta, lat/lng
  deltas in units of `0.00001°`). Capped at 1024 chars.
- GPS-jitter filter: entries within ~5 m of the previous emitted point are skipped.
- Lat/lng rounded to 5 decimals (~1 m).

The separate `bpt-history` / `numOfCharacters` / `lastLogMessage` surface (driven by the external
"Life360 Tracker" app via `sendHistory`) is unrelated to `saveHistory`/`history`. `historyClearData`
clears the whole driver-side history surface (both `state.locationHistory` and `state.list1`).

---

## 8. State model

See [STATE_REFERENCE.md](STATE_REFERENCE.md) for the authoritative per-variable table. In brief:

- **Setup cache:** `circles`, `places`, `members`, `accessToken` (OAuth, for `/view` only).
- **Account preference:** `unitOfMeasure` (`"i"`/`"m"`, via `GET /users/me`; overrides each driver's
  `isMiles`).
- **Session:** `cookies`.
- **Per-member:** `etag-<id>`, `inflight-<id>`, `transientCount-<id>`, `transientUntilMs-<id>`,
  `inTransit-<id>`.
- **Scheduling:** `lastSuccessMs`, `scheduledBaseSecs`, `pollIntervalSecs`, `dynamicPollingActive`,
  `memberInTransit`, `lastCirclesFetchMs`, `memberCount`.
- **Account health:** `failCount`, `tokenLikelyExpired`, `rateLimitedUntilMs`, `watchdogWarned`,
  `message`, `tokenStatus`.
- **UI one-shots:** `tokenStatus(+Pending)`, `forceUpdateStatus(+Pending)`.

No client-invented throttle state (e.g. a `lastUpdateMs` "minimum interval" guard) and no dead
generated identifiers — the cron owns the rate, and the wire path carries no client-generated id.

---

## 9. Settings (app preferences)

- **STEP 1 Access Token** — paste bearer, **Check Token** runs `GET /users/me`; the result
  (`tokenStatus`) shows validity inline (a `200` greets you by name; `401`/`403` = expired/revoked).
- **STEP 2 Circle** — "Fetch Circles" (v4 `GET /circles`) → circle picker.
- **STEP 3 Home** — "Fetch Places" (`GET /circles/{id}/places`) → HOME picker.
- **STEP 4 Members** — "Fetch Members" (`GET /circles/{id}/members`) → multi-select.
- **STEP 5 Verify Connectivity** — "Fetch Locations" runs the per-member loop; shows "✓ last
  successful fetch" age.
- **Polling** — `pollFreq` (10/15/30/60/180/300/0=Disabled), `dynamicPolling`, `dynamicPollFreq`.
- **Notifications** — `notifyTokenExpiry` master toggle, `notifyDevices`, `notifyRepeatHours`.
- **Logging** — `logEnable`, `logRawPayload` (verbose, sensitive), `logShowNames`, `logShowMapsLink`.
- **Map View** — optional `googleMapsApiKey` (else OSM), `/view` link + Generate/Revoke buttons.
- **Force Update** — member dropdown + button (gated on a member selection).

Button results that must survive the single page re-render use a one-shot pending flag
(`*StatusPending`) cleared by `mainPage()` on the next open.

---

## 10. Privacy / PII & security

The integration handles PII: real names, exact GPS coordinates, place/home names and addresses, phone
numbers, emails, avatars, and battery/charge/WiFi state — any of which can land in logs users paste
into forum posts.

### 10.1 Logging controls — two orthogonal axes

| Setting | Type | Default | Controls |
| --- | --- | --- | --- |
| `logEnable` | bool | OFF | **Verbosity.** Debug/trace on/off. |
| `logRawPayload` | bool | OFF | **Verbosity + sensitive.** Raw payloads, partial token, partial cookie head, GPS — the full firehose. Labeled sensitive; warn not to share. |
| `logShowNames` | bool | ON | **PII redaction.** ON = names; OFF = opaque UUIDs everywhere (app `getShowNamesInLogs()` + driver `displayMember()`). |
| `logShowMapsLink` | bool | ON | **PII redaction.** ON = "moving" log includes a Maps link to exact coords; OFF = link (and coords) omitted. |

- `logShowNames` OFF must replace *all* names with IDs, not just some.
- `logRawPayload` is the one switch that intentionally logs sensitive data — its description says so.
  Never log the **full** bearer token or **full** cookie jar even behind it — partial/head only.
- Both privacy toggles default ON; on a fresh install (`settings.*` null before the first **Done**),
  the helpers treat null as the documented default.

### 10.2 Other security surfaces

- `/view` URL embeds the OAuth `access_token` — document the leak risk; rotate via the OAuth toggle /
  the in-app **Revoke Map Link** button.
- Google Maps key is visible in page HTML — restrict by HTTP referrer + API in Cloud Console.
- The Life360 bearer token is PII-grade — §3.5 treats it as a secret credential.

---

## 11. Map view (`/view` endpoint)

`renderView` (mapped at `/view`, requires OAuth + `?access_token=<state.accessToken>`) collects every
child device's current `latitude`/`longitude` (skipping null/0,0) plus name, avatar, address, battery,
presence, last-update, and inTransit, serializes them to JSON, and renders a single full-page map:

- **OpenStreetMap (Leaflet)** by default — no API key required.
- **Google Maps** when `googleMapsApiKey` is set.

Both render avatar markers (initial-letter pin fallback), a member-count status line, and a 60-second
auto-refresh.

---

## 12. Recreation checklist

To rebuild from nothing, in dependency order:

1. **Wire contract first (§0).** `life360BaseUrl`, `getHttpHeaders`, `life360Params`,
   `captureCookies`/`captureCookiesAsync` (both via `mergeCookie`), `clearSessionCache` — byte-identical
   on the wire. Verify a single `GET /circles/{id}/members/{mid}` returns 200 and cookies accumulate.
2. **Soak the cookie path** against a live circle for hours — confirm no 403 creep. This gate has
   killed the integration before.
3. **Confirm per-member 304** — `If-None-Match` on an idle member returns `304`.
4. **Fast loop (§4.1):** `handleTimerFired` → `fetchLocations` → `fetchMemberLocation` (etag +
   in-flight guard) → `handleMemberLocationResponse` → `notifyChildDevice` → driver.
5. **Per-member backoff + account gates (§5).** Verify a flaky member backs off alone; 401/429 pause
   the whole loop; auto-recovery probe clears `tokenLikelyExpired` on a 200.
6. **Slow loop (§4.2):** circles poll (v3, ≤1/min) → `memberCount` diff → `fetchMembers` →
   `handleMembersResponse` → `createChildDevices` + name/avatar/location push.
7. **Dynamic polling + scheduler guards (§4.3/§4.5)** and the watchdog.
8. **Force-update (§6)** — manual button.
9. **Map view, notifications, privacy toggles, settings layout (§9–§11).**
10. **Verify the §7.3 contract** before any in-place release — diff the driver's attribute/capability/
    command declarations and both files' preference names against the prior version; new entries must
    be purely additive.

> Hubitat has no local test harness; validation = install on a real hub against a real circle and
> watch logs. §0 (cookies + headers) is the highest-risk surface and the thing to soak-test longest.
