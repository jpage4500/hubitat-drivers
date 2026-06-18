# Life360+ — Clean-Room App Specification (v2)

A from-scratch specification for a rewritten Life360 Hubitat integration. The goal is the
**least fragile, lowest-point-of-failure, fewest-moving-parts** design that still does the job:
track Life360 circle members as Hubitat presence/location devices.

This spec is derived from four sources:

- `hubitat-drivers-jpage4500/life360` — the currently *released* app/driver (synchronous, per-member polling).
- `hubitat-drivers-fork/life360` — this fork: async per-member fetch, eTags, in-flight guards, backoff, force-update, circles-poll membership detection. Plus [STATE_REFERENCE.md](STATE_REFERENCE.md). *(Note: `CODE_REVIEW.md` and `REVIEW_FOLLOWUPS.md` referenced throughout this spec were working documents that are not present in this directory — their findings are incorporated into the code and this spec.)*
- `ha-life360` — the **real Home Assistant integration** (`pnbruckner`). The robust, low-maintenance reference; "just works." Plus `life360/life360`, its API client library.
- `~/Desktop/life360.json` — the unofficial OpenAPI spec (`krconv/life360-api-docs`).

## The shape of v2: converge on the proven per-member + etag model

The original instinct for this rewrite was "one big call gets everything" (`GET /circles/{id}` →
all members + locations in a single request — simplest, fewest moving parts). Investigating the
**real HA integration** changed that conclusion:

- **HA does NOT use one big call for locations.** It polls **each member individually with an etag**
  (`GET /circles/{id}/members/{mid}`, `If-None-Match`), on a fast cadence, and uses the all-members
  endpoint only for a *slow background refresh* of names/avatars/membership (§3.6). This per-member +
  etag design is what has kept HA "tight" with minimal maintenance for years.
- **The fork has independently been converging on exactly this** — async per-member fetch, per-member
  etags, in-flight guards, backoff, and (recently) periodic circle polling for member/name/icon
  changes. That periodic poll *is* HA's second coordinator. The fork is ~90% of HA's two-cadence
  architecture without having named it as such.
- **"One big call" is the outlier** — the fork's own *discovery*, proven by nobody, and it throws away
  the cheap-when-idle win that makes the proven model efficient: under per-member etags, an unchanged
  member costs a near-free 304; under one big call, any single member moving forces reprocessing the
  whole circle, and the circle-level etag may not even be honored.

**So v2's real value is not a new polling shape — it's finishing the convergence on the proven model
and stripping the cruft.** Keep the per-member + etag location loop (HA's and the fork's shared
design); formalize the two-cadence split (slow membership refresh + fast location loop); remove the
bandaid-on-bandaid workarounds, the GPS-jitter heuristics, and the ad-hoc per-member backoffs;
replace them with clean, minimal mechanisms. This is a **lower-risk rewrite backed by two independent
working implementations**, not a clean-sheet bet on an untested polling shape.

The two API capabilities the fork discovered remain useful, just repositioned:

- **§9.2 — `GET /circles/{circle}` (all members + `memberCount` in one call).** Demoted from "the core
  location loop" to **the slow membership-refresh call** — one cheap request to detect roster changes
  and refresh names/avatars. Not the location path.
- **§9.1 — force a fresh GPS fix.** `POST /circles/{circle}/members/{member}/request` makes Life360
  push a location request to the phone (≈5s updates for ≈60s). Manual-only freshness tool; replaces
  the pile of retry/skip/heuristic logic that existed purely to chase stale locations.

### Resolved decisions (so the code phase doesn't relitigate them)

- **Polling model:** **per-member fetch + per-member etag** for locations (the proven HA/fork model),
  on a single Hubitat timer that loops the selected members and fires one `asynchttpGet` each. A
  **separate slow cadence** refreshes membership/names via `GET /circles/{id}` (§4). The "one big
  call" model is **rejected** as the location path.
- **Delivery:** v2 is built on a **fresh branch in this fork repo** as a large patch over `master`,
  keeping the `jpage4500` namespace and `Life360+` app/driver names (required for in-place update
  compat per §7.3, and because this is **sent upstream** for the maintainer's review — it is not a
  standalone fork release).
- **Dynamic polling:** kept — bumps the per-member location-loop rate while any member is in transit
  (§4.3).
- **Force-update auto-trigger:** **NOT shipped in v2** — force-update stays **manual only** (settings
  button + new driver command), exactly as the fork works today (the fork has *no* auto-trigger and is
  running well). Auto-trigger is recorded as a future idea, not built (§6). This is consistent with the
  "trust the payload / do less" principle.
- **Privacy log defaults:** `logShowNames` / `logShowMapsLink` stay default **ON** — hubs are rarely
  internet-exposed, so "easy by default" is fine; privacy-conscious users flip them off (§10.1).
- **304 / etag:** per-member `If-None-Match` is the **efficiency core**, not an afterthought (it's how
  HA stays cheap). Send it unconditionally per member and trust whatever Life360 returns (200 or 304).

---

## 0. The one rule that overrides everything: DO NOT BREAK THE COOKIE PATH

> The integration has been **completely dead** several times when Life360 changed their backend.
> The most recent rescue (fork commit `3f78018`, May 2026) was the **Cloudflare cookie handling**.
> What is in the released/fork code today is a **known-working configuration**. Treat the wire-level
> behavior as load-bearing. The rewrite may restructure everything *around* it, but the bytes on the
> wire — URL, headers, cookie capture/replay — must stay byte-for-byte identical to what works now.

### 0.1 The exact wire contract that currently works — copy it verbatim

| Element | Required value | Why |
| --- | --- | --- |
| Base URL | `https://api-cloudfront.life360.com/v3` | The cloudfront host is what `3f78018` switched to. `api.life360.com` is the documented fallback only. |
| `Accept` | `application/json` | — |
| `cache-control` | `no-cache` | — |
| `User-Agent` | `com.life360.android.safetymapd/KOKO/23.50.0 android/13` | Exact mobile-app fingerprint. Matches HA's `USER_AGENT`. |
| `Authorization` | `Bearer <access_token>` | — |
| **No other headers** | — | Extra headers (e.g. `X-Application`, `circleid`) have triggered 403s. Do not add any unless a future break forces it. |
| `Cookie` | replayed from captured `Set-Cookie` | **The Cloudflare gate.** See below. |

### 0.2 Cookie capture/replay — the load-bearing mechanism

- Cloudflare sets `__cf_bm` (bot-management) and `_cfuvid` cookies. **Without `__cf_bm` you get 403
  within minutes.** This is the single most important behavior in the whole integration.
- **Capture cookies on EVERY response** — circles, members, location, force-update, `/users/me`,
  everything. This was the key insight of `3f78018`.
