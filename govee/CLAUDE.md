# govee — driver notes

Single standalone driver: `govee-pool.groovy` (driver name **Govee Pool Thermostat**). No app, no child
devices. Not registered in the root `repository.json` and has no `packageManifest.json`, so it is
**not HPM-installable** — it is imported by raw URL.

Two ways to authenticate: paste a sniffed token (`setAuthToken`), or let the driver log in with the Govee
account email/password and fetch it itself (`useAutoLogin`, default off). See "Automatic login" below.

## Why the official API is not an option

Govee's **official** developer API (`Govee-API-Key` header, `openapi.api.govee.com`) does **not** expose
the GoveeLife Smart Pool Thermometer — it isn't in the supported-device list, and Govee has declined
requests to add it. The only way to read it is Govee's undocumented *app* API:

```
GET https://app2.govee.com/bff-app/v1/device/list
headers: clientId, clientType: 0, appVersion, Host, Authorization: Bearer <JWT>
```

Both header values can be captured from the phone app's HTTPS traffic (HTTP Toolkit on a rooted Android
device — `img.png` is the original capture), or obtained by logging in (see "Automatic login" below).

## Response shape gotchas

- `data.devices[]`, each with `deviceExt.lastDeviceData` and `deviceExt.deviceSettings` — those two are
  JSON **strings** nested inside the JSON, so they need a second parse (`asMap()` handles either shape).
- `tem` is **hundredths of a degree Celsius** (`2650` → 26.50 °C).
- The response contains **every** device on the account. The driver picks one candidate (those with a
  `tem` field) — optionally pinned by the `goveeDeviceId` preference. The original code `sendEvent`ed
  temperature for all of them into the one attribute, so the last device in the array won.
- Auth failures come in **two shapes**: a real HTTP `401`, *and* HTTP `200` with a body of
  `{"status":401,"message":"authorization token is invalid"}`. Both must be handled. This is why the poll
  uses `asynchttpGet` — the synchronous `httpGet` *throws* on 4xx, so a real 401 never reached the
  body-status check and was logged as a generic exception.
- Govee enforces a **minimum `appVersion`** and answers a too-old one with a 400/454 and "The app version
  is too low, please upgrade the version!". Hence the `goveeAppVersion` preference (default `7.0.30`) —
  it can be bumped without a code change.

## Token handling

The token is a real HS256 **JWT**, so `exp`/`iat` are decoded from the payload
(`String.decodeBase64()` — sandbox-safe, url-safe base64 and missing padding are handled) and published as
the `tokenExpires` / `tokenDaysLeft` attributes. `authStatus` goes `ok` → `expiring` → `expired`.
Notifications can't live in a driver (`capability.notification` inputs don't work there — commit
`f48049e` removed them for that reason), so expiry alerting is a Rule Machine rule on `tokenDaysLeft`.

**Storage.** Driver *preference* string inputs 500-error on long values, which is why the token was
originally split across `authToken1`/`authToken2`. It now lives in `state.authToken`, written by the
`setAuthToken(STRING)` command — a command parameter has no such limit (Hubitat staff's recommended
workaround). The two legacy inputs are kept `required: false` for backwards compatibility:

- precedence is **most-recently-changed source wins**
- `reconcileTokenSources()` fingerprints the legacy pair (`"${len}:${last8}"`); a changed fingerprint is
  adopted into `state.authToken`, so the first Save after upgrading migrates with no user action
- the live source is visible in the `tokenSource` attribute
- do **not** flip `authToken1/2` to `type: "password"` — changing the type of an already-saved setting
  risks dropping the value on existing installs
- `normalizeToken()` trims, strips a leading `Bearer `, strips surrounding quotes and removes **all**
  whitespace; an embedded newline from a wrapped paste is otherwise a silent 401

### Automatic login (optional, default off)

