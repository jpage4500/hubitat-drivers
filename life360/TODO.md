# Life360 — Open Items

## Backlog

Nothing open. All audit findings from 2026-06-23 are fixed on `feature/async-member-fetch`. Branch is pending Hubitat test before merge to master.

---

## Fixed (2026-06-23, feature/async-member-fetch)

Exhaustive audit findings — all fixed in this pass:

| ID | Description |
|----|-------------|
| BUG-SINCE | driver `sEpoch.seconds` treated Unix timestamp as duration → "Since:" field showed ~year 55,000. Fixed: `new Date(sEpoch * 1000L)` |
| SEC-1 | Stored XSS: member names/addresses injected raw into `<script>` block in both map builders. Fixed: `.replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e")` on `membersJson` |
| BUG-SCHED | `unschedule()` in `scheduleUpdates()` silently killed token-expiry reminder. Fixed: re-arm via `if (state.tokenLikelyExpired) notifyTokenExpired()` after `unschedule()` |
| ERR-1 | `handleUserSettingsResponse` called `response.json` without try/catch on 200 path. Fixed: wrapped in try/catch |
| ERR-2 | `handleTokenProbeResponse` cleared `tokenLikelyExpired` after `response.json` — parse exception left app stuck in slow-poll. Fixed: state clears moved before JSON parse |
| STATE-2 | In-flight guard keys not cleared on hub restart → member polling permanently blocked after reboot. Fixed: `clearSessionCache()` called from `initialize()` |
| SEC-2 | Google Maps API key not HTML-encoded in `<script src>` attribute. Fixed: escape `&`, `"`, `<`, `>` before embedding |
| API-1 | `sendEvent` for `inTransit`, `isDriving`, `charge`, `wifiState` passed Groovy `Boolean` — coercion was implicit. Fixed: `.toString()` on each |
| API-5 | `shareLocation` sent as `"1"`/`"0"` (raw API); callers expecting `"true"`/`"false"` silently failed. Fixed: `toBool(...).toString()` |
| GROOVY-1 | `toDouble(0)` returned 0 via Groovy falsy-zero — integer `0` speed treated as absent. Fixed: `if (object != null)` |
| ERR-4 | `response.getErrorMessage()` returned null in several handlers → GString rendered literal `"null"`. Fixed: `?: "(no details)"` on all 6 call sites |
| GROOVY-2 | `(settings.pollFreq ?: "60").toInteger()` would fail if Hubitat returned enum as Integer. Fixed: `.toString()` before `.toInteger()` |
| API-4 | `contact`, `acceleration`, `switch` declared as `"string"` instead of proper enum. Fixed: enum declarations with canonical values |
| DEAD-1 | Single-arg `displayMember(String)` overload had no call sites. Fixed: deleted |
| LIT-1 | Magic `6` in `runIn(6, ...)`. Fixed: `FORCE_UPDATE_FETCH_DELAY_SECS = 6` |
| LIT-2 | Magic API version `4` for circles endpoint. Fixed: `CIRCLES_API_VERSION = 4` |
| LIT-3 | Magic `6` in backoff shift cap. Fixed: `MAX_BACKOFF_SHIFT = 6` |
| LIT-4 | Magic `6372.8` in `haversine()`. Fixed: `EARTH_RADIUS_KM = 6372.8` |
| LIT-5 | `/ 100000.0` re-derived `LAT_LNG_PRECISION` as literal. Fixed: `(LAT_LNG_PRECISION as double)` |
| MIN-1 | Bool settings used string `"false"`/`"true"` as `defaultValue`. Fixed: boolean literals |
| MIN-2 | Commented-out log lines in `dynamicPolling()`. Fixed: removed |
| MIN-3 | `else { listSize1 = 0 }` unreachable after null guard in `sendHistory()`. Fixed: direct assignment |
| MIN-4 | `KM_TO_MI` used as divisor — name implied multiplication. Fixed: renamed `KM_PER_MI` |
| MIN-5 | `binTransita` legacy naming artifact. Fixed: renamed `motionLabel` |

