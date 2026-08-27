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
