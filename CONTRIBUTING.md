# Contributing to ChunkVeil

Thanks for your interest in improving ChunkVeil. This document explains how to build the project, what kinds of contributions are welcome, and the rules that keep a security-oriented plugin trustworthy.

## Before You Start

- **Bugs**: open a [GitHub issue](https://github.com/DeKaeyman/ChunkVeil/issues) using the bug report template. Include ChunkVeil, Paper, ProtocolLib, and Java versions, your config, logs, and reproduction steps.
- **Security problems** (protection bypasses, leaks, crashes from crafted packets): do **not** open a public issue. Follow [SECURITY.md](SECURITY.md).
- **Features**: open an issue first to discuss. ChunkVeil deliberately stays a visibility-based underground concealment plugin — see "Scope" below.

## Building

Requirements:

- JDK 21 (the jar targets Java 21 so it runs on both Paper 1.21.x and 26.x)
- Git

```bash
./gradlew shadowJar
```

The distributable jar lands in `build/libs/`. bStats is shaded and relocated automatically.

To launch a local Paper test server with the plugin preinstalled:

```bash
./gradlew runServer
```

Note: ProtocolLib is a hard dependency at runtime; drop a compatible ProtocolLib jar into the test server's `plugins/` folder (see the [README compatibility table](README.md#compatibility)).

## Project Layout

Everything lives in `com.dekaeyman.chunkveil`:

| Area | Files |
| --- | --- |
| Plugin lifecycle, fail-closed handling | `ChunkVeilPlugin` |
| Packet interception | `ProtocolChunkListener` |
| Chunk palette / block-state rewriting | `ChunkPacketBlockRewriter`, `NmsBlockStateIds` |
| Reveal engine, queues, entity scans | `VeilEngine`, `PlayerVeilState`, `VeilChunkUpdate` |
| Config and language | `VeilSettings`, `VeilWorldSettings`, `VeilLang` |
| Commands and events | `VeilCommand`, `VeilListener`, `UpdateNotifyListener` |
| Metrics, telemetry, updates | `VeilMetrics`, `VeilTelemetry`, `UpdateChecker` |

## Ground Rules

These are non-negotiable for pull requests:

1. **Never weaken fail-closed behavior.** If a chunk packet for a protected world cannot be rewritten safely, it must be cancelled — never sent as-is. No "best effort" fallback modes, even behind a config flag.
2. **No silent protection loss.** Any code path that disables protection must log it and keep it visible (`/chunkveil status`, `/chunkveil compat`, admin notifications).
3. **No absolute claims in docs.** Words like "impossible", "zero leaks", "guaranteed", or "all underground data" are banned. Describe what is covered, under which conditions, and what is not.
4. **No scope creep.** No punishments, cheat detection heuristics, combat/movement checks, economy or claims logic, auto-updating jars, or GUI frameworks.
5. **Main-thread discipline.** Bukkit world/entity access stays on the main thread unless you can prove the API is thread-safe. Don't move work async casually.
6. **Match the existing style.** Plain Java, no new runtime dependencies without discussion, small focused classes, package-private where possible.

## Pull Requests

- One logical change per PR.
- Describe **how you tested it**: which Paper version, which ProtocolLib build, what you did in-game (xray/freecam client, `/chunkveil status`, `/chunkveil compat`, `/chunkveil inspect`). Untested packet-path changes will not be merged.
- Update `CHANGELOG.md` under an "Unreleased" heading and any affected docs (`README.md`, `docs/MODRINTH.md`, `docs/SPIGOT.md` — they must stay consistent with each other).
- CI must build cleanly (`./gradlew shadowJar`).
- If your change affects packet handling, describe exactly which packet types are affected and what happens on failure.

## Scope

ChunkVeil is packet-level underground concealment: hide underground terrain, block entities, entities, and related packet information until the player can realistically observe them, and fail closed when that cannot be done safely.

Good contribution areas: packet coverage, reveal quality, performance and scheduling, diagnostics and admin tooling, tests and fixtures, documentation accuracy.

Out of scope: anything that turns ChunkVeil into a general anti-cheat, moderation tool, or gameplay plugin.

## License

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
