# Google Chromecast+ — app notes

Native Hubitat integration for Google Cast devices. **One** driver runs in two roles: **parent**
(created by the app; manages child devices + aggregate status) and **child** (one per Cast device; owns a
TLS socket on port 8009 and speaks the CASTV2 protocol directly — no bridge, no protobuf lib). Protocol
details are in the header comment of `google-chromecast-plus-driver.groovy`.

Three files that must be deployed **together** (they call each other's methods; a version skew can hit a
missing method during `createChild`): `google-chromecast-plus-app.groovy`,
`google-chromecast-plus-parent-driver.groovy`, `google-chromecast-plus-driver.groovy`.

## How TTS works (current)

`speak()` / `playText()` → `announce()` → Hubitat `textToSpeech()` (Amazon Polly here; returns an **MP3**,
~22 kHz mono) → a **single** media `LOAD` to the Default Media Receiver (`CC1AD845`) → restore prior content
afterward (mode `restore`/`resume`). `playTrack()` / `playMedia()` play a URL directly (no TTS, no lead-in).

- **Lead-in delay** is a **per-device** driver preference (`leadInDelay`, seconds, default **0**, range 0–5).
  When ≥1, `announce()` prepends an SSML pause `<break time="Ns"/>` to the text (`withLeadIn()`), so the
  returned MP3 *opens with silence*. This is the current fix for first-word/sentence clipping (below). It's
  skipped when the delay is 0 or the text already contains `<break`/`<speak>`.
- There is **no separate silence clip and no swap** anymore — the announcement is one media item.

## TTS issues & status

### Chirps around announcements — FIXED
High-pitched staccato beeps at the start and/or end of a `speak`. These are the **device's own
volume-change confirmation beeps**, triggered by `SET_VOLUME`. `finishTts()` used to send two *unconditional*
SET_VOLUMEs per restore-mode announcement (volume restore + a mute restore that was always a no-op — the
announce path never mutes). Fix: restore volume only if the announcement actually set one
(`state.ttsVolumeApplied`), and drop the mute restore. A `speak` with no announcement volume now sends
**zero** SET_VOLUMEs → no chirp. Confirmed in device logs.

### First-word / first-sentence clipping on Nest Hub (display) — FIX IMPLEMENTED, needs on-hub confirmation
Start of an announcement cut off (up to a whole first sentence) on a Nest Hub; fine on Nest Mini speakers.

Ruled out during investigation:
- NOT a generic display cold-start — **Play Track** of an MP3 plays clean on the Hub.
- NOT (solely) the old pre-roll **swap** — it clipped even with pre-roll OFF (a single LOAD).
- The confounds between the clean Play Track and the clipped Speak were never fully separated: file encoding
  (the "clean" test used an online-tool MP3, **not** Hubitat's Polly output), **serving** (GitHub CDN vs the
  hub's local file server — every clean playback we saw was externally served), the `SET_VOLUME` sent right
  before the load, and the LOAD's metadata `title:"Announcement"` vs Play Track's empty title.

Fix (the Chime TTS approach, done natively): bake the lead-in silence **into the TTS clip** via the SSML
`<break>` instead of playing a separate silence item and swapping. One media item, no swap → the device's
cold-start clip lands on the leading silence and the speech survives. It's a **mitigation that works
regardless of the exact root cause.** `<break time="2s"/>` was confirmed working on the reporting user's hub.
**To use it, set that device's Lead-in delay to ~1–2s** (default 0 = off). The root cause of *why* a
hub-served Speak clips on the Hub was never pinned down; the isolation tests to nail it were never run on the
Hub: (a) Play Track the hub's own `http://<hubIP>/tts/<hash>.mp3` URL — clean vs clipped separates file/serving
from the Speak path; (b) Speak with the announcement volume blank — tests the pre-load `SET_VOLUME`.

### Old pre-roll silence — REMOVED
Previously: generate an 8 kHz silent WAV, host it on the hub (`/local/`), LOAD it, then swap in the TTS on
PLAYING (with a lead-in hold for displays). Removed entirely — the swap (and the WAV→MP3 codec/sample-rate
switch) is what clipped displays, and it depended on the device reaching the hub's HTTP server. Replaced by
the SSML break. The app-level "Pre-roll silence" toggle and "Lead-in delay" setting were removed with it
(lead-in is now per-device).

## Related findings (came up during TTS debugging)

- **Request-id race — FIXED.** `nextRequestId()` did a non-atomic `state.requestId` read-modify-write; the
  socket (`parse`) thread and the scheduled poll raced and handed two messages the same id (observed a `7038`
  collision). Now a per-device `AtomicInteger` in the `@Field reqId` map.
- **"Won't announce until you hit Initialize" — DIAGNOSED, not auto-fixed.** Cast receivers silently prune
  idle senders; TCP + heartbeat PONGs keep the socket *looking* alive, so the 35 s watchdog never fires and
  the `connect()` guard (bails if `isConnectedState()`) blocks self-recovery. Initialize is the only path that
  force-sets `state.conn = IDLE` + fresh handshake. The `ttsStartCheck` watchdog now *logs* this case but does
  not recover from it. A real fix: have the heartbeat periodically issue its own GET_STATUS (detect app-level
  death despite live PONGs), and/or reconnect+retry when an announcement LOAD never reaches PLAYING.
- **Chime before media on cold start** — Google's Cast session-start earcon, played when the Default Media
  Receiver is *launched* fresh (Google tears the idle DMR down after a few minutes). The driver already avoids
  it when the DMR is warm (`ensureApp` reconnects instead of relaunching). Not suppressible via the standard
  protocol; keeping the DMR alive to avoid it has worse side effects. Expected behavior.

## Diagnostics (visible with debug logging on)

- `conn: X -> Y (why)` — every connection-state transition (`setConn`).
- `TTS: begin (conn=…, transport=…, mode=…)` — at the start of each announcement.
- `TTS: audio sent but speaker never started …` (warn) — `ttsStartCheck`, if a TTS LOAD never reaches PLAYING
  within 5 s (stale/dropped session).

## Conventions / gotchas (this driver)

- Cross-thread state (touched by both `parse()` on the socket thread and scheduled jobs) MUST live in
  `@Field static` maps keyed by `device.id`, never in `state` — `state` writes race. See `rxBuf` / `lastRx` /
  `reqId` / etc.
- `getClass()` is blocked in the Hubitat sandbox — use `instanceof`.
- Hubitat `textToSpeech()` here is Amazon Polly (e.g. voice Salli) → MP3, ~22 kHz mono. SSML `<break>` is
  honored on this setup but is **not** guaranteed on every hub/TTS engine — hence lead-in defaults to off and
  is opt-in per device.
- This is a Hubitat driver: it only runs on a hub, can't be compiled/run locally. Local verification is
  limited to brace/paren balance and reading the diff; behavior must be confirmed on the user's hub.
- The user commits changes themselves — don't offer to commit.
