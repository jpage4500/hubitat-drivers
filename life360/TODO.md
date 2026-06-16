# Life360+ — Open TODO

Single consolidated list of everything still open, merged from the former `CODE_REVIEW.md` and
`REVIEW_FOLLOWUPS.md` (both now removed — their fixed items are done, this is what's left).

Ranked by severity:

- **UNSAFE** — load-bearing correctness; fix before next install.
- **NEEDS CHANGES** — real defect or doc lie; merge-blocking.
- **NIT** — quality / clarity; safe follow-ups.

The load-bearing context still applies and must not be regressed:

- **Cookie path is load-bearing.** `api-cloudfront.life360.com/v3`, exact `User-Agent` / `Accept` /
  `cache-control` and the *absence* of extra headers, and `captureCookies` on **every** response
  (`__cf_bm` / `_cfuvid` — without them Cloudflare 403s within minutes). Any change here needs hours of
  soak testing against a live circle.
- **Bearer token is the only known auth.** Pasted from a signed-in browser session (DevTools → Network
  → `Authorization: Bearer`). No programmatic login.
- **Driver public contract is frozen.** Attribute/capability/command names + value vocabularies, the
  `generatePresenceEvent(member, placesJson, home)` bridge, and the `"${app.id}.${memberId}"` DNI
  format must not change (breaks users' Rule Machine / dashboards on update).

---

## UNSAFE

### U1. `captureCookiesAsync` cookie-jar merge is wrong — can drop `_cfuvid`

`life360_app.groovy` `captureCookiesAsync()` (~L1122):

```groovy
String existing = state["cookies"] ?: ""
if (existing && !existing.contains(cookieVal.split("=")[0])) {
    state["cookies"] = existing + ";" + cookieVal
} else {
    state["cookies"] = cookieVal          // <-- nukes the whole jar
}
```

**Problem:** the `else` branch overwrites the entire jar with a single cookie. The first time `__cf_bm`
rotates on an async response, `_cfuvid` (and anything else previously joined) is lost. Almost every call
is async now (`asynchttpGet` for circles / members / locations / `/users/me`), so this runs constantly —
same class of failure as the May-2026 outage that `3f78018` rescued. **This is the one genuinely
dangerous open item.**

**Fix:** parse the existing jar into a `name -> value` map, upsert this cookie's name, re-serialize.
(The sync `captureCookies()` is correct because it does `responseCookies.join(";")` on a single
response — use it as the reference behavior.)

---

## NEEDS CHANGES

### N1. `dynamicPolling()` reads previous-tick in-transit state after the async refactor

`life360_app.groovy` `fetchLocations()` (~L497): the per-member fetches fire `asynchttpGet` and return
immediately, but `dynamicPolling()` runs synchronously right after the fan-out — so it reads the
*previous* tick's `state["inTransit-<id>"]` flags, never this tick's.

**Effect:** standard→dynamic (and dynamic→standard) rate flips lag one full poll cycle.

**Fix (pick one):**
- `runIn(2, "dynamicPolling")` after the fan-out (simple, latency-tolerant), or
- call `dynamicPolling()` from inside `handleMemberLocationResponse` when a member's `inTransit` flips,
  or
- track expected-vs-completed responses per cycle and call it from the last response handler.

### N2. `state.message` is never cleared on transient network errors

`life360_app.groovy`: `handleMemberLocationResponse` no-status branch (~L546) and
`handleMembersResponse` (~L373) set `state.message = "Network error…"`, but only the 200/304 paths clear
it. A single transient blip (DNS hiccup, Wi-Fi reconnect) leaves the red banner pinned on the settings
page until the user opens settings and hits Done.

**Fix:** clear `state.message = null` whenever `state.lastSuccessMs` is bumped (inside the 200/304
branches, alongside the existing `state.watchdogWarned` clear).

### N3. STATE_REFERENCE.md is significantly stale

`STATE_REFERENCE.md` hasn't kept up with the branch work. Missing / wrong:

- **App settings table:** `notifyTokenExpiry`, `notifyRepeatHours`, `logRawPayload`, `logShowNames`,
  `logShowMapsLink`, `forceUpdateMember` — none listed.
- **App state vars:** `scheduledBaseSecs`, `pollIntervalSecs`, `transientCount-<memberId>`,
  `transientUntilMs-<memberId>`, `watchdogWarned`, `memberCount`, `lastCirclesFetchMs`, `tokenStatus` /
  `tokenStatusPending`, `forceUpdateStatus` / `forceUpdateStatusPending` — none listed.
- **Driver state vars:** `state.locationHistory`, `state.list1` — referenced in code, absent from any
  table.
- **Scheduled jobs row:** "periodically re-arms schedule + re-fetches member list" is obsolete — the
  fixed `fetchMembers` timer was removed; membership is detected via the circles poll-count diff.
- **Driver attributes table:** `lastLocationUpdate`, `powerSource` (and the external-app attrs
  `bpt-history`, `numOfCharacters`, `lastLogMessage`, `lastMap`) are emitted but not declared in the
  driver's `attribute` block — be honest about declared-vs-emergent.
- **Driver commands table:** `historyClearData` clears the entire driver-side history surface
  (`state.locationHistory` + `state.list1`), not just `bpt-history`.
- **Cross-reference (was REVIEW_FOLLOWUPS §3.5):** `lastLocationUpdate` changes only when `address1`
  changes; `lastUpdated` is the per-event timestamp. Add a one-line note so a Rule Machine author isn't
  surprised.
- **Cross-reference (was REVIEW_FOLLOWUPS §3.1):** dynamic polling only engages when
  `pollFreq > dynamicPollFreq` — note this on the `dynamicPolling` row and in the in-app description.

**Fix:** sweep STATE_REFERENCE against current code in one pass.

---

## NIT

### Q1. `handleMembersResponse` calls `notifyChildDevice` without checking `location`

`life360_app.groovy` (~L390): the driver's `generatePresenceEvent` early-returns when
`member.location == null`, so this is benign today — but `/circles/<id>/members` doesn't always include
`location` and a future API change could surface it. Add an explicit `if (member?.location)` guard so
the intent is in the code.

### Q2. `dynamicPolling` guard undocumented in the UI

(Folded into N3, but also a code-adjacent doc nit.) Add a one-line note to the dynamic-polling input
description in `mainPage` that it only engages when `pollFreq > dynamicPollFreq`.

---

## Larger / deferred

### D1. Pass member UUIDs internally; resolve names only at display (privacy hardening)

Member first/last names from the Life360 API are stored in `state.members`, passed as function
arguments (`memberObj.firstName`, `notifyChildDevice`, `generatePresenceEvent`), and written to device
attributes throughout. The current privacy controls (`logShowNames`) only gate **log output** — real
names exist everywhere else, so they can still leak via state dumps, debug output, or future code paths
that don't call `displayMember()`.

**Fix (refactor):** pass `memberId` (UUID) through internal APIs; look up the display name from
`state.members` only at the point of user-visible output (logs, device labels, UI). User-facing device
attributes (e.g. `memberName`) are intentional and stay as-is.

**Status:** not started — larger refactor, not blocking.

### D2. On-failure token check

When `handleMemberLocationResponse` hits the 3rd consecutive 401/403 and is about to set
`tokenLikelyExpired = true`, fire `checkToken()` (`GET /users/me`) inline first. Gives a definitive
diagnosis (real expiry vs. transient auth blip) and populates `state.tokenStatus` so the user sees the
reason next time they open settings — without pressing the Check Token button.

**Status:** planned, not implemented. (The standalone Check Token button + the `updated()` validation
already exist; this only adds the automatic on-failure path.)

---

## Already done / out of scope (for reference — do not redo)

- **Force-update (manual GPS-fix request):** done. Lives in the settings panel (member dropdown +
  "Force Update" button → `forceMemberUpdate()` / `handleForceUpdateResponse()`). Manual only — no
  driver command, no auto-trigger (deliberately: "trust the payload, don't outsmart Life360").
- **One-call get-everything for locations (`GET /circles/{id}`):** rejected. A per-member + etag rewrite
  was investigated and abandoned — the current per-member model already matches the proven Home
  Assistant design. See `APP_SPEC.md` for the (abandoned) rewrite analysis if ever revisited.
- All other CODE_REVIEW items (§2.x bugs, §3.x bugs, §4.x perf, §5.x UX, §6.1–§6.4 privacy/security,
  §8.x minor, §9.1/§9.3 token validation) were marked FIXED before these docs were consolidated.