- **Replay the accumulated cookie jar on EVERY request** via the `Cookie` header.
- The cookie jar must be a **name→value merge/upsert**, never a whole-jar overwrite. (REVIEW_FOLLOWUPS
  §1.1: the fork's *async* `captureCookiesAsync` had an overwrite bug that could drop `_cfuvid`.) The
  rewrite captures from the async poll response (§0.3) but **must do the merge correctly** — parse the
  existing jar into `name→value`, upsert this response's cookie(s), re-serialize. The sync
  `captureCookies` is the correctness reference: it does `responseCookies.join(";")` across *all*
  `Set-Cookie` headers of one response; the async path accumulates the per-response cookie into the
  jar the same way, without ever clobbering the whole jar.
- Tokenizer note (CODE_REVIEW §1.3): `it.value?.tokenize(';|,')?.getAt(0)` is **not** a bug —
  `__cf_bm` is base64-url-safe (no commas) and `;` is always the first segment separator. Leave it.

### 0.3 Cookie capture under the per-member model — keep async, fix the merge

The location loop fires one `asynchttpGet` **per selected member** per tick, plus the slow membership
call and force-update. Every one of those responses must run cookie capture (§0.2) — this is unchanged
from the fork, which already does exactly this and works in production.

**Cookie capture from async responses is proven** — the fork captures `__cf_bm`/`_cfuvid` from
`asynchttpGet`/`asynchttpPost` responses today. The *only* defect REVIEW_FOLLOWUPS §1.1 found was the
**merge logic** (a whole-jar overwrite that could drop `_cfuvid`), not the header extraction. v2 keeps
async capture and **fixes the merge to a proper name→value upsert** (parse the existing jar → upsert
this response's cookie(s) → re-serialize), matching the correctness of the sync `captureCookies` join.

> Because multiple async responses land close together (N members per tick), the merge must be a
> read-modify-write **upsert keyed by cookie name**, and must tolerate concurrent updates — never
> clobber the jar. This is the single most important correctness detail in the whole loop.

> **Soak-test requirement:** any change to §0.1/§0.2 must be tested against a *live circle for at
> least several hours* (Cloudflare 403s appear minutes-to-hours after a bad change, not immediately).

---

## 1. Design principles

1. **Per-member + etag location loop (the proven model).** Each selected member is fetched with
   `GET /circles/{id}/members/{mid}` carrying that member's `If-None-Match`. An unchanged member
   returns a near-free `304`; only movers return a `200` body. This is how HA and the fork stay cheap
   and current. A **separate, slow** `GET /circles/{id}` refreshes membership/names/avatars and gives
   `memberCount` for roster-change detection (§4.2).
2. **Async, with a per-member in-flight guard.** Each per-member fetch is `asynchttpGet` so a
   slow/down Life360 never blocks the hub scheduler thread. A per-member in-flight marker prevents a
   new request for a member whose prior request is still outstanding — so a slow service skips that
   member's tick rather than stacking requests. (This is the fork's §7.1 pattern; it's the right
   tool *because* we poll per member.)
3. **Trust the payload — don't outsmart Life360.** Pass `inTransit` / `isDriving` and the rest of the
   `location` object straight through. No GPS-jitter heuristics, no "the API is probably wrong so let's
   correct it" logic (the fork proved heuristics fire false-positives on stationary Wi-Fi members —
   CODE_REVIEW §2.5). The released code is a pile of bandaid-on-bandaid workarounds-for-workarounds;
   the fork **removed nearly all of them** and trusts the response instead. **Governing assumption:**
   if part of Life360's API is genuinely broken, Life360 fixes it upstream — we do *not* accrete more
   client-side workarounds. The only sanctioned override is the user's explicit manual speed
   thresholds (`transitThreshold` / `drivingThreshold`). When in doubt, do less.
4. **Force-update for freshness, not inference.** When a fresh fix is genuinely needed, *ask the phone*
   (§9.1) instead of inferring movement from noisy data.
5. **Fail loud, fail safe.** On auth failure: clear session, flag token, slow polling, notify once.
   Never hammer a dead endpoint. Never silently spin.
6. **Read-only and unobtrusive.** No webhooks (disabled by Life360), no writes except the
   force-update request, minimal state, minimal scheduler churn.
7. **PII-aware logging is kept in full (see §10).** The integration logs member names, coordinates,
   addresses, phones, and emails. The fork's two-axis logging controls — verbosity (`logEnable`,
   `logRawPayload`) and PII redaction (`logShowNames` names↔UUIDs, `logShowMapsLink`) — are **all
   retained by name** so users can run debug logging and still share logs safely.
8. **Backward compatibility is non-negotiable (see §7.3).** This app has shipped for years. Existing
   users have Rule Machine rules, dashboards, and webCoRE pistons bound to the current child-device
   attributes, capabilities, and commands. The rewrite **must keep every device-facing name and value
   identical** so an in-place update doesn't silently break anyone's automations. Improve *behind* the
   contract, never by changing it.
9. **Simplify by removing cruft, not by changing the proven shape.** v2's simplification is deleting
   bandaids/heuristics and replacing N ad-hoc per-member backoffs with clean per-member backoff +
   one health view — *not* collapsing the per-member loop into a single call. The per-member +
   etag shape stays because it's proven; the mess around it goes.

---

## 2. Architecture

```
┌──────────────────────────── Hubitat Hub ────────────────────────────┐
│                                                                      │
│  Life360+ App (parent)                                               │
│   • holds access_token, circle id, home place id, member selection   │
│   • schedule(pollFreq) ──► pollLocations()  (fast loop)              │
│   •   pollLocations(): for each member → asynchttpGet member+etag    │
│   •     handleMemberResponse: 200 → update child; 304 → no-op        │
│   • schedule(slow)     ──► refreshMembership()  (slow cadence)       │
│   •   refreshMembership(): GET /circles/{id} → memberCount + names   │
│   • forceUpdate(memberId): POST …/request  (manual)                  │
│   • /view endpoint: all-members map (OSM default, Google optional)   │
│   • capture cookies on EVERY response (load-bearing)                 │
│                                                                      │
│   ├── Child device: Life360+ Driver (member A)                       │
│   ├── Child device: Life360+ Driver (member B)                       │
│   └── …                                                              │
└──────────────────────────────────────────────────────────────────────┘
```

Two files, exactly as today: `life360_app.groovy` (parent) and `life360_driver.groovy` (child). Two
cadences, mirroring HA's two coordinators: a **fast per-member location loop** and a **slow membership
refresh**.

---

## 3. API surface used (and explicitly *not* used)

From `life360.json`. Base path `https://api-cloudfront.life360.com/v3`.

### 3.1 Used in steady state

