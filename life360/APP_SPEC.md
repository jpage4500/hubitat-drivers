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
| API version | `3` for member/location/place/force-update calls; `4` for all `/circles` fetches (setup and steady-state) | v4 `/circles` returns richer circle data and works for membership polling; v3 is used for the per-member location path and other endpoints. |
| `Accept` | `application/json` | — |
| `cache-control` | `no-cache` | — |
| `User-Agent` | `com.life360.android.safetymapd/KOKO/23.50.0 android/13` | Exact mobile-app fingerprint. An alternate fingerprint is kept commented in `getHttpHeaders` as a fallback. |
| `Authorization` | `Bearer <access_token>` | — |
| **No other headers** | — | Extra headers (e.g. `X-Application`, `circleid`) have triggered 403s. Do not add any unless a future break forces it. |
| `Cookie` | replayed from the captured jar | **The Cloudflare gate.** See §0.2. |

`life360Params(String path, int version = 3)` returns `[uri, headers: getHttpHeaders(), timeout: 30]`.
The `Cookie` header is added by callers from `state.cookies` when present.

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
- The cookie value is taken as the first segment before `;`:
  `it.value?.tokenize(';')?.getAt(0)`. This is correct — `__cf_bm` is base64-url-safe (no commas),
  and `;` is always the attribute separator. Splitting on `,` was wrong and removed. A well-formed
  `name=value` is required before upserting.

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
   returns a near-free `304`; only movers return a `200` body. A **separate, slow** v4 `GET /circles`
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
│   • schedule(pollFreq) ──► handleSlowTimer() each tick:              │
│   •     watchdog → circles poll (≤1/min) → fetchLocations()          │
│   •   fetchLocations(): for each member → asynchttpGet member+etag   │
│   •     handleMemberLocationResponse: 200 → update child; 304 → noop  │
│   •     200 + in-transit → ensureFastChain() (§4.3, if dynamic on)   │
│   •   handleCirclesPollResponse: memberCount diff → fetchMembers()   │
│   •     handleMembersResponse: refresh state.members + reconcile      │
│   •       child devices (create/remove) + push name/avatar/location   │
│   • fastPollMember(): per-member runIn chain for movers (§4.3)       │
│   • forceMemberUpdate(memberId): POST …/request  (manual button)     │
│   • /view endpoint: all-members map (OSM default, Google optional)   │
│   • capture cookies on EVERY response (load-bearing, §0)             │
│                                                                      │
│   ├── Child device: Life360+ Driver (member A)                       │
│   ├── Child device: Life360+ Driver (member B)                       │
│   └── …                                                              │
└──────────────────────────────────────────────────────────────────────┘
```

Two files: `life360_app.groovy` (parent) and `life360_driver.groovy` (child). Three cadences: the
**slow timer** (`handleSlowTimer`) at the user's default poll rate; **per-member fast-poll chains**
(`ensureFastChain`/`fastPollMember`) that fire extra ticks for in-transit members when dynamic
polling is on; and a **slow membership refresh** (circles poll, floored at ≥1/min), all driven from
the single `handleSlowTimer` scheduled job.

---

## 3. API surface

Base path `https://api-cloudfront.life360.com/v{version}`.

### 3.1 Steady state

| Purpose | Method + path | Version | Notes |
| --- | --- | --- | --- |
| **Poll one member's location** | `GET /circles/{circle}/members/{member}` | 3 | **Core location call.** Per member, with that member's `If-None-Match`. `200` = changed (full member + `location`); `304` = unchanged (near-free). One per selected member per tick. |
| **Detect roster changes** | `GET /circles` | 4 | **Slow cadence (≤1/min).** Returns all circles; the selected circle's `memberCount` is compared against `state.memberCount`. A change triggers `fetchMembers()`. Uses `CIRCLES_API_VERSION = 4`. |
| **Refresh membership / names** | `GET /circles/{circle}/members` | 3 | Fired by `fetchMembers()` when the roster changes (or on first baseline). Refreshes `state.members`, reconciles child devices, pushes refreshed name/avatar/location. |
| Force fresh GPS fix | `POST /circles/{circle}/members/{member}/request` | 3 | Body `{"type":"location"}`, `Content-Type: application/json`. Returns `{requestId, isPollable}`. Cookies required (Cloudflare). |

### 3.2 Setup / on demand

