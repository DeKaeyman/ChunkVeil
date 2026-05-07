# ChunkVeil

ChunkVeil is a Paper + ProtocolLib anti-xray, anti-ESP, anti-freecam, and anti-PieChart-abuse plugin for Minecraft 1.21.11.
It hides underground chunk sections from each player before the client receives them, then reveals chunks only when the player can actually reach them with the view scan.
The goal is to prevent clients from learning about hidden underground blocks, caves, bases, block entities, and optional entities before that information should be visible.

The plugin is primarily built for the overworld. Nether and End can be configured, but they are disabled by default because their terrain and fake blocks need different choices.

## Requirements

- Paper 1.21.11
- Java 21
- ProtocolLib 5.4.1 snapshot/dev build or another build compatible with Paper 1.21.11

## What It Does

- Rewrites outgoing chunk packets for hidden chunks.
- Replaces underground non-fake blocks with a configurable fake block.
- Reduces xray, ESP, freecam, and PieChart-style underground information leaks.
- Optionally hides air, but this is disabled by default for performance.
- Reveals chunks using a 360-degree ray scan instead of a simple distance radius.
- Keeps revealed chunks visible until they leave the player's render distance.
- Rewrites later block update packets so hidden blocks do not leak.
- Cancels block entity update packets below the hidden Y range.
- Optionally hides entities below the hidden Y range.

## Default Config

```yaml
view-reveal-horizontal-rays: 64
view-reveal-vertical-rays: 13
view-reveal-occlusion-grace-blocks: 2
view-reveal-refresh-millis: 150

worlds:
  world:
    enabled: true
    hide-below-y: 0
    min-y: -64
    default-fake-block: DEEPSLATE
    hide-air: false
    hide-entities: true
    hide-players: false
  world_nether:
    enabled: false
    hide-below-y: 32
    min-y: 0
    default-fake-block: NETHERRACK
    hide-air: false
    hide-entities: true
    hide-players: false
  world_the_end:
    enabled: false
    hide-below-y: 0
    min-y: -64
    default-fake-block: END_STONE
    hide-air: false
    hide-entities: true
    hide-players: false
```

## Config Explanation

`worlds`
Every world has its own block/entity settings. There are no global hide block settings.

`enabled`
Whether ChunkVeil should run in that world.

`hide-below-y`
Per-world setting. Blocks below this Y level are hidden. With `0`, blocks from `min-y` through `-1` are hidden.

`min-y`
Per-world setting. Lowest Y level ChunkVeil should process.

`default-fake-block`
Per-world setting. The block sent to the client for hidden real blocks. For overworld, `DEEPSLATE` is usually the safest choice.

`hide-air`
Per-world setting. When `false`, air stays air and only non-air blocks are faked. This is faster and is the recommended default.
When `true`, air is also replaced by the fake block, which hides caves and base layouts more aggressively but costs more.

`hide-entities`
Per-world setting. Hides mobs, item drops, minecarts, armor stands, item frames, and similar entities below the hidden Y range when their chunk is hidden.

`hide-players`
Per-world setting. Also hides players below the hidden Y range. Default is `false` because hiding players can affect PvP and moderation.

`view-reveal-horizontal-rays`
Global per-player setting. Every tracked player runs a view scan with this many horizontal directions.

`view-reveal-vertical-rays`
Global per-player setting. Every horizontal direction scans this many up/down angles.

`view-reveal-occlusion-grace-blocks`
Global per-player setting. How many solid blocks a ray can pass through before stopping. This prevents tiny cave corners from staying hidden when the client can still see them.

`view-reveal-refresh-millis`
Global per-player setting. Minimum time between view scans for each player.

## Language File

ChunkVeil creates `plugins/ChunkVeil/lang.yml` on first start.

`prefix`
Prefix used before command messages.

`states.enabled` and `states.disabled`
Colored labels used in status/debug messages.

`commands`
All command replies. Placeholders use `{name}` syntax, for example `{players}`, `{chunks}`, `{version}`, and `{label}`.

Use `/chunkveil reload` after editing `lang.yml`.

## Examples

Overworld only:

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
  world_nether:
    enabled: false
  world_the_end:
    enabled: false
```

Enable Nether with Netherrack fake blocks:

```yaml
worlds:
  world_nether:
    enabled: true
    hide-below-y: 32
    min-y: 0
    default-fake-block: NETHERRACK
    hide-air: false
```

More aggressive cave hiding in the overworld:

```yaml
worlds:
  world:
    enabled: true
    hide-air: true
```

More accurate but heavier view reveal:

```yaml
view-reveal-horizontal-rays: 96
view-reveal-vertical-rays: 17
view-reveal-refresh-millis: 100
```

Lighter server setup:

```yaml
view-reveal-horizontal-rays: 48
view-reveal-vertical-rays: 9
view-reveal-refresh-millis: 250

worlds:
  world:
    enabled: true
    hide-air: false
    hide-entities: false
```

## Commands

`/chunkveil status`
Shows config state, packet rewrite status, tracked players, queued chunks, and metrics.

`/chunkveil reload`
Reloads the config and refreshes online players.

`/chunkveil refresh`
Forces a refresh for all online players.

`/chunkveil disable`
Emergency switch. Stops ChunkVeil packet/listener processing, shows hidden entities again, and refreshes sent chunks back to the real world state for online players. The plugin stays loaded so commands still work.

`/chunkveil enable`
Starts the ChunkVeil runtime again after `/chunkveil disable`.

`/chunkveil debug on`
Logs a compact metrics summary every 30 seconds.

`/chunkveil debug off`
Disables debug summaries.

`/chunkveil version`
Shows the plugin version.

Alias: `/cv`

## Permissions

`chunkveil.admin`
Allows all ChunkVeil admin commands.

`chunkveil.status`
Allows `/chunkveil status`.

`chunkveil.reload`
Allows `/chunkveil reload`.

`chunkveil.refresh`
Allows `/chunkveil refresh`.

`chunkveil.toggle`
Allows `/chunkveil disable` and `/chunkveil enable`.

`chunkveil.debug`
Allows `/chunkveil debug on/off`.

`chunkveil.bypass`
Bypasses all ChunkVeil hiding for that player.

## Performance Notes

The recommended default is `hide-air: false`. It avoids rewriting huge amounts of cave air and is much lighter.

Most CPU cost happens when players receive new chunks, move into new chunks, or reveal hidden areas. Idle players should be cheap.

Use `/chunkveil status` for quick counters and `/spark profiler start --timeout 600` for real profiling on a live server.
