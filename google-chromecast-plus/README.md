# Google Chromecast+

Hubitat integration for Google Cast / Chromecast devices

See community post: https://community.hubitat.com/t/beta-google-chromecast/164999

## Features

- **Auto-discovery** of Cast devices on your network (manual IP entry as a fallback)
- **Announcements (TTS)** with optional volume, then auto-restore of whatever was playing
- **Play media** by URL (audio or video)
- **Playback control** — play, pause, stop, next / previous, seek, volume, mute
- **Now-playing status** — title, artist, album art, position, current app, and playback state
- **Launch / stop** Cast apps

- Real-time updates over a persistent connection, or quieter on-demand polling

Reliable announcements: fixes the built-in integration's 2‑second TTS cutoff / first‑word clipping.

----

## Installation

Install via Hubitat Package Manager (HPM) — search for **Google Chromecast+**.

## Setup

- **Apps → Add user app → Google Chromecast+**
- Wait for discovery to find your devices — 15–60s on first open, while the hub fills its mDNS cache in the background.
- Check the devices you want, then click **Done**.

Auto-discovery needs Hubitat firmware **2.4.1.151+**. On older firmware, add devices under **Add a device by IP**.

----

## How it works

Each Chromecast or Google speaker you pick becomes its own device in Hubitat, all grouped under a single **Google Chromecast+** parent device. The parent gives you an at-a-glance summary — how many devices you have and how many are playing right now; each child device is the one you actually control.

Every device looks like a standard speaker to Hubitat, so it works out of the box with rules, dashboards, and the HD+ app. From a rule or dashboard you can:

- **Make an announcement** (text-to-speech) — see below
- **Play media** from a URL (a sound effect, a radio stream, a video)
- **Control playback** — play, pause, stop, next / previous, seek
- **Set volume or mute**
- **See what's playing** — title, artist, album art, and which app is running

### Announcements (text-to-speech)

This is the feature most people use, so here's exactly what happens when a rule or dashboard tells a speaker to "speak" something:

1. **Hubitat turns your text into audio.** The spoken voice comes from Hubitat's own built-in text-to-speech, *not* from this app — so the voice, language, and audio quality are whatever your Hubitat is set up to use. This app just plays the resulting clip.
2. **The speaker is woken up first.** Idle Google/Nest speakers put their amplifier to sleep, and if audio starts the instant they're idle they swallow the first word — you hear "...ont door is open" instead of "Front door is open." To prevent this, the app first plays a brief *silent* clip (a "lead-in") to wake the speaker, then swaps in your message. Displays like the Nest Hub need a little more lead-in than plain speakers, so the app adjusts automatically.
3. **Your message plays.**
4. **Whatever was playing before comes back.** If music was playing when the announcement fired, the app restores it afterward — so an announcement politely interrupts and then resumes.

**Announcement volume.** You can optionally give an announcement its own volume (say, louder for a doorbell alert). If you *don't* set one, the message simply plays at the speaker's current volume and the app doesn't touch the volume at all. Leaving it blank is the quietest option — see [Troubleshooting](#troubleshooting) if you're hearing beeps.

## Advanced / technical details

Skip this unless you like knowing what's under the hood.

- **No bridge, no add-ons.** The driver speaks Google's native Cast protocol (CASTV2) directly to each device over an encrypted socket on port 8009 — there's no external server, Node app, or cloud service in the middle. The logic is modeled after Home Assistant's Cast integration.
- **Discovery** reads the hub's own mDNS cache (`_googlecast._tcp`), which is why it needs Hubitat firmware **2.4.1.151+**. Anything not found automatically can be added by IP. A device's type (speaker / video-display / group) is detected from its advertised capabilities and used to pick the right announcement lead-in.
- **Two connection modes**, set per device: a **persistent connection** for real-time now-playing updates (default), or quieter **on-demand polling** that connects only when needed. A heartbeat keeps the persistent connection healthy.
- **The two classic TTS bugs, fixed.** The built-in integration's ~2-second announcement cutoff is fixed by answering the device's heartbeat pings immediately and only restoring the previous content after a *real* end-of-playback signal (not a transient one). First-word clipping is fixed by the silent lead-in described above — a tiny silent WAV generated on the hub and served from `http://<hub-ip>/local/`.
- **Volume restore.** If an announcement set its own volume, the app puts the previous volume back when it finishes — but only if it actually changed it. An announcement with no volume set sends no volume commands at all (this is what keeps volume-beep-happy devices quiet).
- **Media resume limits.** Content the app cast itself can be truly resumed. Content from a third-party app (Spotify, YouTube, etc.) can only be relaunched — Cast doesn't let an outside controller resume someone else's stream at its exact position.
- **Settings.** Integration-wide: status refresh interval, pre-roll silence on/off, lead-in delay, and debug logging (auto-off after 24h). Per device: real-time-vs-polling and announcement volume.