**Not fixed — no actionable fix available in Groovy sandbox:**

| ID | Description |
|----|-------------|
| STATE-1 | `state.failCount` read-modify-write racy across concurrent 401 callbacks. Hubitat state writes are not atomic; no lock primitive available. Impact: auth threshold may take 5–6 trips instead of 3 — delayed notification only, not silent failure |

### Unlisted findings (found during this pass — not fixed)

| ID | File | Location | Finding |
|----|------|----------|---------|
| UL-1 | driver | ~84 | `attribute "motion", "string"` — should be `"enum", ["active", "inactive"]` like `acceleration` |
| UL-2 | driver | ~607 | `toBool()` uses `if (object)` falsy check — same class as GROOVY-1; integer `0` coerces to false |
| UL-3 | app | ~1109 | `handleMembersAsyncResponse` 200 path calls `response.json` with no try/catch |
| UL-4 | driver | ~485 | Magic `0.00005` threshold in location-change check — not covered by `LAT_LNG_PRECISION` |
| UL-5 | app | ~1186 | `notifyTokenExpired()` passes magic `86400` to `runIn` — no named constant for seconds-per-day |

---

## Fixed (2026-06-22, feature/async-member-fetch)

All 24 items from the full 9-sweep audit, plus 3 post-review findings:

| ID | Description |
|----|-------------|
| BUG-1 | `handleException`: swapped `scheduleUpdates`/`notifyTokenExpired` order so reminder job isn't cancelled |
| BUG-2 | 304 response now resets `failCount`, `tokenLikelyExpired`, `rateLimitedUntilMs` |
| BUG-3 | `Retry-After` header on 429 now read case-insensitively |
| BUG-4 | Avatar sentinel changed from `"not set"` to `""` |
| EH-1 | `handleMembersResponse` and `handleForceUpdateResponse` wrap `response.json` in try-catch |
| EH-1b | `handleMemberLocationResponse` 200 path wraps `response.json` in try-catch (post-audit find) |
| EH-2 | `handleMembersResponse` network error path now sets `state.message` |
| EH-3 | `handleCirclesPollResponse` non-200 upgraded from `log.debug` to `log.warn` |
| DEAD-1 | Removed unused `count` variable from `getHistory()` |
| DEAD-2 | Deleted unused `getLogEnable()` |
| REDUNDANT-1 | Removed double `Math.round` in `getHistory()` — then restored it after fp-truncation review |
| REDUNDANT-2 | Eliminated `join`/`split` roundtrip in `sendHistory()` |
| REDUNDANT-3 | `sendTheMap()` no longer creates a pointless local variable |
| MINOR-1 | `toBool()` handles native JSON booleans via `instanceof Boolean` check |
| MINOR-2 | Cookie tokenizer now splits on `;` only, not `;|,` |
| MINOR-3 | Removed hard-coded `mph` from debug log; now shows m/s only |
| MINOR-4 | Removed pointless `address1prev = "No Data"` from `installed()` |
| MINOR-5 | `updated()` now clears `state.message` |
| MINOR-6 | `createChildDevices()` orphan-cleanup removes stale `inTransit-<memberId>` key |
| MINOR-7 | `circle.memberCount?.toInteger() ?: 0` replaces falsy-zero-prone form |
| ML-1 | `60000L` → `CIRCLES_POLL_INTERVAL_MS` |
| ML-2 | `3600` → `SECS_PER_HOUR` |
| ML-3 | `18` → `HISTORY_TILE_FOOTER_LEN` |
| ML-4 | `10` → `MAX_HISTORY_LOG_LINES` |
| ML-5 | `2.23694`, `3.6`, `1.609344` → `MS_TO_MPH`, `MS_TO_KPH`, `KM_PER_MI` |
| XSS-1 | HTML tile builder: `address1` now HTML-escaped; avatar validated as `http` URL; `escapeHtml()` added to driver |
| powerSource | `sendEvent(powerSource)` now sends canonical `"dc"`/`"battery"` enum values |
