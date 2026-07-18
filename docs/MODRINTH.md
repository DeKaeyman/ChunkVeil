![ChunkVeil banner](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-1.png)

# ChunkVeil

ChunkVeil is a free, open-source **Paper + ProtocolLib anti-xray and anti-ESP protection plugin** focused on underground chunk leaks.

Most anti-xray tools focus on ores. ChunkVeil goes further: it helps hide underground chunks, cave shapes, hidden rooms, storage areas, block entities, entity spawns, and later block updates before modified clients can use that information.

It is built for server admins who want stronger protection against xray, ESP, freecam scouting, cave discovery, hidden-base discovery, and PieChart-style underground leaks without adding a full gameplay anti-cheat.

ChunkVeil reduces what the client can learn. It does not claim to make every hacked client impossible to use.

## Why Use ChunkVeil?

![More than ore obfuscation](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-3.png)

- **Anti-xray for more than ores** - Hide underground terrain, ores, caves, and base layouts.
- **Anti-ESP support** - Optionally hide underground entities while their chunk is hidden.
- **Freecam resistance** - Underground chunks stay masked until the player can realistically see or reach them.
- **Packet-level protection** - While protection is active, hidden chunk packets are rewritten before they are sent. A hidden chunk never leaves the server with real underground block data in it; a packet that cannot be rewritten safely is cancelled instead of sent.
- **Block update protection** - Later block changes are also masked while a chunk is hidden.
- **Block entity protection** - Hidden block entity update packets below the protected Y range are cancelled.
- **Secondary leak protection** - Cancels hidden underground explosions, world events, block cracks, sounds, particles, and vibrations, and sanitizes concealed light data.
- **View-based reveals** - Uses a 360-degree visibility scan instead of revealing everything in a simple radius.
- **Adaptive scan quality** - Optional TPS-aware ray reduction helps busy servers keep tick time predictable.
- **Per-world config** - Configure fake blocks, hidden Y ranges, air hiding, entity hiding, and player hiding per world.
- **Fail-closed startup/runtime** - If ProtocolLib or raw chunk rewriting is not compatible, ChunkVeil disables protection instead of pretending it is safe.
- **Tested, not just claimed** - An automated packet regression test suite runs in CI, and `/chunkveil verify` gives owners a single PASS/WARN/FAIL protection check. Exact coverage: [docs/COVERAGE.md](https://github.com/DeKaeyman/ChunkVeil/blob/main/docs/COVERAGE.md)
- **Admin tools** - Includes status, compatibility diagnostics, player inspect, diagnostic reports, performance prediction, reload, refresh, debug metrics, permissions, and emergency disable.
- **Update notifications** - Admins get an in-game notice with a download link when a newer release compatible with the server's exact Minecraft version is available.
- **Open source** - MIT licensed and easy to audit.

## Requirements

![Requirements](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-2.png)

- Paper 1.21.x or 26.x
- Java 21 or newer; Java 25 is required for Paper 26.x and the currently pinned ProtocolLib development build
- ProtocolLib compatible with your Paper/Minecraft version

ProtocolLib version matters. Pick the build that matches your server version:

- Paper 1.21.4 - 1.21.8: [ProtocolLib 5.4.0](https://github.com/dmulloy2/ProtocolLib/releases) (stable release)
- Paper 1.21.9 - 1.21.11 and 26.x: the newest ProtocolLib release or [development build](https://github.com/dmulloy2/ProtocolLib) that lists your exact version ([Hangar versions](https://hangar.papermc.io/dmulloy2/ProtocolLib/versions))

ChunkVeil ships as a single universal jar. **Verified** means this exact combination was run on a real server with this ChunkVeil release. **Expected** means it should work but was not run before release.

| Server | ProtocolLib | Java | Status |
| --- | --- | --- | --- |
| Paper 26.1.2 | newest dev build for 26.1 | 25 | Verified (ChunkVeil 0.5.0) |
| Paper 1.21.11 | newest build for 1.21.11 | 21+ historically; current dev build requires 25 | Verified (ChunkVeil 0.5.0) |
| Paper 1.21.8 | 5.4.0 | 21+ | Verified (ChunkVeil 0.5.0) |
| Paper 26.2 | matching ProtocolLib development build | 25 | Expected, not manually verified |

The repository's [`release-metadata.json`](https://github.com/DeKaeyman/ChunkVeil/blob/main/release-metadata.json) is the canonical machine-readable source for build versions, pinned compatibility artifacts, checksums, and update releases.
| Other Paper 1.21.x / 26.x | matching build for that version | 21+ / 25 | Expected, not verified |
| Spigot, Folia, pre-1.21 | - | - | Unsupported |

If an *expected* combination misbehaves, ChunkVeil is designed to fail closed rather than leak, and `/chunkveil verify` will tell you what went wrong. `/chunkveil compat` remains an alias.

## How It Works

![How ChunkVeil works](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-4.png)

1. Underground data starts hidden from the player.
2. Hidden underground blocks are replaced with a configurable fake block.
3. ChunkVeil scans what the player can realistically reveal using view rays.
4. Real chunks are restored when they become visible or reachable.
5. Later hidden block/entity updates are masked or cancelled where possible.

ChunkVeil's core rule: **while protection is active, a hidden chunk never leaves the server with real underground block data in it.** Chunk blocks, block entities, and concealed light are committed atomically. Startup initialization failure stops the server by default. A runtime parser/rewriter failure cancels the current packet and quarantines subsequent protected traffic without restoring real chunks or stopping the running server. An explicit administrator disable still restores chunks normally.

It does not protect terrain above your cutoff, infer or conceal the world seed, detect player behaviour, or control plugins that rewrite packets later. Light is sanitized per full 16-block section, so align `hide-below-y` to a multiple of 16 for a clean boundary.

ChunkVeil is primarily designed for the overworld. Nether and End can be configured, but they are disabled by default because their terrain and fake block choices usually need separate testing.

## Visual Comparison

These screenshots use an xray-style view so the difference is easy to see.

### Without ChunkVeil

![Underground terrain exposed without ChunkVeil](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/chunveil-pre.png)

With ChunkVeil disabled, underground terrain, caves, ores, structures, and hidden spaces can be visible to modified clients before the player should know about them.

### ChunkVeil with `hide-air: false`

![ChunkVeil hiding underground blocks while leaving air visible](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/chunkveil-air.png)

This is the recommended default. Air stays air, so caves and empty pockets may still appear as open space, but solid hidden blocks are replaced with the configured fake block, such as `DEEPSLATE`. This is faster and reduces the most useful block information without rewriting huge amounts of air.

### ChunkVeil with `hide-air: true`

![ChunkVeil hiding underground blocks and air](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/chunkvail-no-air.png)

When `hide-air` is enabled, ChunkVeil also replaces underground air with the fake block. This makes cave shapes, rooms, and hidden base layouts much harder to read from the client side, but it costs more because many more blocks need to be rewritten.

## Compatibility With Paper Anti-Xray

ChunkVeil can run alongside Paper's built-in anti-xray and packet-based plugins such as Orebfuscator. Paper anti-xray usually runs before ProtocolLib sees the outgoing chunk packet, and ChunkVeil then applies its underground hiding pass to the packet the player is about to receive.

For the strictest protection, test your exact plugin stack with `/chunkveil status`, an xray/freecam client, and both `hide-air: false` and `hide-air: true` depending on how much cave/base shape you want to conceal.

## Installation

1. Install Paper 1.21.x or 26.x.
2. Install Java 21 or newer. Use Java 25 for Paper 26.x or the currently pinned ProtocolLib development build.
3. Install a ProtocolLib build compatible with your Paper version.
4. Put `ChunkVeil.jar` in your server's `plugins` folder.
5. Start the server once to generate `plugins/ChunkVeil/config.yml` and `plugins/ChunkVeil/lang.yml`.
6. Run `/chunkveil status` in-game or from console.

## Recommended Default

```yaml
worlds:
  world:
    enabled: true
    hide-below-y: 0
    min-y: -64
    default-fake-block: DEEPSLATE
    hide-air: false
    hide-entities: true
    hide-players: false
```

`hide-air: false` is recommended for most servers because it is much lighter. Use `hide-air: true` when hiding cave shapes and hidden base layouts is more important than performance.

## Commands

- `/chunkveil status` - Shows runtime state, worlds, queue size, rewrite status, and metrics.
- `/chunkveil verify` - One PASS/WARN/FAIL check that distinguishes INITIALIZED, EXERCISED, and TRIPPED protection and reports health by packet category. (`/chunkveil compat` is an alias.)
- `/chunkveil inspect <player>` - Shows a player's current ChunkVeil state, visible chunks, queue count, view distance, and bypass state.
- `/chunkveil report` - Creates a sanitized report with versions, config checksum, packet health, timings, plugin stack, and anonymized runtime state.
- `/chunkveil predict <players> <ramGb> <cpuTier> [viewDistance]` - Experimental current-workload estimate from live timings; not a guaranteed player capacity.
- `/chunkveil update` - Checks now whether a newer compatible release is available.
- `/chunkveil reload` - Reloads config and language files. Add `--check` to validate the config from disk without applying it.
- `/chunkveil refresh` - Forces a rescan and refresh for online players.
- `/chunkveil disable` - Emergency switch that restores real chunks for online players.
- `/chunkveil enable` - Starts the runtime again.
- `/chunkveil debug on|off` - Toggles debug metrics.
- `/chunkveil version` - Shows the plugin version.

Alias: `/cv`

## Permissions

- `chunkveil.admin`
- `chunkveil.status`
- `chunkveil.verify`
- `chunkveil.inspect`
- `chunkveil.report`
- `chunkveil.predict`
- `chunkveil.update`
- `chunkveil.reload`
- `chunkveil.refresh`
- `chunkveil.toggle`
- `chunkveil.debug`
- `chunkveil.version`
- `chunkveil.bypass`

## Good First Test

1. Join with an admin account.
2. Run `/chunkveil verify` and read the PASS/WARN/FAIL verdict.
3. Run `/chunkveil status` for counters.
4. Go underground below the configured `hide-below-y`.
5. Move in and out of caves or tunnels.
6. Test `/chunkveil inspect <yourname>`.
7. Test `/chunkveil report` before reporting bugs.
8. Test `/chunkveil refresh`.
9. Test `/chunkveil disable` to restore real chunks for online players.
10. Use `/chunkveil debug on` while testing.

## Update Notifications

ChunkVeil periodically checks a small version manifest on GitHub and tells admins (permission `chunkveil.update`) when a newer release is available **for their exact Minecraft version**. Releases that do not declare compatibility with the running server version are never offered.

The checker only reads version metadata. It never downloads or installs anything. Disable it with `update-checker.enabled: false` in `config.yml`.

## Anonymous Usage Statistics

ChunkVeil uses [bStats](https://bstats.org/plugin/bukkit/ChunkVeil/32677) to collect anonymous aggregate usage statistics: server count, player counts, Minecraft/Java versions, and a few ChunkVeil-specific charts such as hide-air usage. No world data, no player data, and no server address is ever sent.

Opt out with `metrics.enabled: false` in `config.yml`, or globally for all plugins in `plugins/bStats/config.yml`.

![ChunkVeil usage statistics](https://bstats.org/signatures/bukkit/ChunkVeil.svg)

## Bug Reports

Please report bugs on GitHub:

https://github.com/DeKaeyman/ChunkVeil/issues

Include your ChunkVeil version, Paper version, ProtocolLib version, logs, config, and reproduction steps.

## Source Code

https://github.com/DeKaeyman/ChunkVeil
