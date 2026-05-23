# Life360+

Unofficial Hubitat integration that tracks the members of your Life360 circle as Hubitat devices.

## What it is

A pair of files installed on your Hubitat hub:

- **App** ([life360_app.groovy](life360_app.groovy)) — talks to Life360's servers, manages child devices, schedules polling.
- **Driver** ([life360_driver.groovy](life360_driver.groovy)) — one child device per tracked member, exposes their location and phone state as Hubitat attributes.

There is no public Life360 API. Everything in the HTTP path was reverse-engineered, and this integration breaks and gets rescued every time Life360 changes something on their end. See [CODE_REVIEW.md](CODE_REVIEW.md) §1 for the triage history and which parts of the code are load-bearing.

## What it does

- Per-member location: latitude / longitude / accuracy, current address (or `"Home"` when inside the configured HOME radius), distance from HOME, "since" timestamp.
- Phone state: battery %, charging, WiFi, in-transit, driving, current speed.
- Hubitat presence: each member's child device toggles `present` / `not present` based on the HOME place.
- Optional polling that speeds up when a member is in transit (e.g. 60s normally, 20s while driving).
- Optional all-members map view via a built-in `/view` endpoint (OpenStreetMap by default; Google Maps if a key is provided).
- Optional per-member location history (off by default; up to 100 most-recent points kept in driver state, exposed as the compact `history` attribute).
- Optional HTML tiles for dashboards (avatar, status, address, battery, WiFi, last update).

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

## Setup

1. Install the app and driver code in Hubitat (Apps Code / Drivers Code → New → paste).
2. Add a user app instance: **Apps → Add user app → Life360+**.
3. Paste your Life360 `access_token` (sign in at `life360.com`, open DevTools → Network, look for the bearer token in the request headers).
4. Pick your circle, your HOME place, and which members to create child devices for.
5. Choose a poll interval (default 60s).

Detailed walkthrough and community support: <https://community.hubitat.com/t/release-life360/118544>

Works with the HD+ dashboard: <https://joe-page-software.gitbook.io/hubitat-dashboard/tiles/location-life360>

## Related files in this repo

- [CODE_REVIEW.md](CODE_REVIEW.md) — known bugs, performance items, UX gaps, security notes.
- [STATE_REFERENCE.md](STATE_REFERENCE.md) — every setting, state var, scheduled job, and child-device attribute.

## History

Originally "Life360 with States" → "Life360+" (continuation by Joe Page). See the change log at the top of [life360_app.groovy](life360_app.groovy) for the version-by-version history of API breaks and rescues.