## Troubleshooting

Most reports are about announcements, so start here.

### The first word is cut off
The speaker's amp is still waking up. Make sure **Pre-roll silence** is on (app settings), then raise the **Lead-in delay** — leave it blank for automatic, or try `1`–`2` seconds. The lead-in delay is integration-wide, so raising it adds a little latency to every device; displays generally need more than plain speakers.

### The announcement cuts off after ~2 seconds
That was the built-in integration's signature bug and should be fixed here. If you still see it, it usually means the connection is dropping — enable debug logging (below) and report it.

### I hear beeps / chirps around the announcement
Those are the **device's own volume-change confirmation sounds** — the same blip you get pressing the volume buttons — and they fire whenever the app changes the volume. They are not the normal Cast "ding."

- If you're **not** setting a per-announcement volume, the current version sends no volume commands, so there should be **no chirps**.
- If you **are** setting an announcement volume, some models beep once as the volume changes. That's the speaker's firmware; it can't be fully suppressed for volume changes. Workaround: leave the announcement volume blank and set the speaker's volume ahead of time with a separate volume action, or accept the single beep.
- On some Nest speakers the beep's pitch rises with the volume level, and speaker **groups** may beep once per member — so the same setup can sound different from one device to the next.

### No audio at all
- Confirm **Hubitat's text-to-speech works** — it's a platform feature. If Hubitat can't produce speech audio, there's nothing for the app to play.
- Make sure the speaker can reach the hub on your LAN: the silent lead-in clip is served *from the hub*, so a device on another subnet or an isolated IoT/guest VLAN may not be able to fetch it.
- Check the device isn't muted or set to zero volume.

### Why do my devices behave differently?
"Cast" spans a lot of hardware — Nest Mini and Nest Audio speakers, Nest Hub displays, Chromecast dongles and Android TVs, and third-party speakers from JBL, Sony, LG, and others. They differ in how deeply they sleep the amp (how much lead-in they need), whether and when they beep on volume changes, how quickly they start playing, and how they handle groups. So a rule that sounds perfect on one speaker may clip or beep on another. Tune the knobs to match your worst-behaving device — announcement volume is per device, and the lead-in delay is integration-wide.

### Reporting an issue
Reproducible issues are the ones that get fixed. To help:

1. Turn on **Enable debug logging** in the app (it turns itself off after 24h).
2. Reproduce the problem once.
3. Open **Logs**, filter to the device, and copy the relevant lines.
4. Include: the device **model** and type (speaker / display / group), how you triggered it (rule, dashboard, HD+, or a manual command), your Hubitat **firmware** version, and the exact symptom (start clipped? end cut off? beep before or after the message? how many beeps?).

Post to the [community thread](https://community.hubitat.com/t/beta-google-chromecast/164999) or open a GitHub issue.

----

## Why write this when a built-in app already exists?

I spent some time with Home Assistant recently and really liked how it auto detected all of my Chromecast devices (Google Mini speakers, Android TV devices, JBL Speakers with cast support).

I remembered Hubitat has a built-in Chromecast app but it never worked very well for me. After reading lots of support threads I had the feeling this might be a good one to tackle. I wrote it with Claude (AI) and modeled the logic after the Home Assistant code. I've been testing primarily with HD+ - my primary use case is just having it report the currently playing media

![image|334x500, 50%](hd1.jpeg)

I can't guarantee it'll be better than the built-in version but if you have issues that I can test/reproduce I'll try to fix them. And it's open source so worst case anyone can use AI to fix any issues that come up in the future.