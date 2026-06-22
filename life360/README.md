# Life360+

Unofficial Hubitat integration that tracks the members of your Life360 circle as Hubitat devices.

## What it is

A pair of files installed on your Hubitat hub:

- **App** ([life360_app.groovy](life360_app.groovy)) — talks to Life360's servers, manages child devices, schedules polling.
- **Driver** ([life360_driver.groovy](life360_driver.groovy)) — one child device per tracked member, exposes their location and phone state as Hubitat attributes.

There is no public Life360 API. Everything in the HTTP path was reverse-engineered, and this integration breaks and gets rescued every time Life360 changes something on their end. The cookie/Cloudflare path is the most fragile part — see [Cookie handling](#cookie-handling-load-bearing--dont-break-this) below.

## What it does

- Per-member location: latitude / longitude / accuracy, current address (or `"Home"` when inside the configured HOME radius), distance from HOME, "since" timestamp.
- Phone state: battery %, charging, WiFi, in-transit, driving, current speed.
- Hubitat presence: each member's child device toggles `present` / `not present` based on the HOME place.
- Optional polling that speeds up when a member is in transit (e.g. 60s normally, 20s while driving).
- Optional all-members map view via a built-in `/view` endpoint (OpenStreetMap by default; Google Maps if a key is provided).
- Optional per-member location history (off by default; up to 100 most-recent points kept in driver state, exposed as the compact `history` attribute).
- Optional HTML tiles for dashboards (avatar, status, address, battery, WiFi, last update).
- Token-expiry push notifications to configured devices when polling hits consecutive auth failures, with optional repeat reminders (2 h / 6 h / 12 h / 24 h / 48 h). If the cause was a transient service issue, the app auto-recovers once Life360 responds normally — no need to re-paste a token.
- **Force Update** — push a GPS fix request to a member's phone from the app settings page; the next poll cycle picks up the fresh location (~5 seconds).
- Speed and distance automatically follow the Life360 account's units preference (imperial or metric); the per-device `isMiles` toggle is a fallback only, used until the app fetches the account preference.

## What it doesn't do

- No official API support — Life360 has never published one. Long outages have happened before (most recently fixed May 2026) and will likely happen again.
- No webhooks. Life360's webhook surface was disabled; the integration polls instead.
- No history retention beyond Hubitat's normal event log. Anything more durable needs an external app or tile.
- No control of Life360 itself (can't add/remove members, can't manage circles, can't trigger crash detection, etc.) — it's read-only.

## Capability repurposing — important if you use Rule Machine

A few standard Hubitat capabilities are repurposed to expose Life360 data:

| Standard capability | What it actually means here |
| --- | --- |
| `Switch` on/off | WiFi connected / not |
| `Contact Sensor` open/closed | Charging / on battery |
| `Acceleration Sensor` active/inactive | In transit OR driving OR away from home |

Full attribute list in [STATE_REFERENCE.md](STATE_REFERENCE.md).

## Privacy & security notes

- **Map View URL contains a live access token.** The `/view` link shown on the app config page is of the form `http://<hub>/apps/api/<appId>/view?access_token=<token>`. Anyone with LAN access to your hub plus that URL can pull every tracked member's live coordinates. Don't share the URL or screenshots of the config page. If the token leaks, disable and re-enable OAuth on the app's source-code editor (Apps Code → Life360+ → OAuth) to rotate it.
- **Google Maps API key is visible in page HTML.** If you set `googleMapsApiKey`, the key is embedded in the `<script src="…?key=…">` tag the browser receives — there is no way to hide it server-side. Lock the key down in Google Cloud Console (APIs &amp; Services → Credentials): add HTTP-referrer restrictions to your hub's hostname/LAN, and API restrictions to *Maps JavaScript API* only. A leaked-but-restricted key is harmless.
- **Logs may include member names, place names, and coordinates** unless you turn off the two privacy toggles under app settings → Logging (*Include Names and Places in Logs*, *Include Google Maps Link in Logs*). Turn both OFF before sharing hub logs publicly.

## Cookie handling (load-bearing — don't break this)

Life360 sits behind Cloudflare. Two cookies it issues — `__cf_bm` (bot-management) and `_cfuvid` —
must be captured from responses and replayed on every request, or Cloudflare starts returning **403**
within minutes and the integration goes completely dark. This is the single most fragile part of the
app and has caused total outages before.

**The cookie jar must be merged per cookie name, not overwritten.** `__cf_bm` rotates periodically
(roughly every 30 min); when it does, Life360 sends a fresh `Set-Cookie: __cf_bm=...` on a normal
response. The old async code did:

```groovy
if (existing && !existing.contains(name)) {
    state.cookies = existing + ";" + cookieVal   // append if name not already present
} else {
    state.cookies = cookieVal                     // <-- BUG: replaces the WHOLE jar
}
```

The `else` branch fires whenever the incoming cookie's name is **already in the jar** — which is
exactly the `__cf_bm` rotation case — and it replaced the entire jar with that single cookie,
silently dropping `_cfuvid`. A couple of requests later, Cloudflare 403s and everyone is cut off. It
failed silently and on a delay, which made it look like "Life360 broke again" rather than a client
bug.

The fix (`mergeCookie()` in [life360_app.groovy](life360_app.groovy)) parses the existing jar into a
`name → value` map, **upserts only the incoming cookie by name**, and re-serializes. A rotating
`__cf_bm` now updates just its own entry and every other cookie (`_cfuvid`) survives. This matches
what the synchronous capture path already did correctly (a single `join(";")` across all `Set-Cookie`
headers of one response).

When debugging cookies, enable **Debug Logging** and watch for `captureCookiesAsync: updated
'__cf_bm'; jar now [_cfuvid, __cf_bm]` — `_cfuvid` staying in the jar after a `__cf_bm` update is the
proof the merge is healthy. On any auth failure the log also prints `jar-at-failure [...]` so you can
tell a token problem (jar intact) from a cookie-path problem (jar missing a Cloudflare cookie).

## Setup

1. Install the app and driver code in Hubitat (Apps Code / Drivers Code → New → paste each file).
2. Add a user app instance: **Apps → Add user app → Life360+**.
3. Obtain and paste your Life360 access token — see [Getting your access token](#getting-your-access-token) below.
4. Click **Check Token** to confirm it's valid.
5. Fetch and pick your circle, HOME place, and which members to track.
6. Choose a poll interval (default 60s) and click Done.

## Getting your access token

There is no official Life360 API. The only known working method is to capture a bearer token from an already-authenticated browser session:

1. In a desktop browser, go to `https://www.life360.com` and **sign in** with your Life360 account (complete any MFA prompts).
2. Open **Developer Tools** (F12 or right-click → Inspect) and select the **Network** tab.
3. Reload the page or navigate around the Life360 web app so API requests appear. Filter for `life360.com` requests.
4. Click any request to `api-cloudfront.life360.com/v3/…` and look at its **Request Headers**. Find the line that starts with `Authorization: Bearer `.
5. Copy **only the token** — the long string after `Bearer ` (not including the word `Bearer` or any quotes or spaces).
6. Paste that token into **STEP 1: Access Token** in the app and click **Check Token** to verify.

**Notes:**
- The token is equivalent to your Life360 login password — treat it as a secret. Don't paste it into forum posts or screenshots.
- Tokens expire server-side (no documented lifetime). When the app shows a "token expired" banner, repeat steps 1–6 to get a fresh token and paste it; clicking **Done** clears the expired flag.
- The app will attempt to auto-recover if the issue was a transient service problem rather than a genuine expiry.

Detailed walkthrough and community support: <https://community.hubitat.com/t/release-life360/118544>

Works with the HD+ dashboard: <https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/location-life360>

## Related files in this repo

- [CHANGELOG.md](CHANGELOG.md) — version-by-version history for both the app and the driver, including user-facing highlights for each release.
- [CHANGES_TECHNICAL.md](CHANGES_TECHNICAL.md) — developer-level delta vs. the upstream baseline (function names, error handling, state changes).
- [STATE_REFERENCE.md](STATE_REFERENCE.md) — every setting, state var, scheduled job, and child-device attribute.
- [APP_SPEC.md](APP_SPEC.md) — clean-room design specification (the code is authoritative; the spec records intent and notes any divergences).

## History

Originally "Life360 with States" → "Life360+" (continuation by Joe Page). See [CHANGELOG.md](CHANGELOG.md) for the version-by-version history of API breaks and rescues.
