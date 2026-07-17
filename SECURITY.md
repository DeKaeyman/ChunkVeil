# Security Policy

ChunkVeil is a security-oriented plugin: its whole job is preventing hidden underground information from reaching clients. Reports about ways to defeat or destabilize that protection are treated as security issues, not ordinary bugs.

## Supported Versions

Only the latest release receives security fixes.

| Version | Supported |
| --- | --- |
| Latest release ([GitHub Releases](https://github.com/DeKaeyman/ChunkVeil/releases)) | Yes |
| Older releases | No — update to the latest release |

The compatibility table in the [README](README.md#compatibility) lists which Paper, ProtocolLib, and Java combinations each release was verified against.

## What Counts as a Vulnerability

Please report privately (see below) if you find any of the following:

- **Protection bypass** — any way for a client to receive real block data, block entities, entities, or secondary packet information (sounds, explosions, world events) from a chunk that ChunkVeil should currently be hiding, while runtime protection is active.
- **Fail-closed bypass** — any situation where the chunk rewrite path fails but the packet is still sent with real data instead of being cancelled, or where protection silently stops without the documented warnings.
- **Crash or denial of service** — malformed or crafted packets, configs, or manifest data that crash the server or wedge the plugin.
- **Update checker abuse** — any way the update manifest could cause the plugin to do more than display a notice (it must never download or execute anything).
- **Privilege issues** — bypassing `chunkveil.*` permission checks.

## What Is Not a Vulnerability

These are documented, intentional limitations — feel free to open a regular issue to discuss them, but they are not security reports:

- Cave and tunnel shapes being visible with `hide-air: false` (the default; documented trade-off).
- Information above the configured `hide-below-y` cutoff (out of scope by design).
- Another plugin rewriting the same packets after ChunkVeil and restoring real data.
- Combat, movement, or other gameplay cheats — ChunkVeil is not an anti-cheat.
- Protection being off after an explicit `/chunkveil disable` or a loudly-reported fail-closed shutdown.

## How to Report

**Do not open a public GitHub issue for security problems.**

1. Preferred: use GitHub's private vulnerability reporting — [Report a vulnerability](https://github.com/DeKaeyman/ChunkVeil/security/advisories/new).
2. Include: ChunkVeil version, Paper version, ProtocolLib version, Java version, your config (redact anything private), and step-by-step reproduction — ideally with the client/mod used to observe the leak.

## What to Expect

- Acknowledgement within **7 days**.
- An assessment (confirmed / not reproducible / working as documented) within **14 days**.
- Confirmed protection bypasses are fixed with priority and shipped in a dedicated release; the release notes will say a security issue was fixed, with full details published after server owners have had a reasonable window to update.
- Credit in the changelog and advisory if you want it.

## Coordinated Disclosure

Please give us a chance to ship a fix before publishing details or proof-of-concept tooling. In return we commit to acting on confirmed reports quickly and to never downplaying a confirmed leak — if a bypass exists, the changelog and advisory will say so plainly.

## Scope Notes for Reviewers

- The plugin never downloads or executes remote code. The update checker only reads version metadata from `update.json` in this repository and displays a notice.
- bStats telemetry is aggregate and anonymous, and can be disabled (`metrics.enabled: false`).
- The plugin needs no network permissions beyond the update manifest fetch and bStats submission.
