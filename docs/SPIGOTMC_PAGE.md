# ChunkVeil SpigotMC Resource Page

Use this file as the source for the SpigotMC resource form.

## Form Fields

Title:

```text
ChunkVeil
```

Version String:

```text
0.1.0-beta.1
```

Tag Line:

```text
Packet-level underground anti-xray and anti-ESP protection for Paper servers.
```

Native Major MC Version:

```text
1.21
```

Tested Major MC Versions:

```text
1.21
```

Source Code:

```text
https://github.com/DeKaeyman/ChunkVeil
```

Languages Supported:

```text
English
```

Additional Information URL:

```text
https://github.com/DeKaeyman/ChunkVeil
```

Alternative Support URL:

```text
https://github.com/DeKaeyman/ChunkVeil/issues
```

ProtocolLib URL:

```text
https://www.spigotmc.org/resources/protocollib.1997/
```

## Description

Paste this into the SpigotMC Description box.

```bbcode
[CENTER][IMG]BANNER_1_URL[/IMG]

[SIZE=4][B]Packet-level underground protection for Paper servers.[/B][/SIZE]

[COLOR=#3b82f6]Hide underground chunk data before the client can learn it.[/COLOR]
[/CENTER]

[HR][/HR]

[B][SIZE=5]What is ChunkVeil?[/SIZE][/B]

ChunkVeil is a Paper + ProtocolLib protection plugin that hides underground chunk data from each player until that player can realistically see or reach it.

Most anti-xray setups focus on ore obfuscation. ChunkVeil targets a wider information leak: chunk packets can expose caves, underground rooms, hidden bases, block entities, entity spawns, and later block updates before the player should know they exist.

ChunkVeil helps reduce xray, ESP, freecam, hidden-base discovery, and PieChart-style underground information leaks by rewriting or blocking that data while it is still hidden.

[CENTER][IMG]BANNER_3_URL[/IMG][/CENTER]

[B][SIZE=5]Core Features[/SIZE][/B]

[list]
[*][B]Chunk packet rewriting[/B] - rewrites outgoing hidden underground chunk sections before the client receives them.
[*][B]Configurable fake blocks[/B] - replaces hidden blocks with a per-world fake block such as deepslate.
[*][B]View-based revealing[/B] - reveals chunks with a 360-degree ray scan instead of a simple distance radius.
[*][B]Persistent reveals[/B] - keeps revealed chunks visible until they leave the player's render distance.
[*][B]Block update protection[/B] - rewrites later block and multi-block changes while a chunk is hidden.
[*][B]Block entity protection[/B] - cancels hidden block entity update packets below the hidden Y range.
[*][B]Optional entity hiding[/B] - can hide underground entities, with player hiding disabled by default.
[*][B]Admin safety tools[/B] - status, refresh, reload, debug metrics, and emergency runtime disable.
[/list]

[B][SIZE=5]Best Fit[/SIZE][/B]

[list]
[*]Survival servers
[*]SMP servers
[*]Servers that care about underground bases
[*]Servers that want extra protection beyond ore-only anti-xray
[/list]

ChunkVeil is primarily designed for the overworld. Nether and End support are configurable but disabled by default because those dimensions usually need different fake block choices and more careful testing.

[B][SIZE=5]Requirements[/SIZE][/B]

[list]
[*]Paper 1.21.11
[*]Java 21
[*]ProtocolLib compatible with Paper 1.21.11: https://www.spigotmc.org/resources/protocollib.1997/
[/list]

[COLOR=#d97706][B]Important:[/B][/COLOR] ChunkVeil touches packet internals. Minecraft, Paper, or ProtocolLib updates can affect compatibility. For Paper 1.21.11, use the ProtocolLib build recommended on the ProtocolLib resource page for 1.21.9-1.21.11. Please test on your own setup before using it on a large production server.

[B][SIZE=5]Commands[/SIZE][/B]

[list]
[*][B]/chunkveil status[/B] - Shows runtime state, enabled worlds, queue size, packet rewrite status, and metrics.
[*][B]/chunkveil reload[/B] - Reloads config and language files, then refreshes online players.
[*][B]/chunkveil refresh[/B] - Forces a rescan and refresh for online players.
[*][B]/chunkveil disable[/B] - Emergency switch. Stops packet/listener processing, restores hidden entities, and refreshes sent chunks back to real world data.
[*][B]/chunkveil enable[/B] - Starts the runtime again after disabling it.
[*][B]/chunkveil debug on|off[/B] - Logs compact metrics every 30 seconds.
[*][B]/chunkveil version[/B] - Shows the plugin version.
[/list]

Alias: [B]/cv[/B]

[B][SIZE=5]Permissions[/SIZE][/B]

[list]
[*][B]chunkveil.admin[/B] - Allows all ChunkVeil admin commands.
[*][B]chunkveil.status[/B] - Allows /chunkveil status.
[*][B]chunkveil.reload[/B] - Allows /chunkveil reload.
[*][B]chunkveil.refresh[/B] - Allows /chunkveil refresh.
[*][B]chunkveil.toggle[/B] - Allows /chunkveil disable and /chunkveil enable.
[*][B]chunkveil.debug[/B] - Allows /chunkveil debug on/off.
[*][B]chunkveil.bypass[/B] - Bypasses all ChunkVeil hiding for that player.
[/list]

[B][SIZE=5]Default Overworld Setup[/SIZE][/B]

[CODE]worlds:
  world:
    enabled: true
    hide-below-y: 0
    min-y: -64
    default-fake-block: DEEPSLATE
    hide-air: false
    hide-entities: true
    hide-players: false[/CODE]

[B]hide-air: false[/B] is the recommended default because it is much lighter. Turn it on only if you want more aggressive cave and base-layout hiding and have tested the performance impact on your server.

[B][SIZE=5]Recommended First Test[/SIZE][/B]

[list=1]
[*]Install Paper 1.21.11, Java 21, ProtocolLib, and ChunkVeil.
[*]Start the server once to generate config files.
[*]Join with an admin account and run [B]/chunkveil status[/B].
[*]Mine or teleport underground below the configured hide-below-y.
[*]Test with your real render distance, player count, and world setup.
[*]Use [B]/chunkveil debug on[/B] during testing and profile with Spark if needed.
[/list]

[B][SIZE=5]Links[/SIZE][/B]

[list]
[*][B]Source code:[/B] https://github.com/DeKaeyman/ChunkVeil
[*][B]Bug reports:[/B] https://github.com/DeKaeyman/ChunkVeil/issues
[*][B]Releases:[/B] https://github.com/DeKaeyman/ChunkVeil/releases
[/list]

[B][SIZE=5]Bug Reports[/SIZE][/B]

Please include:

[list]
[*]ChunkVeil version
[*]Paper version
[*]ProtocolLib version
[*]Server logs
[*]Config
[*]Steps to reproduce
[*]Whether the issue happens with only ChunkVeil and ProtocolLib installed
[/list]

[HR][/HR]

[CENTER][B]ChunkVeil reduces underground information leaks. It does not claim to make every hacked client impossible to use.[/B][/CENTER]
```

