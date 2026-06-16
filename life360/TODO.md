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

### D2. On-failure token check

When `handleMemberLocationResponse` hits the 3rd consecutive 401/403 and is about to set
`tokenLikelyExpired = true`, fire `checkToken()` (`GET /users/me`) inline first. Gives a definitive
diagnosis (real expiry vs. transient auth blip) and populates `state.tokenStatus` so the user sees the
reason next time they open settings — without pressing the Check Token button.

**Status:** planned, not implemented. (The standalone Check Token button + the `updated()` validation
already exist; this only adds the automatic on-failure path.)