| Purpose | Method + path | Version | Notes |
| --- | --- | --- | --- |
| Validate token / get units | `GET /users/me` | 3 | Cheap. Run on **Check Token**, on `installed()`/`updated()` (async, `refreshUserSettings()` — to learn the account's units preference), and on each 5-min tick while the token is flagged expired (auto-recovery probe). |
| List circles | `GET /circles` | **4** | Setup only — the "Fetch Circles" button populates the circle picker. Same `CIRCLES_API_VERSION = 4` constant used for both setup and steady-state. |
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
  - `issues.disconnected` (parsed for heartbeat log; `"1"` or `true` = disconnected)

`GET /circles` → `{circles: [{id, name, memberCount (string), …}]}`. The membership poll plucks the
selected circle by id and reads `memberCount`.

`GET /users/me` → user object with `firstName`, `lastName`, `id`, `loginEmail`, and
`settings.unitOfMeasure` (`"i"` = miles/mph, `"m"` = km/kph). The units field is captured by
`captureUnitOfMeasure()` into `state.unitOfMeasure` and drives `getUnitIsMiles()`.

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

## 4. The three loops

`handleSlowTimer` is the single scheduled handler. Each tick, in order:

1. **Watchdog** — if `lastSuccessMs` is older than `max(pollFreq×10, 10min)` and the token isn't
   flagged, log `WATCHDOG` once on the rising edge (`state.watchdogWarned`) and set a banner.
2. **Circles poll** — at most once per minute (`state.lastCirclesPollMs`), fire an async v4
   `GET /circles` → `handleCirclesPollResponse` (§4.2).
3. **Locations** — if `tokenLikelyExpired`, fire `probeTokenAfterExpiry()` (§5) and return; otherwise
   call `fetchLocations()` (§4.1).

### 4.1 `fetchLocations()` — the slow per-member location loop

Called on each slow-timer tick (and by the `fetchLocationsBtn` button and after a force-update).
Builds a shared `ctx` map and fires one `asynchttpGet` per selected member.

```
fetchLocations():
  if circle unset OR no members selected: return false
  if rateLimitedUntilMs in future: return false       # honor prior 429
  if tokenLikelyExpired: return false                  # don't hammer a dead token
  ctx = buildPlacesContext()                           # serialize places ONCE per tick (§4.5)
  ctx.cookies      = state["cookies"]
  ctx.showNames    = showNamesInLogs()
  ctx.showMapsLink = settings.logShowMapsLink != false
  ctx.useMiles     = getUnitIsMiles()                  # null = not yet known; driver falls back
  ctx.logRawPayload = getLogRawPayload()
  for memberId in settings.users:
    fetchMemberLocation(memberId, ctx)                 # fires one asynchttpGet each
  return true

fetchMemberLocation(memberId, ctx):
  now = nowMs()
  effectiveSecs = (dynamicPolling && member in-transit) ? dynamicPollFreqSecs : pollFreqSecs
  httpTimeout = clamp(effectiveSecs, 5, 30)
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
  if !status: applyTransientBackoff(memberId); return  # per-member backoff (§5)
  captureCookiesAsync(response)                        # EVERY response (§0)
  switch(status):
    200: notifyChildDevice(memberId, response.json, ctx)
         markFetchSuccess(memberId)                    # clear failCount/flags/backoff/watchdog
         store etag[memberId] from case-insensitive "l360-etag" header
    304: markFetchSuccess(memberId)
    401/403: onAuthFailure()                            # §5
    429: backoff(Retry-After)                           # global rate-limit window, §5
    502/503/504/520/522/525: applyTransientBackoff(memberId)   # per-member backoff, §5
    else: log error + set message
```

Notes:

- **Per-member etag is the whole efficiency story.** Send `If-None-Match` per member unconditionally;
  trust the result. A stationary member returns `304` (no body, no work). The etag header is read
  **case-insensitively** (`response.headers?.find { it.key?.equalsIgnoreCase("l360-etag") }?.value`)
  because the async headers Map is case-sensitive while HTTP header names are not.
- **`buildPlacesContext()` once per tick.** Serialize `placesJson` a single time (it's identical for
  every member) and thread it through every `fetchMemberLocation` → `notifyChildDevice` (§4.5).
- **`markFetchSuccess(memberId)`** clears `failCount`, `tokenLikelyExpired`, `rateLimitedUntilMs`,
  `message`, `watchdogWarned`, `lastSuccessMs`, and that member's transient backoff state.

### 4.2 Membership refresh — the slow loop

```
# in handleSlowTimer, rate-limited to ≤once/min via state.lastCirclesPollMs
asynchttpGet("handleCirclesPollResponse", life360Params("/circles", CIRCLES_API_VERSION) + cookies)   # v4

handleCirclesPollResponse(response):
  if !status: log + return
  captureCookiesAsync(response)
  if status == 401/403: log error; return (don't escalate — separate from the member loop)
  if status != 200: return
  circle = response.json.circles.find { it.id == settings.circle }
  if !circle: log.error("configured circle not found"); return
  newCount = (circle.memberCount ?: "0").toInteger()
  prevCount = state.memberCount (null on re-baseline from updated()/initialize())
  state.memberCount = newCount
  countChanged = (prevCount != null && newCount != prevCount)
  if countChanged OR state.members empty: fetchMembers()
  log.info("heartbeat - ${circleName}: memberCount:${newCount}${delta} ${memberStatusSummary()}")

fetchMembers():                                   # async GET /circles/{id}/members (v3)
  asynchttpGet("handleMembersResponse", life360Params("/circles/${circle}/members"))

handleMembersResponse(response):
  if !status: log + return
  captureCookiesAsync(response)
  if status == 200:
    # trim to fields the app needs — keeps state.members small so every exec's deserialize is cheap
    state.members = rawMembers.collect { [id, firstName, lastName, issues.disconnected] }
    syncChildDevices()                            # create new / remove orphaned (§4.4)
    for memberId in settings.users:               # push refreshed name/avatar/location
      m = rawMembers.find { it.id == memberId }   # use rawMembers (untrimmed) for location push
      if m?.location: notifyChildDevice(memberId, m)
```

**Heartbeat log.** `handleCirclesPollResponse` fires a `log.info("heartbeat - ...")` on **every**
successful poll — not just when the count changes. It shows `memberCount`, any delta (`3 → 4`), and
`memberStatusSummary()`: a per-member `name:connected/disconnected` string derived from each
member's `issues.disconnected` flag in `state.members`. This proves polling is alive without
requiring debug logging.

This is the integration's membership auto-sync: when the circle's member count changes, the roster is
re-fetched, devices are reconciled, and existing devices get refreshed identity/location — without the
user reopening the app.

**Not on a mere re-baseline.** `updated()` and `initialize()` null `state.memberCount` so the circles
check fires immediately on the next tick. But `handleCirclesPollResponse` only calls `fetchMembers()`
if `state.members` is already empty OR the count actually changed (not merely on a re-baseline with a
populated roster) — this avoids burning an API call on every Done/reboot when the roster hasn't
changed.

### 4.3 Dynamic polling (optional)

When `dynamicPolling` is enabled and the dynamic rate is faster than the default rate, in-transit
members get **extra polling via per-member `runIn` chains** rather than a global rate switch.
The slow timer continues to fire all members at `pollFreq`; movers additionally receive individual
ticks at `dynamicPollFreq` via these chains running in parallel.

```
ensureFastChain(memberId):
  if not fastPollEligible(): return          # dynamic off, OR dyn >= default, OR token expired
  if fastChain-<memberId> already set: return  # chain already running for this member
  runIn(dynamicPollFreqSecs, "fastPollMember", [data: [memberId], overwrite: false])
  fastChain-<memberId> = true                # set AFTER runIn succeeds to avoid stranding

fastPollEligible():
  return (dynamicPolling && dynamicPollFreqSecs > 0 &&
          pollFreqSecs > dynamicPollFreqSecs && !tokenLikelyExpired)

fastPollMember(data):
  memberId = data.memberId (coerce to String — type may change through runIn serialization)
  keepGoing = (fastPollEligible() && isMemberInTransit(memberId) && memberId in settings.users)
  if keepGoing:
    runIn(dynamicPollFreqSecs, "fastPollMember", [data: [memberId], overwrite: false])
    # re-arm BEFORE the fetch so a fetch failure can't break the chain
  else:
    remove fastChain-<memberId>              # chain ends — member stopped, deselected, or disabled
  fetchMemberLocation(memberId, ctx)         # fire the per-member fetch (same path as slow tick)
```

**Chain lifecycle:**
- Chains are seeded from `notifyChildDevice` when a `200` shows a member in transit:
  `if (inTransit && dynamicPolling) ensureFastChain(memberId)`. This also fires on a 304 (member
  still in transit from persisted state) so a reboot doesn't permanently lose the chain for a
  moving member.
- `scheduleSlowTimer()` calls `unschedule()` (kills ALL jobs) then clears all `fastChain-*` markers.
  The next slow tick re-seeds any members still in transit.
- `fastChain-<id>` is an identity-scoped state key (survives `clearSessionCache()`); it is only
  dropped by `scheduleSlowTimer()` or when the chain intentionally ends.

**Does not engage when:**
- `dynamicPolling` is off
- `dynamicPollFreqSecs >= pollFreqSecs` (no point "speeding up" to a slower rate)
- `tokenLikelyExpired` (degraded mode — only the slow probe runs)
- The member is deselected or stops moving (chain ends naturally at the next tick)

### 4.4 Child-device reconciliation — `createChildDevices()` and `syncChildDevices()`

`syncChildDevices()` is the top-level entry point, called from `installed()`, `updated()`,
`initialize()`, and `handleMembersResponse` (on a 200). It:

1. Calls `createChildDevices()` — creates devices for any selected member that doesn't have one yet.
   Returns a `dni → device` map hoisted from `getChildDevices()` (avoids an O(N²) hub device-list
   walk).
2. Walks that map and deletes any child whose DNI is not `"${app.id}.${memberId}"` for a
   currently-selected member (orphan pruning), then calls `cleanupMemberState(memberId)` to drop
   all per-member state keys (session + identity) for the removed member.

`createChildDevices()` guards on `settings.users` and `state.members` both non-empty. For each
selected member without a device: look it up in `state.members`; if missing, `log.warn` and skip (no
NPE); otherwise `addChildDevice("jpage4500", "Life360+ Driver", "${app.id}.${memberId}", null, [...])`
— **`null` hub id**, never a hardcoded value.

**`state.members` is trimmed.** `handleMembersResponse` stores only `[id, firstName, lastName,
issues.disconnected]` per member — not the full member object with location, avatar, and
communications. This keeps `state.members` small so every execution's deserialisation cost stays low.
`notifyChildDevice()` receives the **untrimmed** `rawMembers` objects when pushing location after a
membership refresh.

### 4.5 Scheduler — `scheduleSlowTimer()`

`scheduleSlowTimer()` is the single scheduling entry point (called from `installed()`, `updated()`,
`initialize()`, auth-failure rising edge, and token-probe auto-recovery).

```
scheduleSlowTimer():
  unschedule()                               # full clean rebuild — kills slow timer, ALL fast chains,
                                             # legacy timers, and the repeat-reminder job
  removeStateKeysWithPrefix(["fastChain-"])  # clear chain markers (unschedule killed the jobs)

  # re-arm reminder timer if token is still expired (unschedule killed it above)
  if tokenLikelyExpired: scheduleTokenExpiryReminder()  # timer only — does NOT re-send alert

  baseSecs = tokenLikelyExpired ? TOKEN_EXPIRED_POLL_SECS (300) : pollFreqSecs()

  if baseSecs <= 0: log.info "polling DISABLED"; return  # pollFreq=0 → Disabled

  if baseSecs < 60: schedule("0/${baseSecs} * * * * ? *", handleSlowTimer)
  else:             schedule("0 */${baseSecs/60} * * * ? *", handleSlowTimer)
```

- **No no-op guard.** Earlier versions tracked `state.scheduledBaseSecs` and skipped the
  `unschedule()` + `schedule()` rebuild when the rate hadn't changed. This guard was removed; the
  rebuild is always performed so there is no hidden state to go stale.
- **`unschedule()` kills the token-expiry reminder chain.** The re-arm after `unschedule()` (above)
  restores the chain timer if the token is currently expired. Without this, opening app settings
  and clicking Done while the token is expired would silently kill the reminder chain.
- `pollFreqSecs()` and `dynamicPollFreqSecs()` read `(settings.pollFreq ?: "60")` etc. null-safely
  so a fresh install before the first **Done** can't NPE.
- Re-arm on hub reboot via `subscribe(location, 'systemStart', initialize)`. `initialize()` calls
  `clearSessionCache()` first — in-flight keys from requests in progress at reboot are never cleaned
  up by their callbacks, so clearing them on restart prevents members from being permanently stuck
  in the "prior request pending" guard.

---

## 5. Error handling & health

Each member fetch is judged independently; a small set of **account-level** flags is shared across all
members (an auth failure or 429 affects every member).

| Condition (per member) | Action |
| --- | --- |
| `200` | `notifyChildDevice`; store member etag; `markFetchSuccess(memberId)` clears `failCount`/`tokenLikelyExpired`/`rateLimitedUntilMs`, member's backoff, `lastSuccessMs`, watchdog |
| `304` | `markFetchSuccess(memberId)` |
| `401`/`403` | **account-level:** `clearSessionCache()` (drop cookies + all etags + inflight/backoff); `failCount++`; at `failCount >= 3` set `tokenLikelyExpired`, then `scheduleSlowTimer()` (slows loop to 300s) **before** `notifyTokenExpired()` — order matters: `scheduleSlowTimer()` calls `unschedule()` which would cancel the reminder job registered by `notifyTokenExpired()` if the order were reversed. Banner text names a re-paste; below 3, a transient "will retry" banner. **Known limitation:** `failCount` read-modify-write is racy across concurrent 401 callbacks — Hubitat state writes are not atomic and no lock primitive is available in the Groovy sandbox. Practical impact: the threshold may take 5–6 trips instead of 3 (delayed notification, not silent failure). No fix available. |
| `429` | **account-level:** `rateLimitedUntilMs = now + (Retry-After ?: 60) + 10s` — pauses the whole loop. Transient banner. |
| `502`/`503`/`504`/`520`/`522`/`525` / network error (no status) | **per-member** exponential backoff (below) |

**Per-member transient backoff.** Each member tracks `transientCount-<id>` and
`transientUntilMs-<id>`:

- On any of the transient statuses above, or a network-level failure (no HTTP status):
  `transientCount-<id>++`; `transientUntilMs-<id> = now + min(pollSecs * 2^(count-1), 300s)`. The
  shift exponent is capped at 6 (`min(count-1, MAX_BACKOFF_SHIFT)`) so the value can't overflow
  `long` before the `Math.min` clamp. `fetchMemberLocation` skips a member still inside its window
  — a flaky single member backs off without penalizing the healthy ones.
- Any `200`/`304` for that member clears both keys (via `markFetchSuccess`).

**Per-member in-flight guard.** `inflight-<id>` is set before `asynchttpGet` and cleared first thing in
the callback; `fetchMemberLocation` skips a member whose prior request is still outstanding
(`now - inflight[id] < (httpTimeout + 2)s`). Prevents pile-up when Life360 is slow.

**Account-level token recovery.** While `tokenLikelyExpired`, the loop runs at 300s and each tick fires
`probeTokenAfterExpiry()` → async `GET /users/me` → `handleTokenProbeResponse`:

- `200`: service healed — clear `tokenLikelyExpired`/`failCount`/`rateLimitedUntilMs`/`message`,
  cancel the reminder chain, `scheduleSlowTimer()` to restore the normal rate, capture units from
  response body. (Auto-recovery — no user action needed if the cause was a transient blip rather than
  a real expiry.)
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
`transientCount-*`, `transientUntilMs-*` keys (the `MEMBER_STATE_PREFIXES_SESSION` list) — a clean
slate on auth failure. Identity keys (`inTransit-*`, `fastChain-*`) survive so transit state and
chain markers aren't lost on a mere token re-paste.

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
  if 200: log requestId/isPollable; runIn(FORCE_UPDATE_FETCH_DELAY_SECS, "fetchLocations")  # ~6s for Life360 to push fresh GPS
  else: report failure
```

Two things that previously made this fail (now correct — do not regress):

1. Body must be exactly `{"type":"location"}` with `Content-Type: application/json`.
2. Cookies must be forwarded — the `__cf_bm`/`_cfuvid` jar is the Cloudflare gate.

**Surface:** a manual member-dropdown + **Force Update** button in app settings, gated on
`!isEmpty(settings.users)` and `!isEmpty(state.members)`. No auto-trigger. Result shown via the
one-shot `forceUpdateStatus` pattern. The status dropdown resets after firing
(`app.removeSetting("forceUpdateMember")`).

> **Known gap (accepted):** `forceMemberUpdate()` does not guard on `tokenLikelyExpired` /
> `rateLimitedUntilMs` before posting. It's a manual one-shot, so an occasional doomed POST against a
> struggling service is harmless — noted rather than worked around.

---

## 7. Driver (child device)

The driver is the stable part; nearly all change is app-side. Entry point:
`generatePresenceEvent(member, placesJson, home, ctx)` → returns a boolean `inTransit` the app reads.

- Null-safe: returns `false` early if `member` or `member.location` is null, or if `home` is null
  (a stale `settings.place` whose Life360 place was removed) — with a `log.error`, never an NPE.
- Null-safe `since` (`location.since?.toLong() ?: 0L`).
- Parse with the static `toDouble`/`toBool` helpers (stringy `"0"`/`"1"`, numbers-as-strings).
- **Units:** resolved in priority order: (1) app-level `logUnits` hard override (imperial/metric/
  hubitat); (2) `ctx.useMiles` from the app's `getUnitIsMiles()` (which reads `state.unitOfMeasure`
  from Life360's `/users/me`); (3) driver's own `unitOverride` per-device preference; (4) fall back
  to Hubitat hub's `temperatureScale`. `ctx` is hoisted once per tick by the app, so the IPC call
  for units happens at most once per poll cycle rather than once per member.
- Motion: trust Life360's `inTransit`/`isDriving`; the manual `transitThreshold`/`drivingThreshold`
  override only when set non-zero (compared against `speedUnits` in display units, null-safe).
- The "moving @ N mph/kph" info log fires only when `inTransit || isDriving`, and includes a Google
  Maps link only when `ctx.showMapsLink` (or `parent?.getShowMapsLink()` for backward compat) is true.
- HTML tile (only when `generateHtml`): uses `long` epoch for `since` (Y2K38-safe), `avatarFontSize`,
  and renders speed units correctly.
- Distance math: `haversine()` is `static` (pure math).

### 7.1 Presence logic

`present` iff `haversine(member, home) <= home.radius`. Inside the radius ⇒ `address1 = "Home"`;
missing place/address ⇒ `"No Data"`. `status` is `"At Home"` or `"<x.x> miles from Home"` /
`"<x.x> km from Home"`.

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
| Presence/power | `presence` (`present`/`not present`), `battery` (0–100), `powerSource` (`dc`/`battery`), `switch` (`on`/`off`), `contact` (`open`/`closed`), `acceleration` (`active`/`inactive`) |
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
home, ctx)` and reads its boolean return. Parent helpers the driver calls: `getShowNamesInLogs`,
`getShowMapsLink`, `getLogRawPayload`, `getUnitIsMiles`, `refresh`. Keeping these
stable lets a new app drive an old installed driver (and vice-versa) during a staged update.

**Device Network ID format (frozen):** `"${app.id}.${memberId}"`. Changing it orphans every existing
child device.

**Driver preferences (frozen names):** `generateHtml`, `saveHistory`, `transitThreshold`,
`drivingThreshold`, `avatarFontSize`, `avatarSize`, `logEnable`, `unitOverride`. Renaming any resets
that user's saved choice on update. Note: `isMiles` (a `bool`) was the pre-5.2.0 name for the units
preference; it was replaced by `unitOverride` (an `enum`) with a one-time migration in the driver's
`updated()`: if `isMiles == false` → `unitOverride = "metric"`. The same applies to the app's
settings vocabulary (§9/§10).

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

- **Setup cache:** `circles`, `places`, `members` (trimmed: id/firstName/lastName/issues only),
  `accessToken` (OAuth, for `/view` only).
- **Account preference:** `unitOfMeasure` (`"i"`/`"m"`, via `GET /users/me`; drives
  `getUnitIsMiles()` which all drivers read via `ctx.useMiles`).
- **Session:** `cookies`.
- **Per-member (session-scoped — cleared by `clearSessionCache()`):** `etag-<id>`, `inflight-<id>`,
  `transientCount-<id>`, `transientUntilMs-<id>`. Defined in `MEMBER_STATE_PREFIXES_SESSION`.
- **Per-member (identity-scoped — survive re-auth):** `inTransit-<id>`, `fastChain-<id>`. Defined
  in `MEMBER_STATE_PREFIXES_IDENTITY`. Cleaned up by `cleanupMemberState()` when a member is
  removed/deselected.
- **Scheduling:** `lastSuccessMs`, `lastCirclesPollMs`, `memberCount`.
- **Account health:** `failCount`, `tokenLikelyExpired`, `rateLimitedUntilMs`, `watchdogWarned`,
  `message`, `tokenStatus`, `placesEmptyWarned`.
- **UI one-shots:** `tokenStatus(+Pending)`, `forceUpdateStatus(+Pending)`.

**Obsolete keys** removed by `removeObsoleteStateKeys()` on every `updated()`/`initialize()`:
`placesJson`, `cachedHome`, `scheduledBaseSecs`, `pollIntervalSecs`, `dynamicPollingActive`,
`memberInTransit`, `lastCirclesFetchMs`. Do not re-introduce them.

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
- **Logging** — `logEnable`, `logRawPayload` (verbose, sensitive), `logShowNames`, `logShowMapsLink`,
  `logUnits` (units for speed/distance in logs and device attributes — app-level override applied to
  all members; "Follow Life360 app" defers to `state.unitOfMeasure`).
- **Map View** — optional `googleMapsApiKey` (else OSM), `/view` link + Generate/Revoke buttons.
- **Force Update** — member dropdown + button (gated on both `settings.users` and `state.members`
  non-empty).

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

**Upgrade-safe defaults.** All four settings use null-safe comparisons (`!= false` for ON-by-default,
`== true` for OFF-by-default) so that an upgrader who has never opened the app's settings page gets
the intended defaults without needing to hit Done. `null` (never configured) evaluates the same as
the documented default.

- `logShowNames` OFF must replace *all* names with IDs, not just some.
- `logRawPayload` is the one switch that intentionally logs sensitive data — its description says so.
  Never log the **full** bearer token or **full** cookie jar even behind it — partial/head only.

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
- **Google Maps** when `googleMapsApiKey` is set (uses the Maps JavaScript API v.weekly +
  `AdvancedMarkerElement` from the `marker` library).

Both render avatar markers (initial-letter pin fallback), a member-count status line, and a 60-second
auto-refresh. The Google Maps API key is HTML-escaped before embedding in the page.

---

## 12. Recreation checklist

To rebuild from nothing, in dependency order:

1. **Wire contract first (§0).** `life360Params`, `getHttpHeaders`, `captureCookies`/
   `captureCookiesAsync` (both via `mergeCookie`), `clearSessionCache` — byte-identical on the wire.
   Verify a single `GET /circles/{id}/members/{mid}` returns 200 and cookies accumulate.
2. **Soak the cookie path** against a live circle for hours — confirm no 403 creep. This gate has
   killed the integration before.
3. **Confirm per-member 304** — `If-None-Match` on an idle member returns `304`.
4. **Slow timer + location loop (§4.1):** `handleSlowTimer` → `fetchLocations` →
   `fetchMemberLocation` (etag + in-flight guard) → `handleMemberLocationResponse` →
   `notifyChildDevice` → driver.
5. **Per-member backoff + account gates (§5).** Verify a flaky member backs off alone; 401/429 pause
   the whole loop; auto-recovery probe clears `tokenLikelyExpired` on a 200.
6. **Slow loop (§4.2):** circles poll (v4, ≤1/min) → `memberCount` diff → `fetchMembers` →
   `handleMembersResponse` → `syncChildDevices` + name/avatar/location push. Confirm heartbeat log
   fires every tick.
7. **Dynamic polling fast chains (§4.3):** `ensureFastChain` / `fastPollMember` / `scheduleSlowTimer`
   chain cleanup. Verify a stopped member's chain ends and the slow poll picks it back up.
8. **Force-update (§6)** — manual button.
9. **Map view, notifications, privacy toggles, units subsystem (`logUnits`/`captureUnitOfMeasure`/
   `refreshUserSettings`), settings layout (§9–§11).**
10. **Verify the §7.3 contract** before any in-place release — diff the driver's attribute/capability/
    command declarations and both files' preference names against the prior version; new entries must
    be purely additive.

> Hubitat has no local test harness; validation = install on a real hub against a real circle and
> watch logs. §0 (cookies + headers) is the highest-risk surface and the thing to soak-test longest.
