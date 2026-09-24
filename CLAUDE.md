# hubitat-drivers — repo notes

A collection of independent Hubitat packages, one per top-level folder (e.g. `google-chromecast-plus/`,
`go2rtc/`, `life360/`). Each package ships a `packageManifest.json` and is registered once in the root
`repository.json` for install via Hubitat Package Manager (HPM). Some packages have their own `CLAUDE.md` with
package-specific notes — read that folder's `CLAUDE.md` first when working inside a package.

## Package manifests & the `id` fields (HPM)

The `id` fields in `packageManifest.json` (each app/driver) and in `repository.json` (each package) are just
**random UUIDv4s** — opaque, arbitrary identifiers HPM uses to track an installed package for updates. They
carry no meaning and don't have to match anything external.

- The `hpm` CLI (`manifest-add-app` / `manifest-add-driver` / `repository-add-package`) generates these with a
  random UUID. Generating one by hand with `uuidgen` is **exactly equivalent** — same kind of value, same
  behavior. The two will never produce the *same* string (both are random), and that's fine.
- **The only rules:** each `id` must be unique within the repo, and must stay **stable once the package is
  published/installed** — changing it makes HPM treat it as a different package. Note `hpm manifest-create`
  **overwrites** the manifest with fresh ids, so never re-run it on an already-published package.
- So: hand-written (`uuidgen`) manifests and CLI-generated ones are interchangeable. Pick whichever; just keep
  ids stable after release.

The canonical CLI workflow (and the exact commands used for every existing package) lives in `hpm-usage.txt` —
follow that pattern when adding a new package.

## Conventions

- **No computed method names.** `log."${level}"(msg)` — and any `obj."${name}"()` — is rejected by the hub's
  Groovy sandbox at compile time (`Expression [MethodCallExpression] is not allowed`), which takes the whole
  app/driver down at install. Dispatch with a `switch` instead. The `logAt`/`logAppAt` helpers in every
  package were converted on 2026-08-21 (`google-chromecast-plus`, `go2rtc`, `govee`) — copy the switch form,
  not the old one-liner, into any new package.
- Drivers/apps only run on a Hubitat hub — they can't be compiled or run locally. Local verification is limited
  to brace/paren balance, JSON validity, and reading the diff; behavior must be confirmed on a hub.
- Namespace is `jpage4500`; author "Joe Page".
- The user commits changes themselves — don't offer to commit.

## CHANGES.txt

Every fix or feature appends **one line** to `CHANGES.txt` in the repo root, at the end of the file,
under a bare `MM/DD` header for the current day — add the header only for that day's first entry:

```
09/09
- Rooms: long-press any room to open the tile's edit dialog
```

Write the line as part of making the change rather than sweeping it up afterwards. Work that changes
no code adds nothing.

- **One line per change, no wrapping.** Two changes worth mentioning separately get two lines; don't
  join them with a semicolon or an em dash to keep the count down.
- **High level and behavioural** — what someone using the app would notice, not which files moved
  or how it was done. No file names, selector ids, or rationale; those belong in the commit message.
- **The headline and nothing after it.** Aim under 80 characters, and never past 100. The failure
  mode is a good first clause followed by a colon, comma or dash and then everything the feature can
  do — the fix is to delete from that punctuation onward, not to shorten the sentence evenly.
- **Prefix the line with the part of the app that changed** — a screen, a platform, a feature area
  (`Rooms:`, `iOS:`, `Settings:`) — unless the prefix would name the whole app, which says
  nothing: no `Weather:` in a weather app, no `Files:` in a file manager. Those start with the verb.
- **A fix says what stopped happening**, in the same voice as everything else:
  `- Radar: fixed the app quitting a few seconds after a tile refreshed`.
- **A line earns its place by being something new**, not by explaining something already listed. A
  consequence of a feature, a limit it has, or a rule it follows is not its own entry — the reader
  finds those out by using it. Nothing that starts with "also" or "and now", or restates a feature
  with a caveat attached.

```
- Rooms: long-press any room to open the tile's edit dialog                   ← yes
- Rooms: long-press any room to open the tile's edit dialog — handy when the tile title is hidden
- Devices: track device stats over time and graph them in a new Stats screen  ← yes
- Devices: track battery, temperature and free space over time and graph them in a new Stats
  screen, with filters by OS, model and carrier
```
