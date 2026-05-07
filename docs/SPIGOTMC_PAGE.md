# ChunkVeil - Anti-Xray, Anti-ESP and Underground Packet Veil

ChunkVeil is a Paper + ProtocolLib protection plugin that hides underground chunk data from players until they can realistically see or reach it. Instead of only changing ores, it rewrites hidden chunk sections before the client receives them, helping reduce xray, ESP, freecam, hidden-base discovery, and PieChart-style underground information leaks.

## Why ChunkVeil?

Most anti-xray setups focus on ore obfuscation. ChunkVeil aims at a broader problem: clients can learn far more than ore positions from chunk packets. Caves, underground rooms, block entities, entity spawns, and later block updates can all leak information.

ChunkVeil protects that surface by:

- Rewriting outgoing chunk packets for hidden underground sections.
- Replacing underground blocks with a configurable fake block.
- Revealing chunks with a 360-degree ray scan instead of a simple radius.
- Keeping already revealed chunks visible until they leave render distance.
- Rewriting later block updates while a chunk is hidden.
- Cancelling hidden block entity update packets.
- Optionally hiding underground entities, including players if you enable it.
- Providing an emergency runtime disable command that restores online players.

## Best Fit

ChunkVeil is mainly designed for survival and SMP servers that care about hidden bases, mining fairness, and underground information leaks. The default config targets the overworld and keeps air visible for better performance.

Nether and End support are configurable but disabled by default because those dimensions usually need different fake block choices and more careful testing.

## Requirements

- Paper 1.21.11
- Java 21
- ProtocolLib compatible with Paper 1.21.11

For Paper 1.21.11, use a ProtocolLib dev build or a runtime jar compatible with 1.21.11 chunk wrappers. Older ProtocolLib builds may not support the chunk packet structures ChunkVeil needs.

## Commands

`/chunkveil status` - Shows runtime state, enabled worlds, queue size, packet rewrite status, and metrics.

`/chunkveil reload` - Reloads config and language files, then refreshes online players.

`/chunkveil refresh` - Forces a rescan and refresh for online players.

`/chunkveil disable` - Emergency switch. Stops packet/listener processing, restores hidden entities, and refreshes sent chunks back to real world data.

`/chunkveil enable` - Starts the runtime again after disabling it.

`/chunkveil debug on|off` - Logs compact metrics every 30 seconds.

`/chunkveil version` - Shows the plugin version.

Alias: `/cv`

## Permissions

- `chunkveil.admin`
- `chunkveil.status`
- `chunkveil.reload`
- `chunkveil.refresh`
- `chunkveil.toggle`
- `chunkveil.debug`
- `chunkveil.bypass`

## Default Overworld Setup

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

`hide-air: false` is the recommended default because it is much lighter. Turn it on only if you want more aggressive cave and base layout hiding and have tested the performance impact on your server.

## Recommended First Test

1. Install Paper 1.21.11, Java 21, ProtocolLib, and ChunkVeil.
2. Start the server once to generate config files.
3. Join with an admin account and run `/chunkveil status`.
4. Mine or teleport underground below the configured `hide-below-y`.
5. Test with your real render distance, player count, and world setup.
6. Use `/chunkveil debug on` during testing and profile with Spark if needed.

## Current Status

ChunkVeil is suitable for careful server-owner testing, but it touches packet internals and should be treated as a compatibility-sensitive plugin. Please report bugs with:

- Paper version
- ProtocolLib version
- ChunkVeil version
- Server logs
- Config
- Reproduction steps
- Whether the issue happens with only ChunkVeil + ProtocolLib installed

## Public GitHub Release

ChunkVeil is planned as a public GitHub project. The best launch flow is to publish the source, attach release jars through GitHub Releases, and use issues for bug reports and compatibility feedback.

Recommended public links:

- GitHub repository
- GitHub Releases for downloads
- GitHub Issues for bug reports
- README for setup, config, and compatibility notes

Avoid promising that it blocks every hacked client. A stronger and safer claim is that ChunkVeil reduces underground information leaks by hiding chunk, block update, block entity, and optional entity data until the player can reasonably reveal it.