| Purpose | Method + path | Notes |
| --- | --- | --- |
| **Poll one member's location** | `GET /circles/{circle}/members/{member}` | **Core location call.** Per member, with that member's `If-None-Match`. `200` = changed (full member+`location`); `304` = unchanged (near-free). The fast loop fires one of these per selected member per tick. |
| **Refresh membership / names** | `GET /circles/{circle}` | **Slow cadence.** Returns the circle (incl. `memberCount`) + embedded `members[]`. Detects roster changes and refreshes names/avatars. Not the location path. |
| Force fresh GPS fix | `POST /circles/{circle}/members/{member}/request` | Body `{"type":"location"}`, `Content-Type: application/json`. Returns `{requestId, isPollable}`. Cookies required (Cloudflare). |

> **Why per-member for the location loop (not the one big call):** this matches the proven HA design
> (§3.6) and the fork's working code. Per-member `If-None-Match` makes an unchanged member a near-free
> `304`; only movers cost a `200` body. The one-big-call alternative (`GET /circles/{id}` for
> locations) loses that — any single member moving invalidates the whole-circle response. `/circles/{id}`
> is therefore used only for the **slow membership refresh**, where its `memberCount` is exactly what
> we want.

### 3.2 Used during setup / on demand

| Purpose | Method + path | Notes |
| --- | --- | --- |
| Validate token | `GET /users/me` | Cheap. Run on token paste and on the 3rd consecutive auth failure for a definitive diagnosis. |
| List circles | `GET /circles` | Setup only — populate the circle picker. (Tiny payload; could also be a fallback membership probe.) |
| List places | `GET /circles/{circle}/places` | Setup only — populate the HOME picker. Released code uses `…/places.json`; the spec path is `…/places`. Keep whatever the working code uses; `.json` suffix is harmless. |
| Poll an update request | `GET /circles/members/request/{requestId}` | Optional — to read the forced fix directly instead of waiting for the next tick. |

### 3.3 Explicitly NOT used

- `POST /oauth2/token.json` (username/password login). **The app takes a manually-pasted bearer
  token, never credentials.** This is not a preference — pasting a token captured from a real signed-in
  session is the **ONLY known method that works**. Life360's programmatic login is captcha/MFA-gated
  and is the single most fragile part of HA's library; the app must stay out of it entirely. See
  §3.5 for the token-acquisition procedure that **must be preserved** in the README and in the app's
  STEP 1 help text.
- Webhooks / push subscriptions — disabled by Life360, dead code in the released app.
- Any V5/activity endpoints — `userActivity` is parsed if present but not requested.

### 3.5 Token acquisition — the ONLY known working method (PRESERVE VERBATIM)

> **Load-bearing, like the cookie path.** There is no supported way to log in programmatically. The
> integration only works with a bearer token lifted from a browser session that is already
> authenticated to Life360. These directions must survive the rewrite — reproduce them in `README.md`
> (Setup) and surface a short form in the app's **STEP 1: Access Token** help text. Do not drop them.

**Procedure (browser DevTools method):**

1. In a desktop browser, go to `https://www.life360.com` and **sign in** with your Life360 account
   (complete any MFA). Stay on the logged-in page.
2. Open the browser **Developer Tools** (F12, or right-click → Inspect) and select the **Network** tab.
3. Reload / navigate the Life360 web app so requests appear. Filter the Network list for requests to
   `life360.com` (e.g. calls to `…/v3/circles` or `…/v4/circles`).
4. Click one of those API requests and look at its **Request Headers**. Find the
   `Authorization: Bearer <long-token>` header.
5. Copy **only the token** — the long string **after** the word `Bearer ` (do not include `Bearer`
   itself, and no surrounding quotes/spaces).
