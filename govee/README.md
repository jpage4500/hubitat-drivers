## GoveeLife Smart Thermometer P1 (https://us.govee.com/products/goveelife-smart-pool-thermometer-with-smart-gateway-1s)

This is a custom driver for a Govee Pool Thermometer.

Govee's official developer API doesn't support this device, so the driver uses the same (undocumented) API
the Govee phone app uses. That means you have to capture two values from the app's traffic yourself.

### Capture the credentials

You will need a rooted Android device or some other way to capture HTTPS traffic (HTTP Toolkit works well).
Open the Govee app and look for a request to `https://app2.govee.com/bff-app/v1/device/list`, then copy:

- the **`clientId`** header
- the **`Authorization`** header value (a long `Bearer eyJ...` token)

![img.png](img.png)

### Configure

1. Add a Virtual Device: **Devices → Add Device → Virtual**, pick type **Govee Pool Thermostat**, Save.
2. Paste the **Client ID** into preferences, pick a **Refresh Rate**, and hit **Save Preferences**.
3. Paste the **whole token** using the **Set Auth Token** command (the field on the command takes the full
   value — no need to split it, and a leading `Bearer ` is stripped for you). It starts polling immediately.

If you have more than one Govee temperature sensor on the account, run the **List Devices** command and put
the id you want into the *Govee device id* preference. Otherwise leave it blank.

### The token expires

Govee tokens are JWTs with a fixed lifetime and there's no reliable way to refresh them automatically, so
eventually you have to re-capture one. The driver tells you when that's due instead of just going quiet:

| attribute | meaning |
|---|---|
| `authStatus` | `ok`, `expiring`, `expired`, `invalid`, `missing`, `error` |
| `tokenExpires` | decoded from the token itself |
| `tokenDaysLeft` | days remaining (`-1` if the token has no expiry claim) |
| `lastError` | why it's unhealthy (`none` when fine) |
| `tokenSource` | whether the live token came from the command or the legacy preference fields |
| `lastUpdated` / `lastUpdatedMs` | when the reading last came in |

To get told about it, make a **Rule Machine** rule:

- **Trigger:** `tokenDaysLeft` *changed* — **Condition:** `tokenDaysLeft <= 3`, or simply trigger on
  `authStatus = expired`
- **Action:** notify your phone

To fix it: capture a fresh token and run **Set Auth Token** again. That takes effect immediately — no need
to touch anything else.

### Commands

| command | what it does |
|---|---|
| `Set Auth Token` | paste the full Authorization token (one field) |
| `Clear Auth Token` | forget the stored token |
| `List Devices` | log every Govee device on the account with its `deviceId` |
| `Refresh` | poll now |

### Troubleshooting

- **`authStatus` is `invalid`** — the token was rejected. Re-capture it. The driver keeps polling (retrying
  every 30 minutes) rather than giving up, and any of `Set Auth Token` / `Refresh` / Save retries at once.
- **"app version too low"** in the logs — Govee raised its minimum. Put a newer version string into the
  *Govee app version header* preference (whatever the current Govee app reports; the default is `7.0.30`).
- **Nothing happens at all** — enable *Enable debug logging* and hit **Refresh**; every poll logs the URL,
  a masked token and the result.
