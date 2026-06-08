# Life360+ — Review Followups

Findings from a re-read of [life360_app.groovy](life360_app.groovy), [life360_driver.groovy](life360_driver.groovy), [CODE_REVIEW.md](CODE_REVIEW.md), [STATE_REFERENCE.md](STATE_REFERENCE.md), and [README.md](README.md) on 2026-06-08, after `feature/async-member-fetch` was already in flight. Items here are *not* in CODE_REVIEW yet — they were either missed during the original review, introduced by the async refactor, or are documentation drift between code and the existing review docs.

Same format as [CODE_REVIEW.md](CODE_REVIEW.md): numbered sub-headings, ranked by severity. Severity tags:

- **UNSAFE** — load-bearing correctness; fix before next install
- **NEEDS CHANGES** — real defect or doc lie; merge-blocking
- **NIT** — quality / clarity, can be follow-ups

---

## 1. UNSAFE

### 1.1  `captureCookiesAsync` cookie-jar merge is wrong — can drop `_cfuvid`

[life360_app.groovy](life360_app.groovy) `captureCookiesAsync()` (~L1122)

```groovy
String existing = state["cookies"] ?: ""
// replace existing __cf_bm entry if present, otherwise append
if (existing && !existing.contains(cookieVal.split("=")[0])) {
    state["cookies"] = existing + ";" + cookieVal
} else {
    state["cookies"] = cookieVal          // <-- nukes the whole jar
}
```

**Problem:** the comment says "replace existing entry, otherwise append" but the `else` branch overwrites the entire jar with a single cookie value. The first time `__cf_bm` rotates on an async response, `_cfuvid` (and anything else `captureCookies` joined into the jar earlier) is lost.

Per CODE_REVIEW §1.2, "captureCookies called on **every** API response" is load-bearing — without `__cf_bm` / `_cfuvid` Cloudflare returns 403 within minutes. Almost every call is now async (`asynchttpGet` for circles / members / locations / `/users/me`), so this path runs constantly. This is the same class of failure as the May-2026 outage that `3f78018` rescued.

**Fix:** parse the existing jar into a `name -> value` map, upsert this cookie's name, re-serialize. Sync `captureCookies()` doesn't have this bug because it does `responseCookies.join(";")` on a single response.

**Also:** CODE_REVIEW §8.7 currently claims `captureCookiesAsync` doesn't exist in the fork. It does — defined at ~L1122 and called from 4 places (handleMembersResponse, handleMemberLocationResponse, handleCirclesPollResponse, handleForceUpdateResponse). Re-open §8.7.

---

## 2. NEEDS CHANGES

### 2.1  CODE_REVIEW §4.6 status is a lie

[CODE_REVIEW.md](CODE_REVIEW.md) §4.6 says:

> **Status:** FIXED in branch: feature/async-member-fetch — `memberName` and `avatar` both gated on `device.currentValue()` check before `sendEvent`.

[life360_driver.groovy](life360_driver.groovy) at `generatePresenceEvent` sends `memberName`, `savedPlaces`, and `avatar` unconditionally — the dedup gates were ripped out by commit `102d8f7` ("remove redundant dedup checks; trust Hubitat sendEvent") on the same branch. That decision is defensible (Hubitat dedupes `sendEvent` by value), but the doc still claims the opposite.

**Fix:** rewrite §4.6 status to "Reverted in 102d8f7 — relying on Hubitat sendEvent value-dedup; function-call overhead accepted." Or restore the gates if the §4.6 problem statement (function-call overhead, not just persistence cost) is still seen as worth the code.

### 2.2  `dynamicPolling()` reads previous-tick in-transit state after async refactor

[life360_app.groovy](life360_app.groovy) `fetchLocations()` (~L497):

```groovy
settings.users.each { memberId ->
    fetchMemberLocation(memberId, ctx)   // fires asynchttpGet, returns immediately
}

if (settings.dynamicPolling) {
    dynamicPolling()                     // reads state["inTransit-<id>"] right now
}
```

**Problem:** the async responses set `state["inTransit-${memberId}"]` from inside `handleMemberLocationResponse → notifyChildDevice`, but `dynamicPolling()` runs synchronously right after the fan-out — so it always reads the previous tick's flags, never this tick's.

**Effect:** standard→dynamic flips lag one full poll cycle ("just started moving" stays at `pollFreq` for one extra interval); dynamic→standard flips the same way. Partially defeats §4.7 / §5.3.

**Fix options:**
- `runIn(2, "dynamicPolling")` after the fan-out (simple, latency-tolerant).
- Track expected-vs-completed response count per cycle in state and call `dynamicPolling()` from the last `handleMemberLocationResponse`.
- Just call `dynamicPolling()` from inside `handleMemberLocationResponse` whenever `inTransit` for that member changes.

### 2.3  STATE_REFERENCE.md is significantly stale

[STATE_REFERENCE.md](STATE_REFERENCE.md) hasn't kept up with the work in `feature/async-member-fetch` (and partly `fix/life360-bugs-cleanup-docs`). Missing or wrong:

**App settings table (~L18):** `notifyTokenExpiry`, `notifyRepeatHours`, `logRawPayload`, `logShowNames`, `logShowMapsLink`, `forceUpdateMember` — none listed.

**App state vars:** `scheduledBaseSecs`, `pollIntervalSecs`, `transientCount-<memberId>`, `transientUntilMs-<memberId>`, `watchdogWarned`, `memberCount`, `lastCirclesFetchMs`, `tokenStatus` / `tokenStatusPending`, `forceUpdateStatus` / `forceUpdateStatusPending` — none listed.

**Driver state vars:** `state.locationHistory`, `state.list1` — referenced in code but missing from any state-var table.