6. Paste that token into the app's **STEP 1: Access Token** field and click **Check Token**
   (which calls `GET /users/me` — a `200` with your name confirms it's valid).

**Notes to preserve alongside the steps:**

- The token is a **secret credential** equivalent to your Life360 login — anyone with it can read your
  circle's live locations. Treat it like a password; don't paste it into screenshots or share it.
- Tokens **expire / get revoked** server-side (no fixed lifetime is documented). When the app flags
  `tokenLikelyExpired` (3× consecutive `401`/`403`), repeat this procedure to grab a fresh token and
  re-paste it; clicking **Done** clears the expired flag and resumes polling.
- Capturing the token does **not** require the cookies — the cookies (§0.2) are accumulated separately
  by the app from Life360's responses. The token and the cookie jar are two independent gates; both
  must be valid for a request to succeed.

### 3.4 Key response shapes (from the spec + observed payloads)

`GET /circles/{circle}` → `CircleInformation` ⊕ `{ members: [ MemberBasicInfo ⊕ MemberDetailedInfo ] }`:

- Circle: `id`, `name`, `memberCount` (string), `features`, …
- Member: `id`, `firstName`, `lastName`, `avatar`, `communications[]` (`{channel: Voice|Email, value}`),
  `features.shareLocation`, and `location`:
  - `latitude`, `longitude`, `accuracy` (strings)
  - `battery` (%), `charge` (`"0"`/`"1"`), `wifiState` (`"0"`/`"1"`)
  - `speed` (number; iPhone can report `-1` → treat as 0)
  - `inTransit` (`"0"`/`"1"`), `isDriving` (`"0"`/`"1"`)
  - `since` (epoch seconds; **may be absent** — null-guard, CODE_REVIEW §3.7)
  - `name` / `address1` / `address2` / `shortAddress` (place/address labels)
  - `startTimestamp`, `endTimestamp`, `timestamp`

> All booleans are stringy `"0"`/`"1"`; all numbers may arrive as strings. Parse defensively
> (null-safe `toDouble` / `toBool` helpers, exactly as the driver does today).

### 3.6 Reference: how Home Assistant *actually* polls (corrected from the real integration)

From `ha-life360` — the **real HA integration** (not just the client library). The architecture has
**two coordinators** ([coordinator.py](../../ha-life360/custom_components/life360/coordinator.py#L711)):

- **Circles & Members *list* coordinator** — slow/background. Calls `get_circles` then
  `get_circle_members` (`GET /circles/{id}/members`, all members in one call) only to refresh
  names/pictures/membership. The code comments note it's "not called very often."
- **Member *location* coordinator — ONE PER MEMBER, every `UPDATE_INTERVAL = 5 seconds`**
  ([const.py](../../ha-life360/custom_components/life360/const.py#L17)). Each calls `get_circle_member`
  (**singular**, `GET /circles/{id}/members/{mid}`) **with etags** (`raise_not_modified=True`).

**Key finding:** the "just works" reference **polls each member individually at 5s with `If-None-Match`**
and uses the all-members endpoint only for the slow background membership refresh. This is essentially
the **fork's** model — and the reason both have stayed current with minimal maintenance.

**What this means for v2 (decided):**
- **Adopt the per-member + etag location loop.** It's proven by two independent implementations (HA and
  the working fork). This is the v2 location path.
- **Demote the one-big-call (`GET /circles/{id}`) to the slow membership refresh** — exactly HA's role
  for the all-members endpoint. Its `memberCount` drives roster-change detection (§4.2).
- **Hubitat adaptation:** HA runs a *separate 5s timer per member* (true parallel coordinators). On
  Hubitat that's too many scheduled jobs, so v2 uses **one timer tick that loops the selected members
  and fires one `asynchttpGet` each** — same per-member + etag behavior, one scheduler entry. This is
  the fork's existing approach; keep it.
- Cookies / base URL / headers / User-Agent / etag-via-`l360-etag` are the **same wire path** as the
  Hubitat code (the fork was modeled on this library) — §0 stands.

---

## 4. App: the two loops (per-member location + slow membership)

Two cadences, mirroring HA's two coordinators. The fast loop fetches each member's location with an
etag; the slow loop refreshes the roster/names.

### 4.1 `pollLocations()` — the fast per-member location loop

```
pollLocations():                                      # the scheduled timer handler (pollFreq)
  if circle unset OR no members selected: return
  if rateLimitedUntilMs in future: return            # honor prior 429
  if tokenLikelyExpired: return                       # don't hammer a dead token
  throttle: reject if < 5s since last loop start      # keep the existing guard

  ctx = buildPlacesContext()                          # serialize places ONCE per tick (§4.5)
  maybeRefreshMembership()                             # slow cadence, rate-limited (§4.2)

  for memberId in settings.users:
    fetchMemberLocation(memberId, ctx)                # fires one asynchttpGet each

  if settings.dynamicPolling: scheduleDynamicCheck()  # re-evaluate rate after responses land (§4.3)

fetchMemberLocation(memberId, ctx):
  now = nowMs()
  # per-member in-flight guard — don't stack a 2nd request for a member still pending
  if inflight[memberId] > 0 AND (now - inflight[memberId]) < (httpTimeout + 2s): return
  # per-member transient backoff — skip if still inside this member's backoff window
  if backoffUntil[memberId] > now: return
  inflight[memberId] = now

  params = life360Params("/circles/${circle}/members/${memberId}")
  params.timeout = clamp(pollIntervalSecs, 5, 30)
  if etag[memberId]: params.headers["If-None-Match"] = etag[memberId]   # per-member etag
  asynchttpGet("handleMemberResponse", params, [memberId: memberId, ctx: ctx])

handleMemberResponse(response, data):
  memberId = data.memberId
  inflight.remove(memberId)                            # clear guard FIRST, always
  captureCookies(response)                             # EVERY response — load-bearing (§0.2)
  switch(response.status):
    200:
      notifyChildDevice(memberId, response.json, data.ctx)
      etag[memberId] = response.headers["l360-etag"]   # store per-member etag
      onMemberSuccess(memberId)                         # clear this member's failCount/backoff
      bump lastSuccessMs; clear message/watchdog
    304:                                                # unchanged — near-free, the efficiency win
      onMemberSuccess(memberId)
      bump lastSuccessMs; clear message/watchdog
    401/403:
      onAuthFailure()                                  # clear session cache + cookies, count, maybe flag+notify
    429:
      backoff(Retry-After)                              # global rate-limit window
    5xx/520/522/525/timeout/no-status:
      onMemberTransientFailure(memberId)                # per-member exponential backoff (§5)
```

Notes:

- **Async per member.** `asynchttpGet` never blocks the scheduler thread. Each member has its own
  in-flight marker, so a slow member skips its next tick instead of stacking — and one slow member
  doesn't hold up the others.
- **Per-member etag is the whole efficiency story.** Send `If-None-Match` for each member
  unconditionally; trust the result. A stationary member returns `304` (no body, no work). This is why
  HA stays cheap at 5s and why we keep it. Do **not** resurrect the skip-etag-for-in-transit hack
  (CODE_REVIEW §9.1.1) — if Life360 says `304`, nothing changed.
- **Dynamic-polling flags are fresh.** Because each member's `inTransit` flag is written in
  `handleMemberResponse`, evaluate the rate switch *after* the batch lands (a short `runIn` re-check),
  not inline in `pollLocations()` — the correct fix for REVIEW_FOLLOWUPS §2.2.
- **`buildPlacesContext()` once per tick.** Serialize `placesJson` a single time and thread it through
  every `fetchMemberLocation` → `notifyChildDevice` (§4.5).

### 4.2 `refreshMembership()` — the slow membership/names loop

Mirrors HA's "Circles & Members list" coordinator. Runs on a **slow cadence** (rate-limited to at most
once per minute via `maybeRefreshMembership()`), not every location tick.

```
maybeRefreshMembership():
  if now - lastMembershipFetchMs < 60s: return
  lastMembershipFetchMs = now
  asynchttpGet("handleMembershipResponse", life360Params("/circles/${circle}"))   # +cookies

handleMembershipResponse(response):
  captureCookies(response)
  if 200:
    circle = response.json
    if circle.memberCount != state.memberCount:        # roster changed
      state.memberCount = circle.memberCount
      reconcileChildDevices(circle.members)            # create missing / orphan-delete
    refresh names/avatars from circle.members          # cheap, dedup by value
```

- `GET /circles/{id}` returns `memberCount` + embedded `members[]` — one cheap call to both detect
  roster changes *and* refresh names/avatars. This is the one place the §9.2 all-members endpoint earns
  its keep.
- **Carry forward the fork's `createChildDevices()` fixes** in `reconcileChildDevices()`:
  - `addChildDevice(…)` passes `null` for the hub id (let Hubitat pick), **not** the hardcoded `1234`
    (CODE_REVIEW §3.3 — fails on any non-`1234` hub / multi-hub installs).
  - Hoist `getChildDevices()` **once** into a `dni → device` map; don't call it inside the loop
    (O(N²) hub walk, §4.4). Use the map for both creation and orphan cleanup.
  - Drop the dead `childList.find { it.data.vcId == … }` inner check (§3.4) — the `!childDevice` guard
    already protects creation.
  - Orphan cleanup: delete any child whose DNI isn't `"${app.id}.${memberId}"` for a currently-selected
    member (§3.5).

### 4.3 Dynamic polling (optional) — bumps the per-member loop rate

**Decision (kept for v2):** dynamic polling stays — slow-standard-poll users (e.g. 3-min standard) get
real value from faster updates during a trip. **It bumps the rate of the per-member location loop**
while any selected member is moving; it does *not* change the per-member structure (every member is
still fetched with its own etag — they're just fetched more often).

> **Why this exists at all.** HA (§3.6) polls **each member every 5 seconds**, fixed — no user-facing
> slow/fast concept. The Hubitat configurable rate + dynamic polling are deliberate additions to
> **tread lighter on the API**: a slow-poller keeps near-zero load when nothing's happening and only
> speeds up briefly during actual motion — far gentler over a day than HA's constant 5s polling, which
> matters given the years of breakage.

- If `dynamicPolling` enabled and **any** selected member's `inTransit`/`isDriving` flag is set, switch
  the location-loop schedule to `dynamicPollFreq`; otherwise `pollFreq`.
- Reuse the fork's **no-op-rebuild guard**: track `state.scheduledBaseSecs`; only `unschedule()` +
  `schedule()` when the desired rate actually changes (CODE_REVIEW §2.6, §8.8). Prevents triple-fire
  scheduler churn.
- `pollFreq == 0` ("Disabled") must **early-return before adding jitter** (CODE_REVIEW §2.3 — jitter
  turned "disabled" into 1–4s polling).
- Dynamic only engages when `pollFreq > dynamicPollFreq` (REVIEW_FOLLOWUPS §3.1) — document it.
- Per-member `inTransit-<memberId>` flags are written in `handleMemberResponse` and read by the rate
  switch to answer "is *anyone* moving?"

### 4.4 Scheduler hygiene (carry forward, these were real bugs)

- Always `unschedule()` before re-arming the location-loop job; never stack timers (CODE_REVIEW §2.6).
- One shared `@Field static final Random RNG` for jitter, not `new Random()` per call (§8.6).
- Re-arm on `systemStart` (hub reboot) via `subscribe(location, 'systemStart', initialize)`.

---

## 5. App: error handling & health

Per-member classification (each member fetch is judged independently), plus one shared
account-level view of token/rate-limit state. This is the fork's model, kept — but with the *ad-hoc*
per-member backoff replaced by one clean, consistent per-member backoff helper.

| Condition (per member) | Action |
| --- | --- |
| `200` | `notifyChildDevice`; store member etag; clear this member's `failCount`/`backoffUntil`; bump `lastSuccessMs`; clear `message`/watchdog |
| `304` | clear this member's `backoffUntil`; bump `lastSuccessMs`; clear `message`/watchdog |
| `401`/`403` | account-level: `clearSessionCache()` (drop cookies + **all** etags); `failCount++`; at `failCount >= 3` set `tokenLikelyExpired`, notify once, slow loop to 300s. **Also fire `GET /users/me`** for a definitive expired-vs-blip diagnosis; store `state.tokenStatus`. |
| `429` | account-level: `rateLimitedUntilMs = now + (Retry-After ?: 60) + 10s` — pauses the whole loop |
| `5xx`/`520`/`522`/`525`/timeout/no-status | **per-member** backoff — see below |

**Per-member transient backoff (clean version of the fork's §5.3).** Each member tracks its own
`transientCount[memberId]` and `backoffUntil[memberId]`:

- `onMemberTransientFailure(memberId)`: `transientCount[memberId]++`;
  `backoffUntil[memberId] = now + min(pollSecs * 2^(transientCount-1), 300s)` (exponential, capped at
  5 min). `fetchMemberLocation` skips a member still inside its window. A flaky single member backs off
  without penalizing the healthy ones.
- Any `200`/`304` for that member clears its `transientCount`/`backoffUntil`.
- This is the same per-member backoff the fork shipped, just expressed as one helper instead of
  scattered inline logic — the cleanup, not a behavior change.

**Per-member in-flight guard.** `inflight[memberId]` set before `asynchttpGet`, cleared first thing in
`handleMemberResponse`; `fetchMemberLocation` skips a member whose prior request is still outstanding
(`now - inflight[memberId] < httpTimeout + 2s`). Prevents pile-up when Life360 is slow (fork §7.1).

**Account-level health (shared across members).** `failCount` / `tokenLikelyExpired` /
`rateLimitedUntilMs` are *account*-scoped (an auth failure or 429 affects every member), so they live
at the loop level, not per member. This is the one place a single "health view" makes sense — and it's
enough; no separate circuit-breaker abstraction is needed beyond the per-member backoff + the
account-level token/rate-limit gates.

**Watchdog:** if `lastSuccessMs` (newest success across all members) is older than
`max(pollFreq×10, 10min)` and token isn't flagged, log `WATCHDOG` once on the rising edge (not every
tick — CODE_REVIEW §3.6) and surface a banner.

**Token-expiry notification:** notify device(s) once on the rising edge; optional repeat reminder
(Never/2/6/12/24/48h); master on/off toggle; cancel the chain when a fresh token is pasted in
`updated()` (CODE_REVIEW §5.2).

**`clearSessionCache()`** drops `cookies` and **all** per-member etags (`etag[*]`), plus per-member
`inflight[*]` / `backoffUntil[*]` / `transientCount[*]` — a clean slate on auth failure, exactly like
the fork.

---

## 6. App: force-update (§9.1) — confirmed working, keep the recipe exactly

```
forceMemberUpdate(memberId):
  url  = ".../circles/${circle}/members/${memberId}/request"
  body = JsonOutput.toJson([type: "location"])     # EXACT payload — wrong/empty body → 400
  params.contentType = "application/json"
  params.body        = body
  params.headers["Cookie"] = state.cookies          # REQUIRED — Cloudflare 403s without cookies
  asynchttpPost("handleForceUpdateResponse", params, [memberId: memberId])

handleForceUpdateResponse:
  captureCookies(response)
  if 200: log requestId/isPollable; runIn(6, "pollLocations")   # pick up fresh fix next loop
```

Two things that previously made this fail (now fixed — do not regress):

1. **Body must be exactly `{"type":"location"}` with `Content-Type: application/json`.** Empty / form /
   wrong body → 400.
2. **Cookies must be forwarded.** The accumulated `__cf_bm`/`_cfuvid` jar is the Cloudflare gate, not
   the bearer token or User-Agent alone.

Surfaces:

- **Manual button** in app settings (member dropdown + "Force Update"). This is where the feature
  lives — settings panel, manual, on-demand. No auto-trigger.

> This is one extra async call alongside the location loop. It's a user-triggered one-shot — it **must**
> use the same correct name→value cookie-merge (§0.2/§0.3) when it captures the response, and it should
> respect account health: don't fire while `tokenLikelyExpired` or `rateLimitedUntilMs` is active —
> there's no point asking a struggling service for *more* work.
>
> **Implementation note:** the current code (`forceMemberUpdate()` in `life360_app.groovy`) does not yet
> guard on `tokenLikelyExpired` / `rateLimitedUntilMs` before posting. This is a known gap — see TODO.md
> for the deferred fix.

---

## 7. Driver (child device) — largely unchanged from the fork

The driver is the stable part; the rewrite is mostly app-side. Keep `generatePresenceEvent(member,
placesJson, home)` and its outputs. Carry forward the fork's fixes:

- Trust Life360 `inTransit`/`isDriving`; honor manual `transitThreshold`/`drivingThreshold` only
  (CODE_REVIEW §2.5).
- Null-safe `since` (`location.since?.toLong() ?: 0L`, §3.7).
- Parenthesized presence/address ternaries (§3.1, §3.2).
- `installed()` sends `sendEvent(name:"address1prev", value:"No Data")` (§2.1).
- `historyClearData` uses correct event names (§2.2).
- HTML tile uses `avatarFontSize` (§2.4); `long` epoch for Y2K38 (§8.13); `static haversine` (§8.12).
- App serializes `placesJson` **once per tick** and passes the string through; driver sends it as-is
  (§4.5). Driver tolerates a raw Map for backward-compat.

### 7.1 Capability repurposing (document loudly — README + inline)

| Standard capability | Real meaning |
| --- | --- |
| `Switch` on/off | WiFi connected / not |
| `Contact Sensor` open/closed | Charging / on battery |
| `Acceleration Sensor` active/inactive | In transit OR driving OR away from home |

### 7.2 Presence logic

`present` iff `haversine(member, home) <= home.radius`. Inside radius ⇒ `address1 = "Home"`. This is the
one piece of math the app owns; keep it identical to today.

### 7.4 Location history feature (driver-side, keep as-is)

Gated behind the `saveHistory` preference (default OFF). When ON, the driver appends each new lat/lng to
`state.locationHistory` (most-recent-first, capped at 100) and writes the compact `history` attribute.
Preserve the format and the gating exactly — it's a documented attribute (§7.3) some users parse:

- Format v1: `"1|s,lat,lng|ds,dlat,dlng|…"` — leading `1` is the version; first entry absolute
  (epoch-seconds + decimal lat/lng); subsequent entries are **signed deltas** (seconds delta, lat/lng
  deltas in units of `0.00001°`). Capped at 1024 chars.
- GPS-jitter filter: entries within ~5 m of the previous emitted point are skipped.
- Lat/lng rounded to 5 decimals (~1 m) to keep entries compact.

The separate `bpt-history` / `numOfCharacters` / `lastLogMessage` surface (driven by the external
"Life360 Tracker" app via `sendHistory`) is **unrelated** to `saveHistory`/`history` — keep both
separately; `historyClearData` clears the whole driver-side history surface (both `state.locationHistory`
and `state.list1`).

### 7.5 Manual presence override commands (keep)

`arrived` / `departed` driver commands let a user force `presence` to `present` / `not present` from
Rule Machine or a tile (e.g. to correct a bad fix). They write `presence` directly and are overwritten
by the next poll — intentional, keep them.

### 7.3 Backward-compatibility contract — DO NOT CHANGE DEVICE-FACING NAMES OR VALUES

> Like the cookie path (§0) and the token method (§3.5), this is **load-bearing**. The app has shipped
> for years; users have automations (Rule Machine, webCoRE, dashboards, the external "Life360 Tracker"
> app) bound to these exact attribute names, capability behaviors, command names, and string/enum
> values. An in-place code update **must not** rename, retype, or repurpose any of them, or it silently
> breaks every downstream automation. New code is fine; **renames and value changes are forbidden.**

**The frozen public contract of `Life360+ Driver` (must all remain, identical):**

*Capabilities (and their repurposed meanings — frozen even though some are oddly named):*
`Actuator`, `Presence Sensor`, `Sensor`, `Refresh`, `Battery`, `Power Source`, `Switch`,
`Contact Sensor`, `Acceleration Sensor`. The repurposing in §7.1 (`switch`=WiFi, `contact`=charging,
`acceleration`=transit/away) is part of the contract — keep the same mapping and the same on/off,
open/closed, active/inactive values.

*Attributes (exact names, types, and value vocabularies):*

| Group | Attributes (frozen names) |
| --- | --- |
| Presence/power | `presence` (`present`/`not present`), `battery` (0–100), `powerSource` (`DC`/`BTRY`), `switch` (`on`/`off`), `contact` (`open`/`closed`), `acceleration` (`active`/`inactive`) |
| Location | `latitude`, `longitude`, `accuracy`, `address1`, `address1prev`, `lastLocationUpdate`, `lastUpdated`, `since`, `status`, `distance`, `savedPlaces` |
| Motion | `inTransit` (`"true"`/`"false"`), `isDriving` (`"true"`/`"false"`), `speed`, `userActivity` |
| Phone | `charge` (`"true"`/`"false"`), `wifiState` (`"true"`/`"false"`), `shareLocation` |
| Identity | `memberName`, `avatar`, `phone`, `email` |
| HTML tiles | `avatarHtml`, `html`, `history`, plus the external-app attrs `bpt-history`, `numOfCharacters`, `lastLogMessage`, `lastMap` |

> Subtle semantics to preserve, not "fix": `address1` becomes the literal `"Home"` inside the home
> radius and `"No Data"` when missing; `lastLocationUpdate` updates only when `address1` changes
> (`lastUpdated` is the per-event timestamp); booleans are the **strings** `"true"`/`"false"`, not real
> booleans; `status` is `"At Home"` or `"<x.x> miles from Home"`. Rules match on these exact strings.

*Commands (frozen names + signatures):*
`refresh`, `arrived`, `departed`, `sendHistory(string)`, `sendTheMap(string)`, `historyClearData`.
The external "Life360 Tracker" app calls `sendHistory`/`sendTheMap` — keep them even though they're
not part of the core flow.

*App ↔ driver bridge (frozen):* the parent calls
`childDevice.generatePresenceEvent(member, placesJson, home)` and reads its boolean return. Keep this
method name and signature so a **new app can drive an old installed driver and vice-versa** during a
staged update. Likewise keep the parent helper names the driver calls
(`getShowNamesInLogs`, `getShowMapsLink`, `getLogRawPayload`, `getLogEnable`, `refresh`,
`forceMemberUpdate`).

*Device Network ID format (frozen):* child DNI stays `"${app.id}.${memberId}"`. Changing it would
orphan every existing child device and recreate them as new devices, detaching all rules. The
per-member model still keys members by `memberId`, so this is naturally preserved — just don't
"clean it up."

*Driver preferences (frozen names — same reason as the PII settings in §10.1):* renaming a preference
resets that user's saved choice to the default on update, silently changing behavior (units flip,
history stops, thresholds reset). Keep every name and its meaning:
`isMiles` (miles vs km/kph), `generateHtml` (build HTML tile attributes), `saveHistory` (location
history — §7.4), `transitThreshold` / `drivingThreshold` (manual speed overrides; `0` = trust
Life360), `avatarFontSize`, `avatarSize` (HTML tile sizing), `logEnable`. Same for the app settings in
§9/§10.1 — the whole settings vocabulary is part of the compatibility surface, not just the device
attributes.

**Where improvement is allowed (behind the contract):**

- *Add* new attributes/commands (purely additive — old rules ignore them, new rules can opt in).
- Change *how* a value is computed or *how often* it's sent, as long as the emitted name/type/value
  vocabulary is unchanged (Hubitat dedupes by value, so this is safe).
- The "badly named but frozen" items (`switch`/`contact`/`acceleration` repurposing, stringy booleans)
  may be *aliased* by adding clearer new attributes (e.g. a real `motion` or `charging` attribute)
  **in addition to** — never instead of — the legacy ones. Document both, mark the legacy ones as
  retained for compatibility.

**Migration safety:** because the rewrite is app-side and the driver contract is frozen, a user
updating both files in place keeps all existing child devices, DNIs, attributes, and rules. No
device re-creation, no rule rebinding. That is the whole point of freezing §7.3.

---

## 8. State model

The per-member + etag model keeps per-member keys (they're the proven mechanism), but v2 **cleans up
the cruft** the fork accumulated around them. Target state:

### Keep
- Setup cache: `circles`, `places`, `members` (refreshed by the slow membership loop, §4.2),
  `accessToken` (OAuth, for `/view` only).
- Account preferences: `unitOfMeasure` (Life360 account units — `"i"`/`"m"` — fetched via `GET /users/me` on install/update/check-token; forwarded to child devices via `getUnitIsMiles()`; overrides each driver's local `isMiles` toggle).
- Session: `cookies`, `deviceId`.
- Per-member (the proven mechanism — kept): `etag-<memberId>` (per-member `If-None-Match`),
  `inflight-<memberId>` (per-member in-flight guard), `transientCount-<memberId>` /
  `backoffUntil-<memberId>` (per-member exponential backoff), `inTransit-<memberId>` (drives dynamic
  polling).
- Scheduling: `lastUpdateMs`, `lastSuccessMs` (newest success across members), `scheduledBaseSecs`,
  `pollIntervalSecs`, `dynamicPollingActive`, `memberInTransit`, `lastMembershipFetchMs` (slow-loop
  rate-limit), `memberCount`.
- Account-level health (shared across members): `failCount`, `tokenLikelyExpired`,
  `rateLimitedUntilMs`, `watchdogWarned`, `message`, `tokenStatus`.
- UI one-shots: `tokenStatusPending`, `forceUpdateStatus(+Pending)`.

### Clean up (cruft removed, mechanism kept)
- The fork's *scattered, inconsistent* per-member backoff bookkeeping (`transientCount-*` /
  `transientUntilMs-*` written in several places) → one consistent helper writing
  `transientCount-<id>` / `backoffUntil-<id>` (§5). Same per-member behavior, one code path.
- `lastCirclesFetchMs` → renamed `lastMembershipFetchMs`; now gates the dedicated slow membership loop
  (§4.2) rather than being folded into a one-big-call tick.
- All the bandaid state from master (skip-etag flags, heuristic-correction scratch vars) → **gone**
  (already removed in the fork; do not reintroduce).

> Note: this is the *opposite* of the earlier draft's "delete all per-member keys" plan — that came
> from the rejected one-big-call model. Per-member keys are the proven design; they stay.

> A full settings/state/attribute table for the new design should be written as `STATE_REFERENCE.md`
> once the code exists. The current STATE_REFERENCE is stale (REVIEW_FOLLOWUPS §2.3) and should be
> regenerated, not patched.

---

## 9. Settings (app preferences)

Carry the fork's organized layout (CODE_REVIEW §5.5). **Setup is gated on real token validity, not
guesswork** — the fork already does a genuine `GET /users/me` check (master half-guesses an expired
token from failed location calls); v2 keeps the real check and uses it to gate the next step:

- **STEP 1 Access Token** — paste bearer, then click **Check Token**, which calls `GET /users/me`. A
  `200` (returns the signed-in user) confirms validity; `401`/`403` means expired/revoked. Persist the
  diagnosis in `state.tokenStatus` so a user opening settings after a failure sees *why* it failed
  (expired/revoked vs transient) without re-clicking (CODE_REVIEW §9.3). **Only on a valid token** do
  the circle/place/member fetches become available — don't let the user pick a circle against a token
  that hasn't been confirmed.
- **STEP 2 Circle** — "Fetch Circles" (`GET /circles`) → picker of all circles on the account.
- **STEP 3 Home** — "Fetch Places" → picker.
- **STEP 4 Members** — "Fetch Members" → multi-select.
- **STEP 5 Verify Connectivity** — "Fetch Locations" (now a single `GET /circles/{id}`) + "✓ last
  successful fetch" age.
- **Polling** — `pollFreq` (10/15/30/60/180/300/0=Disabled), `dynamicPolling`, `dynamicPollFreq`.
- **Notifications** — token-failure devices, enable toggle, repeat interval.
- **Logging** — `logEnable`, `logRawPayload` (verbose, sensitive), `logShowNames` (privacy),
  `logShowMapsLink` (privacy).
- **Map View** — optional Google Maps key (else OSM), `/view` link + revoke.
- **Force Update** — member dropdown + button.

---

## 10. Privacy / PII & security (carry forward — all logging controls kept)

This integration handles **PII**: member real names, exact GPS coordinates, home/place names and
addresses, phone numbers, email addresses, avatars, and battery/charge/WiFi state. Any of it can land
in Hubitat's logs, which users routinely copy-paste into community forum posts when asking for help.
The fork added a set of logging controls specifically to keep PII out of shared logs — **all of them
are retained in v2, by name** (the setting names are part of the §7.3-style compatibility surface; a
user's existing log-privacy choices must carry over on update).

### 10.1 Logging controls — frozen set (keep every one)

| Setting | Type | Default | Controls |
| --- | --- | --- | --- |
| `logEnable` | bool | OFF | **Verbosity.** Debug/trace logging on/off. The normal "how chatty" switch. |
| `logRawPayload` | bool | OFF | **Verbosity + sensitive.** Dumps raw Life360 payloads, partial token, partial cookie head, GPS — the full diagnostic firehose. Explicitly labeled sensitive; warn not to share. |
| `logShowNames` | bool | ON | **PII redaction.** When ON, logs show member/place/circle **names**; when OFF, logs emit opaque **UUIDs** instead. This is the names-vs-UUIDs switch. |
| `logShowMapsLink` | bool | ON | **PII redaction.** When ON, a member's "moving" log line includes a Google Maps link to their exact coordinates; when OFF, the link (and thus the coordinates) is omitted. |

Two orthogonal axes, both kept:

- **Verbosity** (`logEnable`, `logRawPayload`) — *how much* gets logged.
- **PII redaction** (`logShowNames`, `logShowMapsLink`) — *whether identity/location* appears in
  whatever does get logged. These let a user run debug logging **and** still share logs safely.

### 10.2 Redaction rules (carry forward, app + driver)

- `logShowNames` gates **every** name/place/circle string in both the app and the driver. App helpers
  `getShowNamesInLogs()` and the driver's `displayMember(firstName)` fallback-to-memberId implement
  this; keep both (they're also in the §7.3 bridge list). Defaults ON to preserve current behavior;
  OFF must replace *all* names with IDs, not just some (CODE_REVIEW §6.3).
- `logShowMapsLink` gates the coordinates-bearing Maps URL in the driver's movement log (CODE_REVIEW
  §6.4). Speed/distance may still log so users can confirm polling works; the precise lat/lng link is
  what's suppressed.
- `logRawPayload` is the one switch that **intentionally** logs sensitive data — its setting
  description must say so explicitly (GPS, partial token, partial cookie, full payloads) and warn
  against sharing (REVIEW_FOLLOWUPS §4). Never log the **full** bearer token or **full** cookie jar,
  even behind `logRawPayload` — partial/head only.

### 10.3 Other security surfaces (carry forward)

- `/view` URL embeds the OAuth `access_token` — document the leak risk; rotate via the OAuth toggle
  (CODE_REVIEW §6.1).
- Google Maps key is visible in page HTML — restrict by HTTP referrer + API in Cloud Console (§6.2).
- The Life360 bearer token is itself PII-grade (full access to the circle's live locations) — §3.5
  already treats it as a secret credential.

---

## 11. What v2 changes vs. the fork

v2 is **not** a polling-shape rewrite. It keeps the fork's proven per-member + etag model and removes
the cruft around it. The table is mostly *keeps* and *cleanups*, not deletions.

| Fork mechanism | Fate in v2 | Reason |
| --- | --- | --- |
| Async per-member fetch | **Kept** | The proven model (HA + fork). This is the location loop. |
| Per-member `etag-<id>` (`If-None-Match`) | **Kept** | The efficiency core — idle members cost a near-free 304. |
| Per-member in-flight guard `inflight-<id>` | **Kept** | Prevents pile-up; the right tool for per-member polling (fork §7.1). |
| Per-member exponential backoff (§5.3) | **Kept, cleaned up** | Same behavior, expressed as one consistent helper instead of scattered inline logic. |
| `captureCookiesAsync` (buggy merge) | **Kept, merge fixed** | Async capture is proven; fix the whole-jar overwrite to a name→value upsert (§0.3 / REVIEW_FOLLOWUPS §1.1). |
| Circles-poll membership timer (§4.7) | **Kept, formalized** | Becomes the explicit slow membership loop (`/circles/{id}` → `memberCount` + names), mirroring HA's list coordinator. |
| `buildPlacesContext` threading (§4.2/§4.5) | **Kept** | Serialize places once per tick, thread to each member. |
| `createChildDevices` fixes (§3.3/§3.4/§3.5/§4.4) | **Kept** | hub-id `null`, hoisted `getChildDevices`, orphan cleanup. |
| Stuck-`inTransit` heuristics (§2.5) | **Stay deleted** | Trust the payload; force-update gives ground truth if ever needed. |
| Master's bandaid headers / skip-etag hacks (§9.1.1) | **Stay deleted** | The whole reason the fork is cleaner; do not reintroduce. |
| Force-update (§9.1) | **Kept (manual only)** | Manual button + new driver command; auto-trigger deliberately not shipped. |
| Scheduler no-op guard, watchdog, token-expiry notify, privacy toggles, map view, real token check | **Kept** | All genuine improvements. |
| One-big-call (`GET /circles/{id}` for locations) | **Rejected** | Loses per-member 304 economy; unproven. Demoted to the slow membership refresh only. |

Net: v2 = the fork's proven per-member + etag architecture, **formalized into two clean cadences**
(fast location loop + slow membership refresh) with the bandaids, heuristics, and scattered backoff
logic removed. It converges on HA's battle-tested shape rather than betting on a novel one. Everything
that was a *real bug fix or UX/privacy/security improvement* is preserved.

---

## 12. Build & validation plan

1. **Stand up the wire contract first.** Implement `life360BaseUrl`, `getHttpHeaders`, `life360Params`,
   `captureCookies` (name→value merge, §0.3), `clearSessionCache` **byte-identical on the wire to the
   working code**. Verify a single `GET /circles/{id}/members/{mid}` returns 200 with a member+location
   and that cookies accumulate correctly across responses.
2. **Soak the cookie path** against a live circle for hours before building anything else. Confirm no
   403 creep. This is the gate that has killed the integration before.
3. **Confirm per-member 304 behavior** — send `If-None-Match` on `GET /circles/{id}/members/{mid}` and
   verify an idle member returns `304`. This is the efficiency core; confirm it works against the live
   API (the fork already relies on it, so this is verification, not exploration).
4. Build the fast loop: `pollLocations()` → `fetchMemberLocation` (per-member etag + in-flight guard)
   → `handleMemberResponse` → `notifyChildDevice` → driver. Validate presence/location on real members.
5. Add the per-member transient backoff helper (§5) and verify a single flaky member backs off without
   penalizing the healthy ones; verify account-level 401/429 gates pause the whole loop.
6. Build the slow loop: `maybeRefreshMembership()` → `GET /circles/{id}` → `reconcileChildDevices()`
   from `memberCount` + names/avatars (§4.2). Rate-limit to ≤ once/min.
7. Add dynamic polling (rate bump on the per-member loop; flags read after responses land — §4.3),
   scheduler guards, watchdog.
8. Port force-update from the fork (proven); add the `refreshLocation` driver command; make it respect
   account health (token/rate-limit).
9. Port map view, notifications, privacy toggles, settings layout.
10. Regenerate `STATE_REFERENCE.md` from the finished code.
11. **Preserve the token-acquisition procedure (§3.5)** in `README.md` and the STEP 1 help text — it is
    the only known working method and must not be lost in the rewrite.
12. **Verify the §7.3 backward-compat contract before release.** Diff the new driver's `attribute` /
    `capability` / `command` declarations **and both files' `preferences`/settings names** against the
    current code — every legacy name, type, and value vocabulary must still be present. Install the new
    files in place over an existing setup and confirm child devices keep their DNIs and all existing
    Rule Machine rules still fire. Any new attributes/settings must be purely additive.
13. **Add a `CHANGELOG.md` entry** for the rewrite summarizing the architecture (per-member + etag
    location loop + slow membership refresh, cruft removed) and explicitly noting "no breaking changes
    to device attributes/commands/settings" so users updating in place know automations are safe.

> Hubitat has no local test harness; "validation" = install on a real hub against a real circle and
> watch logs. Treat §0/§1 (cookies + headers) as the highest-risk change surface and the thing to
> soak-test longest.