## Documentation

Paste this into the SpigotMC Documentation box.

```bbcode
[CENTER][IMG]BANNER_2_URL[/IMG][/CENTER]

[B][SIZE=5]Installation[/SIZE][/B]

[list=1]
[*]Install Paper 1.21.11.
[*]Install Java 21.
[*]Install ProtocolLib compatible with Paper 1.21.11: https://www.spigotmc.org/resources/protocollib.1997/
[*]Put ChunkVeil in your server's plugins folder.
[*]Start the server once to generate config.yml and lang.yml.
[*]Run [B]/chunkveil status[/B] in-game or from console.
[/list]

[B][SIZE=5]Configuration[/SIZE][/B]

ChunkVeil creates its config at:

[CODE]plugins/ChunkVeil/config.yml[/CODE]

The default setup protects the overworld below Y 0 and uses DEEPSLATE as the fake block.

[CODE]worlds:
  world:
    enabled: true
    hide-below-y: 0
    min-y: -64
    default-fake-block: DEEPSLATE
    hide-air: false
    hide-entities: true
    hide-players: false[/CODE]

[B]Recommended default:[/B] keep hide-air set to false unless you have tested the performance cost.

[CENTER][IMG]BANNER_4_URL[/IMG][/CENTER]

[B][SIZE=5]Support[/SIZE][/B]

Report bugs on GitHub:

https://github.com/DeKaeyman/ChunkVeil/issues

Full README and source code:

https://github.com/DeKaeyman/ChunkVeil
```
