![ChunkVeil banner](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-1.png)

# ChunkVeil

ChunkVeil is a free, open-source Paper plugin that helps reduce underground information leaks on Minecraft servers.

It hides underground chunk data before the client receives it, then reveals chunks only when the player can realistically see or reach them through a view-based scan. The goal is to protect more than ores: caves, hidden bases, underground rooms, block entities, entity spawns, and later block updates can all leak useful information to modified clients.

ChunkVeil helps reduce xray, ESP, freecam, hidden-base discovery, and PieChart-style underground leaks. It does not claim to make every hacked client impossible to use.

## Features

![More than ore obfuscation](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-3.png)

- Rewrites outgoing chunk packets for hidden underground sections.
- Replaces hidden blocks with a configurable fake block.
- Reveals chunks using a 360-degree view scan instead of a simple distance radius.
- Keeps revealed chunks visible until they leave the player's render distance.
- Rewrites later block update packets while a chunk is hidden.
- Cancels hidden block entity update packets below the hidden Y range.
- Optionally hides underground entities.
- Includes admin commands, permissions, metrics, debug logging, reload/refresh, and emergency runtime disable.

## Requirements

![Requirements](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-2.png)

- Paper 1.21.x
- Java 21
- ProtocolLib compatible with your Paper/Minecraft version

ProtocolLib version matters. Use the ProtocolLib build recommended for your server version:

https://www.spigotmc.org/resources/protocollib.1997/

ChunkVeil is tested on Paper 1.21.11. Other Paper 1.21.x builds are allowed and expected to work when paired with a compatible ProtocolLib build, but they are not all tested before each release.

## How It Works

![How ChunkVeil works](https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-4.png)

1. Underground data starts hidden from the player.
2. ChunkVeil scans what the player can reveal using view rays.
3. Real chunks are restored when they become visible or reachable.

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

## Compatibility With Anti-Xray

ChunkVeil can run alongside Paper's built-in anti-xray and packet-based plugins such as Orebfuscator. Paper anti-xray usually runs before ProtocolLib sees the outgoing chunk packet, and ChunkVeil then applies its underground hiding pass to the packet the player is about to receive.

ChunkVeil's ProtocolLib listener uses a late packet priority and declares Orebfuscator as an optional soft dependency so, when both plugins are installed, ChunkVeil is more likely to apply its hidden-chunk rewrite after other packet modifiers. Hidden chunks and hidden block updates are still rewritten for players who already have the chunk loaded.

When another plugin also rewrites the same chunk, block-change, or multi-block-change packets after ChunkVeil, that plugin may change the final fake block appearance. It should not reveal real underground blocks unless that plugin deliberately restores real block data. For the strictest protection, test your exact plugin stack with `/chunkveil status`, an xray/freecam client, and both `hide-air: false` and `hide-air: true` depending on how much cave/base shape you want to conceal.

## Installation

1. Install Paper 1.21.x.
2. Install Java 21.
3. Install a ProtocolLib build compatible with your Paper version.
4. Put `ChunkVeil.jar` in your server's `plugins` folder.
5. Start the server once to generate `plugins/ChunkVeil/config.yml` and `plugins/ChunkVeil/lang.yml`.
6. Run `/chunkveil status` in-game or from console.

## Commands

- `/chunkveil status`
- `/chunkveil reload`
- `/chunkveil refresh`
- `/chunkveil disable`
- `/chunkveil enable`
- `/chunkveil debug on|off`
- `/chunkveil version`

Alias: `/cv`

## Permissions

- `chunkveil.admin`
- `chunkveil.status`
- `chunkveil.reload`
- `chunkveil.refresh`
- `chunkveil.toggle`
- `chunkveil.debug`
- `chunkveil.bypass`

## Bug Reports

Please report bugs on GitHub:

https://github.com/DeKaeyman/ChunkVeil/issues

Include your ChunkVeil version, Paper version, ProtocolLib version, logs, config, and reproduction steps.

## Source Code

https://github.com/DeKaeyman/ChunkVeil