**Scheduled jobs row (~L76):** "periodically re-arms schedule + re-fetches member list" is obsolete. The fixed `fetchMembers` timer was removed in §4.7; membership changes are now detected via the circles-pollcount diff.

**Driver attributes table:** `lastLocationUpdate`, `powerSource` are sent via `sendEvent` but not declared in the driver's `attribute` block. Hubitat tolerates this, but if the doc enumerates them, it should be honest about declared-vs-emergent attributes — same caveat for `bpt-history`, `numOfCharacters`, `lastLogMessage`, `lastMap`.

**Driver commands table (~L191):** `historyClearData` is described as "Clears `bpt-history`" but it also clears `state.locationHistory` and `state.list1` — the entire driver-side history surface.

**Fix:** sweep STATE_REFERENCE against the current code in one pass.

### 2.4  `state.message` is never cleared on transient network errors

[life360_app.groovy](life360_app.groovy):
- `handleMemberLocationResponse` no-status branch (~L546) sets `state.message = "Network error for member …"`.
- `handleMembersResponse` (~L373) sets the same on no-status / non-200.

**Problem:** only the 200/304 paths clear `state.message`. A single transient blip (DNS hiccup, Wi-Fi reconnect) leaves the red banner pinned on the settings page until the user opens settings and hits Done (which `updated()` clears).

**Fix:** clear `state.message = null` whenever `state.lastSuccessMs` is bumped (i.e. inside the 200/304 branches alongside the existing `state.watchdogWarned` clear).

---

## 3. NIT

### 3.1  `dynamicPolling` only activates when `pollFreq > dynamicPollFreq` — undocumented guard

[life360_app.groovy](life360_app.groovy) `scheduleUpdates()`:

```groovy
boolean wantDynamic = (settings.dynamicPolling && state.memberInTransit
    && (settings.pollFreq.toInteger() > settings.dynamicPollFreq.toInteger()))
```

If a user sets `pollFreq=10` and `dynamicPollFreq=20` (reasonable on a fast-polling install where dynamic should *slow down* during driving), dynamic polling silently never engages. STATE_REFERENCE and the in-app description don't mention this guard.

**Fix:** add a one-line note to the dynamic-polling input description in `mainPage` and to STATE_REFERENCE's `dynamicPolling` row.

### 3.2  Force Update result is lost on async race vs. page render

[life360_app.groovy](life360_app.groovy) `mainPage()` clears `forceUpdateStatus` via the same one-shot pending pattern used for `tokenStatus`. That pattern works for `checkToken` because the token check is synchronous `httpGet` — the result is set before the page re-renders. `forceMemberUpdate` uses `asynchttpPost`; the page renders showing `"Sending…"` and the user's next page open clears the pending flag *before* the async response has had a chance to update the status.

**Fix:** drop the one-shot clear for `forceUpdateStatus`, or stamp it with a timestamp and clear only when older than e.g. 60s.

### 3.3  `handleMembersResponse` calls `notifyChildDevice` without checking `location`

[life360_app.groovy](life360_app.groovy) ~L390:

```groovy
settings.users.each { memberId ->
    def externalId = "${app.id}.${memberId}"
    if (!getChildDevice("${externalId}")) {
        def member = state.members?.find { it.id == memberId }
        notifyChildDevice(memberId, member)
    }
}
```

The driver's `generatePresenceEvent` early-returns when `member.location == null`, so this is benign today — but `/circles/<id>/members` doesn't always include `location`, and a future API change could surface it. Add an explicit `if (member?.location)` guard so the intent is in the code.

### 3.4  `5.6.1` change description is misleading after the dedup-removal commit

The version-comment block has been moved to [CHANGELOG.md](CHANGELOG.md), but the underlying narrative drift is still in CODE_REVIEW. §4.5's "Status: FIXED — `buildPlacesContext()` serializes once" is correct. §4.6's "Status: FIXED — gated on `device.currentValue()`" is wrong (see 2.1 above).

Treat 2.1 and the §4.5 / §4.6 reconciliation as a single doc-cleanup pass.

### 3.5  Driver `lastLocationUpdate` semantics are subtle

`lastLocationUpdate` is updated only when `address1` changes, not when lat/lng changes — STATE_REFERENCE's "When `address1` last changed" wording is correct but easy to misread as "last location update", which is what `lastUpdated` actually is.

**Fix:** add a one-line explicit cross-reference between the two attributes in STATE_REFERENCE so a Rule Machine author isn't surprised.

---

## 4. Resolved while writing this doc

- **Driver in-file changelog future-dated to 06/30/26 and missing all branch work.** Resolved by [CHANGELOG.md](CHANGELOG.md) — the in-file `Changes:` blocks were removed from both files and replaced with a pointer to CHANGELOG.md. The `5.1.4 - 06/30/26 - add history preference` entry was preserved verbatim in the historical Driver list since it came in from upstream `master`, not from these branches.
- **App in-file changelog `5.3.1` claimed a behavior that was reverted on the same branch.** Same resolution — CHANGELOG `Y.Y.Y` entry summarizes the net behavior of the branch instead of relisting per-version detail that no longer matches the code.
- **`forceMemberUpdate` dumped URL, partial bearer token, partial Cloudflare cookie head, User-Agent, full request/response payloads at `info` on every press.** Originally flagged as a NEEDS CHANGES item; intentional during the §9.1 Cloudflare WAF debugging push. Resolved by gating the credential-bearing and full-payload chatter behind `logRawPayload` (same toggle that gates raw payload logging in the driver). Kept one info-level "sent for X" line in `forceMemberUpdate` and one info-level SUCCESS / warn-level FAILED summary in `handleForceUpdateResponse`. The `logRawPayload` setting description was rewritten to explicitly call out that it logs sensitive data (GPS, partial token, cookie head, full payloads) and shouldn't be shared.
