# Life360+ — Open TODO

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

### D2. Names/avatars refresh from membership poll

The once-per-minute circles poll detects roster changes via `memberCount` diff and calls
`fetchMembers()` when it changes. It does not refresh names or avatars on existing child
devices. Names rarely change in practice, but a renamed member would only update when their
location response comes back with a 200.

---

## Bugs found, not yet fixed

### B1. `settings.pollFreq` NPE on fresh install (app, ~line 895)

`scheduleUpdates()` calls `settings.pollFreq.toInteger()` unconditionally. On a brand-new
install, `settings.pollFreq` is null until the user opens the app and hits Done — so
`installed()` → `scheduleUpdates()` throws NPE and the scheduler is never armed.

**Fix:** `(settings.pollFreq ?: "60").toInteger()` and same for `dynamicPollFreq`.

### B2. HTML tile shows speed in m/s, labelled MPH/KPH (driver, ~line 396)

The tile uses the raw `speed` value (m/s from Life360) but appends "MPH" or "KPH". The
already-converted `speedUnits` variable should be used instead.

**Fix:** replace `speed` with `speedUnits` in the tile speed display line.

### B3. HTML tile reads `since` from stale device state (driver, ~line 373)

`long sEpoch = (device.currentValue('since') ?: 0L) as long` re-reads the previously
stored device attribute instead of using the `since` variable already computed earlier in
`generatePresenceEvent`. On a brand-new device this produces epoch 1970.

**Fix:** use the local `since` variable directly.

### B4. `transitThreshold`/`drivingThreshold` NPE if driver preference is null (driver, ~line 288)

`transitThreshold.toDouble()` throws if the preference is null (fresh device before Done
is pressed, or driver update before user opens settings).

**Fix:** `(transitThreshold ?: 0).toDouble()` and same for `drivingThreshold`.

### B5. `dynamicPolling()` race: concurrent async callbacks can corrupt `memberInTransit` (app, ~line 1272)

`dynamicPolling()` zeroes `state.memberInTransit = false` then iterates all members to
recompute it. If two members' async responses arrive and both call `notifyChildDevice` →
`dynamicPolling()` in quick succession, the second call zeros the flag after the first
already set it true, potentially leaving dynamic polling disengaged even though a member
is in transit.

**Note:** Hubitat Groovy async callbacks may be serialized per-app; if so this is
theoretical. Worth investigating before fixing.

### B6. Transient/backoff errors write `state.message` red banners for self-resolving conditions (app)

401/403 (failCount < 3), 429, and 5xx all write `state.message`, putting a red banner on
the settings page. These conditions are handled by backoff and clear on the next success —
the banner is misleading and persists until a 200 or 304 arrives. Consider suppressing
`state.message` writes for conditions already covered by backoff, or giving them a TTL.
