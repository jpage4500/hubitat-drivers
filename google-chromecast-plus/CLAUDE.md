# Google Chromecast+ — app notes

Native Hubitat integration for Google Cast devices. **One** driver runs in two roles: **parent**
(created by the app; manages child devices + aggregate status) and **child** (one per Cast device; owns a
TLS socket on port 8009 and speaks the CASTV2 protocol directly — no bridge, no protobuf lib). Protocol
details are in the header comment of `google-chromecast-plus-driver.groovy`.

Three files that must be deployed **together** (they call each other's methods; a version skew can hit a
missing method during `createChild`): `google-chromecast-plus-app.groovy`,
`google-chromecast-plus-parent-driver.groovy`, `google-chromecast-plus-driver.groovy`.

## Discovery & the device list (app)

`scanMdns()` **merges** into `state.discovered` and nothing auto-prunes it, by design: a device that's merely
powered off also drops out of the hub's mDNS cache, and `syncChildren()` deletes any child whose candidate is
gone — so auto-expiry would delete real devices. The checkbox is the only remover, and it is **checked by
default** (`isSelected` returns true unless the setting is explicitly `false`), so anything still in
`state.discovered` gets its child re-created on every Done.

That's what produced the reported "ghost devices keep coming back" (2026-08-20): the DNI is derived from
`uuid ?: mac ?: ip`, and a factory reset, or editing/deleting a speaker or display **group** in Google Home,
mints a new `deviceId` → a new DNI, leaving the old entry listed and re-created forever.

Fix: `scanMdns()` stamps `lastSeenMs` on every entry it sees; `isStale()` flags an mDNS candidate missing for
`STALE_MS` (15 min ≈ 3 missed scans) and `deviceRow()` replaces its status line with *"not seen in mDNS since
…"*. Removing them is the **Forget** button (`forgetStale()`), never automatic.

**Regression fixed 2026-08-21 — read this before touching `isStale`.** The first version treated a *missing*
`lastSeenMs` as stale and had `syncChildren()` silently drop unchecked stale entries on Done. On a hub whose
mDNS read came back **empty** (routine: listener not registered yet, cache not filled, firmware without the
API, post-reboot), every pre-upgrade entry was unstamped → everything showed "not seen in mDNS" with no
timestamp → one Done wiped a user's whole 15-device list, keeping only the checked one. Two guards now, both
required before anything is called stale:

1. the entry has a real `lastSeenMs` — a missing stamp means *unknown*, never *gone* (and `scanMdns` backfills
   unstamped entries on the first scan that returns something, so they get the full grace period); and
2. `state.lastScanFound > 0` — an empty read is no evidence about any single device.

Rules that fall out of it:

- **`syncChildren()` never drops a discovered entry**, checked or not. Unchecking deletes the child device (as
  it always did) and the row stays listed, so a powered-off device can be re-checked later.
- `state.discovered` is the *only* record of these devices, which is why pruning it is a deliberate button
  press: `forgetStale()` drops the flagged entries + their `sel_` settings, and their children go on the next
  Done when `syncChildren` finds them missing from the candidates.
- A **manual** entry never goes stale (`source == 'manual'`), but unchecking one does forget it — nothing
  re-discovers a manual entry, so otherwise the child came back on the next Done.

