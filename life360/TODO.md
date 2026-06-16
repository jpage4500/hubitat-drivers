# Life360+ — Open TODO

## NEEDS CHANGES

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