Protocol taken from [wez/govee2mqtt PR #656](https://github.com/wez/govee2mqtt/pull/656) — the fix for the
454 wall that broke every client in March 2026. **The v1 login endpoint is dead**; v2 accepts a verification
code:

```
POST /account/rest/account/v2/login        {email, password, client[, code]}
  -> {status: 200, client: {token, tokenExpireCycle, refreshToken, topic, accountId, A, B, ...}}
  -> body status 454 = a verification code is required
  -> body status 455 = the code was wrong or expired
POST /account/rest/account/v1/verification {type: 8, email}     -> Govee emails the code (~15 min validity)
```

Headers for both: `appVersion` (**7.4.10** — the v2 endpoint rejects older), `clientId`, `clientType: 1`,
`iotVersion: 0`, `timestamp` (ms), and a phone-like `User-Agent`
(`GoveeHome/<ver> (com.ihoment.GoVeeSensor; build:8; iOS 18.4.0) Alamofire/5.10.2`). Note the driver polls
`device/list` with `appVersion 7.0.30` / `clientType 0` (what the sniffed capture used, known-working) but
logs in with `7.4.10` / `clientType 1`; the `goveeAppVersion` preference overrides both.

Key invariants:

- **`state.loginClientId` must stay stable.** There is no refresh endpoint — "refreshing" is logging in
  again, and a *new* client id makes Govee demand a new emailed verification code. It is generated once and
  never regenerated (not even by `clearAuthToken`).
- A login-issued token pairs with the login client id; a sniffed token pairs with the sniffed `clientId`.
  `apiClientId()` keeps each pair together. Whether `bff-app/v1/device/list` actually *requires* the
  matching client id is unverified — `handleAuthFailure()` self-heals by retrying once with the sniffed
  clientId (`state.useSniffedClientId`) when a **freshly issued** login token is rejected within 2 minutes.
- 2FA is expected to be **one-time per client id**, not per login — that assumption is what makes unattended
  re-login viable. If a later login returns 454 anyway, the driver requests a fresh code, sets
  `authStatus = codeRequired` and stops retrying until the user runs `submitLoginCode`.
- Re-login triggers: the daily expiry check, a 401 during a poll, and the manual `login` command. There is
  deliberately **no** long `runIn` timer — a daily cron survives reboots, a week-long `runIn` doesn't.
- `reloginThresholdMs()` = 1 day, capped at ¼ of the token's real lifetime (`exp - iat`, or
  `tokenExpireCycle`). Without that cap a short-lived token would sit permanently inside the re-login
  window. `doLogin()` also self-rate-limits to once per 5 min, which is what stops
  `storeToken → parseTokenExpiry → publishTokenStatus → doLogin` from recursing.
- **454 means "verification code required", not "app version too low."** The poll's version check is keyed
  on the message text, not on a status code.
- Never log the password, the verification code, the request body/params, or the full token.

## Scheduling — the bug that started all this

The original `updated()` built `"0 0/${sec/60} * * * ?"`. The Quartz **minute field only accepts 0–59**,
so 1 Hour → `0 0/60`, 3 Hours → `0 0/180`, 5 Hours → `0 0/300` and Never → `0/0 * * * * ?` were all
invalid: `schedule()` **threw** and aborted `updated()` before the immediate `refreshData()` on the next
line. Pasting a fresh token and hitting Save therefore did *nothing at all*. Rules now in force:

- crons come from the `REFRESH_CRON` lookup table; `cronFor()` is a defensive fallback that can never emit
  an out-of-range step (hours use `0 7 0/N * * ?`)
- "5 Hours" was replaced by "6 Hours" (5 doesn't divide 24 — `0/5` leaves a 4-hour gap at midnight); the
  legacy `18000` value still maps to the 6-hour cron
- always `unschedule('handler')` before `schedule()`; bare `unschedule()` only in `updated()`
- schedule **first**, poll **after**, so a scheduling failure can never swallow the immediate refresh
- a 401 must **never** `unschedule()` (the old code stopped polling permanently, recoverable only by a
  Save that then threw on the cron). Instead `state.authRetryAtMs` suppresses polls for 30 minutes at a
  time; `setAuthToken`, `refresh()` and Save all clear it and retry immediately.

## Gotchas (Hubitat)

- `getClass()` is blocked in the sandbox — use `instanceof`.
- `definition(singleThreaded: true)` serializes the async callback against the scheduled job, so `state`
  writes from `deviceListCallback` don't race the poll.
- `java.math.RoundingMode` is avoided; `round1()` uses `Math.round(x * 10) / 10.0d`.
- Errors belong in **attributes** (`authStatus`, `lastError`), not `state` — `state` is invisible to
  dashboards and unusable in Rule Machine.
- This only runs on a hub; local verification is limited to brace/paren balance and reading the diff.
- The user commits changes themselves — don't offer to commit.