A [user PR](https://raw.githubusercontent.com/tweas/HubitatPublic/refs/heads/master/Chromecast%2BRemoveStaleDevices)
proposed a separate "Potentially Stale Devices" section with its own `remove_*` checkboxes. Not merged: it put
two opposite-polarity checkboxes on the same device (stale entries stay in `candidates`, so they appeared in
both lists) and duplicated the whole mDNS read + DNI derivation in a second `getCurrentMdnsDevices()`.

## How TTS works (current)

`speak()` / `playText()` → `announce()` → Hubitat `textToSpeech()` (Amazon Polly here; returns an **MP3**,
~22 kHz mono) → a **single** media `LOAD` to the Default Media Receiver (`CC1AD845`) → restore prior content
afterward (mode `restore`/`resume`). `playTrack()` / `playMedia()` play a URL directly (no TTS, no lead-in).

- **Lead-in delay** is a **per-device** driver preference (`leadInDelay`, seconds, default **0**, range 0–5).
  When ≥1, `announce()` prepends an SSML pause `<break time="Ns"/>` to the text (`withLeadIn()`), so the
  returned MP3 *opens with silence*. This is the current fix for first-word/sentence clipping (below). It's
  skipped when the delay is 0 or the text already contains `<break`/`<speak>`.
- There is **no separate silence clip and no swap** anymore — the announcement is one media item.

### SSML in TTS text (brace alias)

TTS text may contain SSML (`<break>`, `<prosody>`, …) and it works from the device page's **Play Text**. It
does **not** work from Rule Machine: RM sanitizes its text fields and strips everything between `<` and `>`
(HTML entities and `&#60;`/`&#62;` are stripped along with it), so the markup never reaches the driver.

Fix (reported 2026-08-21): `unescapeSsml()` in the driver, called from `announce()` **before** `withLeadIn()`,
accepts two bracket-free spellings and converts them back —

- `Hello {break time="2s"/} World` (brace alias, the one that survives RM)
- `Hello &lt;break time="2s"/&gt; World` (also `&#60;`/`&#62;`, for callers that only encode)

Design points, all deliberate:

- **Only real SSML tag names** convert (`SSML_ALIAS_RX` whitelist: break, speak, prosody, emphasis, say-as,
  sub, phoneme, voice, audio, lang, mark, p, s, w). A blanket `{`→`<` swap — the original user hack — means a
  `%variable%` expanding to text with braces turns into garbage markup.
- **Case-sensitive.** SSML is XML: `<BREAK/>` is rejected by the engine and the whole announcement is lost
  (`textToSpeech` returns no uri), whereas leaving `{BREAK/}` as literal text makes the typo audible.
- **Void tags get closed** (`SSML_VOID_RX`): `{break time="2s"}` / `<break time="2s">` → `<break time="2s"/>`,
  since an unclosed void tag also loses the announcement.
- Runs **before** `withLeadIn()` so its "caller already supplied SSML" check sees the converted `<break` and
  doesn't prepend a second pause. The original hack lived *inside* `withLeadIn` — it happened to work only
  because it was the first line, ahead of that function's `sec < 1` early return.
- The parent driver's group `speak()` forwards raw text to each child's `speak()`, so groups are covered too.

## Parent-device broadcast

`broadcast(text, volume)` fans one announcement out to every child (and the `SpeechSynthesis` `speak()`
overloads route here, so the parent can be picked as a single Speak/Notification target).

`broadcastIdle(text, volume)` (added 2026-08-21, from a user request) is the same fan-out filtered by
`isIdleChild()` — announce only where nothing is playing, so a broadcast doesn't talk over someone's music.
Two things the naive `status == "stopped"` filter gets wrong, both handled:

- **`markOffline()` and `initialize()` also set `status` to `stopped`**, so an unreachable device reads as
  idle and the announcement gets queued on a device that can't play it. `isIdleChild()` also requires
  `connectionStatus` to be outside `[offline, disconnected]` (`idle` = never connected yet, still allowed).
- Log the filtered count as `N of M device(s)` — otherwise a broadcast that reached nobody looks identical to
  one that reached everybody.

Known caveat, not worth machinery: `status` only flips to `playing` when the child's LOAD actually reaches
PLAYING, so a device asked to speak a second ago still reads idle and back-to-back broadcasts can double up
on it. The real "speaking now" flag is `state.ttsActive` in the child, which the parent can't read without a
new child method — deliberately avoided (a parent-only update would then break every `broadcastIdle` call
with a missing-method error; attributes are always readable regardless of version skew).

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

### playTextAndResume / -Restore didn't restore anything — FIXED (needs on-hub confirmation)
Reported 2026-08-08: Spotify was playing on a Nest device, `playTextAndResume` spoke, Spotify never came back.
Logs showed the announcement finish, then a `LOAD` of `spotify:track:2noGBukdFPCt7m5ZXnDKor` → **LOAD_FAILED**,
then a `SEEK` → **INVALID_MEDIA_SESSION_ID**.

Cause: `snapshotForRestore()` ran inside `startAnnounce()` — i.e. *after* `ensureApp()` had already launched the
DMR. Launching the DMR **evicts** the app that was playing and `handleReceiverStatus` overwrites
`state.appId`/`transportId` with the DMR's. So the snapshot always read `appId == CC1AD845` and finishTts took
the "we cast this ourselves" branch, LOADing the *third-party* app's private contentId into the DMR. The
third-party branch (`snap.appId != APP_DMR`) was unreachable whenever a launch was needed — which is exactly the
third-party case. The stray `SEEK` came from `resumeSeek`, firing 3 s later with the announcement's already-dead
`mediaSessionId`.

Fix: `ensureApp()` calls `capturePriorApp()` (`@Field priorApp` map, cross-thread) right before the DMR launch;
the action that caused the launch consumes it (`takePriorApp()` in `startAnnounce`/`startMedia`) and it wins over
live state in `snapshotForRestore(prior)`. Also: only an `http(s)` contentId is ever re-LOADed (`isPlayableUrl`),
the resume position rides in the LOAD's `currentTime` instead of a follow-up SEEK (`resumeSeek` deleted), a
second announcement started on top of a running one keeps the original snapshot instead of "restoring" the
previous clip, and `pump()`'s APP_CONNECTED case now also requires `state.appId == APP_DMR` (after a third-party
relaunch the transport belongs to *that* app, so the next announcement must relaunch the DMR).

