## GoveeLife Smart Thermometer P1 (https://us.govee.com/products/goveelife-smart-pool-thermometer-with-smart-gateway-1s)

This is a custom driver for a Govee Pool Thermometer.

Govee's official developer API doesn't support this device, so the driver uses the same (undocumented) API
the Govee phone app uses. It signs in with your Govee account to get a token and keeps that token fresh on
its own — there is nothing to capture by hand and nothing to re-paste when it expires.

## Install

**With [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/)** — *Install → Search
by Keywords → "Govee"*, or add `https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/repository.json`
as a custom repository.

Or import the driver by URL: *Developer tools → Drivers Code → New Driver → Import*
`https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/govee/govee-pool.groovy`

## Setup

1. Add a Virtual Device: **Devices → Add Device → Virtual**, pick type **Govee Pool Thermostat**, Save.
2. Fill in your **Govee account email** and **password**, pick a **Refresh Rate**, and hit
   **Save Preferences**.
3. The first login will report *"a verification code has been emailed to you"* — that's expected. Govee
   verifies each new client once.
4. Check your email for the code, then run the **Submit Login Code** command with it. The code is valid for
   about 15 minutes; if it expires, run **Request Login Code** for a new one.
5. Done. From then on the driver logs in again on its own — before the token expires, and after any
   rejection — with no further codes needed.

Commands: **Login** signs in now, **Request Login Code** emails a fresh code, **Submit Login Code** finishes
a login that asked for one.

## Picking the right sensor

If you have more than one Govee temperature sensor on the account, run the **List Devices** command and put
the id you want into the *Govee device id* preference. Otherwise leave it blank.

## Token expiry

Govee tokens are JWTs with a fixed lifetime and there is no refresh endpoint, so the driver just logs in
again before the old one expires. You shouldn't have to do anything, but what it's doing is visible:

| attribute | meaning |
|---|---|
| `authStatus` | `ok`, `expiring`, `expired`, `invalid`, `missing`, `error`, `codeRequired`, `loginFailed` |
| `tokenExpires` | decoded from the token itself |
| `tokenDaysLeft` | days remaining (`-1` if the token has no expiry claim) |
| `lastError` | why it's unhealthy (`none` when fine) |
| `lastUpdated` / `lastUpdatedMs` | when the reading last came in |

Worth a **Rule Machine** rule, so a stuck integration can't go unnoticed:

- **Trigger:** `authStatus` *changed* — **Condition:** `authStatus` is `codeRequired`, `loginFailed` or
  `expired`
- **Action:** notify your phone

Those three are the only states that need a human — everything else the driver recovers from by itself.

## Commands

| command | what it does |
|---|---|
| `Refresh` | poll now |
| `Login` | log in now and fetch a token |
| `Request Login Code` | ask Govee to email a fresh verification code |
| `Submit Login Code` | finish a login that asked for a verification code |
| `Clear Auth Token` | forget the stored token and log in again |
| `List Devices` | log every Govee device on the account with its `deviceId` |

## Troubleshooting

- **`authStatus` is `codeRequired`** — Govee wants a verification code. Check your email and run **Submit
  Login Code**. If the code has expired (~15 minutes), run **Request Login Code** first.
- **`authStatus` is `invalid`** — the token was rejected; the driver signs in again by itself. It keeps
  polling (retrying every 30 minutes) rather than giving up, and `Login` / `Refresh` / Save retries at once.
- **`authStatus` is `loginFailed`** — check the email/password. The driver backs off (15 min → 1 h → 4 h →
  12 h); hitting **Save Preferences** resets that and tries again straight away.
- **"app version too low"** in the logs — Govee raised its minimum. Put a newer version string into the
  *Govee app version header* preference — whatever the current Govee app reports. The defaults are `7.0.30`
  for polling and `7.4.10` for login.
- **Nothing happens at all** — enable *Enable debug logging* and hit **Refresh**; every poll logs the URL,
  a masked token and the result. Passwords, verification codes and full tokens are never logged.
