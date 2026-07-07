- [Life360+](#life360)
  - [What it does](#what-it-does)
  - [Setup](#setup)
  - [Getting your access token](#getting-your-access-token)
  - [Privacy \& security](#privacy--security)
  - [Related files](#related-files)
  - [History](#history)

# Life360+

Unofficial Hubitat integration that tracks the members of your Life360 circle as Hubitat devices.

**Community support and walkthrough:** <https://community.hubitat.com/t/release-life360/118544>

## What it does

A pair of files — **app** ([life360_app.groovy](life360_app.groovy)) and **driver** ([life360_driver.groovy](life360_driver.groovy)) — installed on your Hubitat hub. The app polls Life360 and manages child devices; the driver exposes each tracked member's data as Hubitat attributes.

Per member: latitude/longitude/accuracy, current address (or `"Home"` inside the configured HOME radius), distance from HOME, battery %, charging, WiFi, in-transit, driving, speed, and a "since" timestamp. Each member's child device also reports Hubitat presence (`present` / `not present`) based on the HOME place.

Optional extras: dynamic polling (faster when a member is moving), map view of all members via a built-in `/view` endpoint, per-member location history, HTML dashboard tiles, token-expiry push notifications with auto-recovery, and **Force Update** (push a GPS fix request to a member's phone on demand).

**Works with the HD+ dashboard:** <https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/location-life360>

_There is no official Life360 API — everything was reverse-engineered. Occasional outages happen when Life360 changes something on their end._

## Setup

1. Install the app and driver in Hubitat (Apps Code / Drivers Code → New → paste each file).
2. Add an instance: **Apps → Add user app → Life360+**.
3. Obtain and paste your Life360 access token — see [Getting your access token](#getting-your-access-token) below.
4. Click **Check Token** to confirm it's valid.
5. Fetch and pick your circle, HOME place, and which members to track.
6. Choose a poll interval (default 60 s) and click Done.

## Getting your access token

There is no official Life360 API. The only known working method is to capture a bearer token from an already-authenticated browser session:

1. In a desktop browser, go to `https://www.life360.com` and sign in (complete any MFA prompts).
2. Open **Developer Tools** (F12 or right-click → Inspect) and select the **Network** tab.
3. Reload the page so API requests appear. Filter for `life360.com` requests.
4. Click any request to `api-cloudfront.life360.com/v3/…` and look at its **Request Headers**. Find `Authorization: Bearer `.
5. Copy only the token — the long string after `Bearer ` (not the word `Bearer`, no quotes, no spaces).
6. Paste it into **STEP 1: Access Token** in the app and click **Check Token**.

The token is equivalent to your Life360 password — treat it as a secret. Tokens expire server-side (no documented lifetime); when the app shows a "token expired" banner, repeat steps 1–6 and paste the fresh token. The app will auto-recover if the cause was a transient service issue rather than a genuine expiry.

## Privacy & security

- **Map view URL contains a live access token.** Anyone with LAN access to your hub and that URL can pull every tracked member's live coordinates. Don't share it or screenshot it. If the token leaks, disable and re-enable OAuth (Apps Code → Life360+ → OAuth) to rotate it.
- **Google Maps API key is visible in page HTML.** If you set one, lock it down in Google Cloud Console: HTTP-referrer restriction to your hub's hostname, API restriction to _Maps JavaScript API_ only.
- **Logs may include names, places, and coordinates.** Turn off _Include Names and Places in Logs_ and _Include Google Maps Link in Logs_ (app settings → Logging) before sharing hub logs publicly.
- Cloudflare cookies are load-bearing — missing them causes 403s and polling goes dark. See [CHANGES_TECHNICAL.md](CHANGES_TECHNICAL.md) if you're debugging auth failures.

## Related files

- [CHANGELOG.md](CHANGELOG.md) — version history, user-facing highlights.
- [CHANGES_TECHNICAL.md](CHANGES_TECHNICAL.md) — developer-level detail: security fix IDs, bug IDs, API/attribute notes.
- [STATE_REFERENCE.md](STATE_REFERENCE.md) — every setting, state variable, scheduled job, and attribute.
- [APP_SPEC.md](APP_SPEC.md) — design intent; the code is authoritative.

## History

Originally "Life360 with States" → "Life360+" (continuation by Joe Page). See [CHANGELOG.md](CHANGELOG.md) for the full version history.