**Limitation, not a bug:** a Cast *sender* still cannot resume third-party content. The announcement kills the
Spotify/YouTube session, and their contentIds are app-private handles. Best effort is `sendLaunch(prior appId)` —
the app comes back on screen and the phone can re-attach, but playback does **not** restart by itself. Only
content this driver cast itself (an http URL through the DMR) truly resumes. Google's own Assistant broadcasts
duck-and-resume via a privileged firmware path that isn't in the Cast protocol.

### playTrackAndRestore / playTrackAndResume — mode is ignored (open)
`playMedia()` passes `mode` through to `startMedia()`, which never snapshots and never restores — both behave
exactly like plain `playTrack`. Same fix shape as the TTS path (snapshot + finish on the IDLE transition), not
implemented.

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
- **Blocking TLS connect on an unreachable device — MITIGATED.** `interfaces.rawSocket.connect(secureSocket:true)`
  is synchronous with **no connect-timeout option**; a device that drops SYNs (powered but flaky — the user's
  Nest Hub after a Google update; `ping` also fails) makes it block the calling thread for the full OS TCP
  timeout (**~130 s**). Symptom in logs: `method connect/refresh/playText … ran for 130,xxx ms` (warn), and —
  because the app polls children serially on one thread (`pollDevices: kids.each { it.refresh() }`) — a single
  stuck device stalls the whole poll (`pollDevices … ran for 524,010 ms` = several 130 s connects stacked in one
  cycle). Fix: the two **foreground** connect call sites now defer via `runInMillis(100,"connect")` instead of
  calling inline — `refresh()` (poll path) and `pump()`'s IDLE case (command path, e.g. `playText`). The connect
  still blocks ~130 s, but on a scheduler thread, so the app poll and user commands return immediately; queued
  TTS/media wait in `state.pendingActions` and drain when the socket lands (`pump()` re-runs on the transition).
  We **cannot** shorten the 130 s (no timeout knob) or revive a genuinely-unreachable device — the 300 s
  `markOffline` retry reconnects once it returns. `initialize()` is now a full reset (clears `playbackStatus`/
  `status`/`ttsActive`, not just `connectionStatus`) so it no longer leaves the confusing "idle but Playback
  Status still Offline" state the user reported.
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
- A **computed method name** is blocked too: `log."${level}"(msg)` in `logAt`/`logAppAt` compiled for years but
  fails on current firmware with `Expression [MethodCallExpression] is not allowed` — and since that's a
  compile-time SecurityException, the *whole* app fails to load (reported at install, 2026-08-21). Now a
  `switch`; its levels must stay in sync with the `logTrace`/`logDebug`/… wrappers that call it.
- Hubitat `textToSpeech()` here is Amazon Polly (e.g. voice Salli) → MP3, ~22 kHz mono. SSML `<break>` is
  honored on this setup but is **not** guaranteed on every hub/TTS engine — hence lead-in defaults to off and
  is opt-in per device.
- This is a Hubitat driver: it only runs on a hub, can't be compiled/run locally. Local verification is
  limited to brace/paren balance and reading the diff; behavior must be confirmed on the user's hub.
- The user commits changes themselves — don't offer to commit.
