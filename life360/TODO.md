# Life360 — Audit Findings 2026-06-22 (full 9-sweep)

---

## Bugs

- [ ] **BUG-1** — `handleException()` (app:681-684) calls `notifyTokenExpired()` before `scheduleUpdates()`, so `unschedule()` inside `scheduleUpdates()` cancels the reminder job that was just registered. Note: `handleMemberLocationResponse` line 622 already has the correct order — the bug is only in `handleException`. Fix: swap the two calls in `handleException` to match line 622.
- [ ] **BUG-2** — 304 response does not reset `failCount`, `tokenLikelyExpired`, or `rateLimitedUntilMs`. Two prior 401s + one 304 + one more 401 = false `tokenLikelyExpired`. Fix: add `state.failCount = 0`, `state.tokenLikelyExpired = false`, `state.rateLimitedUntilMs = null` to the 304 block to match the 200 block.
- [ ] **BUG-3** — `Retry-After` header read case-sensitively on line 629 (`response.headers?.get('Retry-After')`) but `l360-etag` right next to it is read case-insensitively. If the gateway returns `retry-after` lowercase, the fallback of 60s is used silently. Fix: use `.find { it.key?.equalsIgnoreCase("Retry-After") }?.value`.
- [ ] **BUG-4** — `"not set"` avatar sentinel (driver:226) leaks into map view as a broken `<img src='not set'>`. The `?: ""` guard in `renderView` doesn't help because `"not set"` is truthy. Fix: change sentinel to `""` in the driver, or filter it out in `renderView`.

---

## Error handling

- [ ] **EH-1** — `handleMembersResponse` and `handleForceUpdateResponse` access `response.json` without a try-catch. If Life360/Cloudflare returns an HTML error page, this throws an uncaught exception in the async callback. Other handlers already guard this. Fix: wrap `response.json` access in try-catch in both handlers.
- [ ] **EH-2** — `handleMembersResponse` on network error (line 457-459) logs `log.error` but does not set `state.message`. Inconsistent with every other error path. Fix: add `state.message = "fetchMembers: network error: ..."`.
- [ ] **EH-3** — `handleCirclesPollResponse` non-200 status is only logged at `log.debug` behind `logEnable` (line 1043). A persistent Cloudflare block on the circles poll would be invisible unless debug logging is on. Fix: upgrade to `log.warn` unconditionally.

---

## Dead code

- [ ] **DEAD-1** — `count` in `getHistory()` (driver:460) — declared, incremented at line 489, never read. Delete it.
- [ ] **DEAD-2** — `getLogEnable()` in app (line 833) — defined but never called. Delete it.

---

## Redundancy

- [ ] **REDUNDANT-1** — `getHistory()` re-rounds lat/lng values (lines 468-469) that `saveLocationHistory()` already rounded before storing. The second round is always a no-op. Remove the redundant `Math.round` calls in `getHistory()`.
- [ ] **REDUNDANT-2** — `sendHistory()` (driver:544-545) converts `state.list1` (a List) to a joined String then immediately splits it back into an array. `lines1` could just be `state.list1` directly. Delete the `join`/`split` roundtrip.
- [ ] **REDUNDANT-3** — `sendTheMap()` (driver:595) declares `String lastMap = "${theMap}"` only to pass it to `sendEvent` on the next line. The local variable adds nothing. Pass `theMap.toString()` directly.

---

## Minor / Low

- [ ] **MINOR-1** — `toBool()` returns `false` for a native JSON boolean `true` (evaluates `true == "1"`). Silent failure if Life360 API changes from `"0"`/`"1"` strings to native booleans. Fix: check `instanceof Boolean` first.
- [ ] **MINOR-2** — Cookie tokenizer splits on `';|,'` (both semicolon and comma) in `captureCookies` and `captureCookiesAsync`. Commas are valid inside cookie values per RFC 6265. Should split on `;` only.
- [ ] **MINOR-3** — Debug log at driver:208 hard-codes `mph` label regardless of unit setting. Cosmetic log-only issue.
- [ ] **MINOR-4** — `address1prev` initialized to `"No Data"` in driver `installed()` but immediately overwritten with `null` on the first `generatePresenceEvent` call because `device.currentValue('address1')` is null on a brand-new device. The initialization is never visible.
- [ ] **MINOR-5** — `updated()` (app:858) clears `tokenLikelyExpired`, `failCount`, and `rateLimitedUntilMs` but does not clear `state.message`. If an error banner is showing and the user hits Done, the red message persists until the next successful poll. Fix: add `state.message = null` in `updated()`.
- [ ] **MINOR-6** — Stale `state["inTransit-${memberId}"]` keys are never removed when a member is deselected. `createChildDevices()` deletes the child device but leaves the key. `dynamicPolling()` only iterates `settings.users` so behavior is correct, but the keys accumulate forever. Fix: remove the key in the orphan-cleanup loop in `createChildDevices()`.
- [ ] **MINOR-7** — `(circle.memberCount ?: "0").toInteger()` (app:1051) — if Life360 returns the integer `0` for an empty circle, Groovy's `?:` treats `0` as falsy and substitutes `"0"`. Result is the same, but the intent is wrong: `?: "0"` is meant to handle `null`, not zero. Fix: use `circle.memberCount?.toInteger() ?: 0`.

---

## Magic literals

- [ ] **ML-1** — `60000L` in `handleTimerFired` (app:978) — 1-minute membership poll threshold in ms. Should be a named constant (e.g. `CIRCLES_POLL_INTERVAL_MS`).
- [ ] **ML-2** — `3600` in `scheduleTokenExpiryReminder` (app:729) — hours-to-seconds multiplier. Should be a named constant (e.g. `SECS_PER_HOUR`).
- [ ] **ML-3** — `18` in `sendHistory` (driver:551) — `HUBITAT_TILE_MAX_CHARS - 18`. The 18 is the byte length of the `</table></div>` closing tag. Unexplained inline. Should be a named constant or a comment.
- [ ] **ML-4** — `10` in `sendHistory` (driver:542) — max number of log lines in the history tile. Should be a named constant (e.g. `MAX_HISTORY_LOG_LINES`).
- [ ] **ML-5** — `2.23694`, `3.6`, `1.609344` (driver:208, 286-287) — m/s→mph, m/s→kph, km→mi conversion factors appear inline. Should be named constants.

---

## Summary

| Category       | Count |
|----------------|-------|
| Bug            | 4     |
| Error handling | 3     |
| Dead code      | 2     |
| Redundancy     | 3     |
| Minor          | 7     |
| Magic literals | 5     |
| **Total**      | **24**|
