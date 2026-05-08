[CENTER]
[IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-1.png[/IMG]
[SIZE=4][B]Packet-level underground protection for Paper servers.[/B][/SIZE]

[COLOR=#2b6cb0]Hide underground chunk data before the client can learn it.[/COLOR]
[/CENTER]

[SIZE=5][B]What is ChunkVeil?[/B]
[/SIZE]
ChunkVeil is a Paper + ProtocolLib plugin that helps reduce underground information leaks on Minecraft servers.

It hides underground chunk data before the client receives it, then reveals chunks only when the player can realistically see or reach them through a view-based scan. The goal is to protect more than ores: caves, hidden bases, underground rooms, block entities, entity spawns, and later block updates can all leak useful information to modified clients.

ChunkVeil helps reduce xray, ESP, freecam, hidden-base discovery, and PieChart-style underground leaks. It does not claim to make every hacked client impossible to use.

[SIZE=5][B]Features[/B]
[/SIZE]
[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-3.png[/IMG]
[/CENTER]

[LIST]
[*][B]Chunk packet rewriting[/B] - Rewrites outgoing hidden underground chunk sections before the client receives them.
[*][B]Configurable fake blocks[/B] - Replaces hidden blocks with a per-world fake block.
[*][B]View-based reveals[/B] - Reveals chunks with a 360-degree view scan instead of a simple distance radius.
[*][B]Persistent reveals[/B] - Keeps revealed chunks visible until they leave render distance.
[*][B]Block update protection[/B] - Rewrites later block updates while a chunk is hidden.
[*][B]Block entity protection[/B] - Cancels hidden block entity update packets.
[*][B]Optional entity hiding[/B] - Can hide underground entities.
[*][B]Admin tools[/B] - Status, reload, refresh, debug metrics, permissions, and emergency runtime disable.
[/LIST]

[SIZE=5][B]Requirements[/B]
[/SIZE]
[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-2.png[/IMG][/CENTER]

[LIST]
[*][B]Paper[/B] 1.21.x
[*][B]Java[/B] 21
[*][B]ProtocolLib[/B] compatible with your Paper/Minecraft version
[/LIST]

[COLOR=#b45309][B]Important:[/B][/COLOR] ProtocolLib version matters. Use a ProtocolLib build compatible with your server version.

ProtocolLib:
[URL]https://www.spigotmc.org/resources/protocollib.1997/[/URL]

ChunkVeil is tested on Paper 1.21.11. Other Paper 1.21.x builds are allowed and expected to work when paired with a compatible ProtocolLib build, but they are not all tested before each release.

[SIZE=5][B]How It Works[/B]
[/SIZE]
[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-4.png[/IMG][/CENTER]

[LIST=1]
[*]Underground data starts hidden from the player.
[*]ChunkVeil scans what the player can reveal using view rays.
[*]Real chunks are restored when they become visible or reachable.
[/LIST]

[SIZE=5][B]Visual Comparison[/B]
[/SIZE]
These screenshots use an xray-style view so the difference is easy to see.

[SIZE=4][B]Without ChunkVeil[/B][/SIZE]
[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/chunveil-pre.png[/IMG][/CENTER]

With ChunkVeil disabled, underground terrain, caves, ores, structures, and hidden spaces can be visible to modified clients before the player should know about them.

[SIZE=4][B]ChunkVeil with hide-air: false[/B][/SIZE]
[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/chunkveil-air.png[/IMG][/CENTER]

This is the recommended default. Air stays air, so caves and empty pockets may still appear as open space, but solid hidden blocks are replaced with the configured fake block, such as DEEPSLATE. This is faster and reduces the most useful block information without rewriting huge amounts of air.

[SIZE=4][B]ChunkVeil with hide-air: true[/B][/SIZE]
[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/chunkvail-no-air.png[/IMG][/CENTER]

When hide-air is enabled, ChunkVeil also replaces underground air with the fake block. This makes cave shapes, rooms, and hidden base layouts much harder to read from the client side, but it costs more because many more blocks need to be rewritten.

[SIZE=5][B]Compatibility With Anti-Xray[/B]
[/SIZE]
ChunkVeil can run alongside Paper's built-in anti-xray and packet-based plugins such as Orebfuscator. Paper anti-xray usually runs before ProtocolLib sees the outgoing chunk packet, and ChunkVeil then applies its underground hiding pass to the packet the player is about to receive.

ChunkVeil uses a late ProtocolLib packet priority and declares Orebfuscator as an optional soft dependency so, when both plugins are installed, ChunkVeil is more likely to apply its hidden-chunk rewrite after other packet modifiers. Hidden chunks and hidden block updates are still rewritten for players who already have the chunk loaded.

If another plugin rewrites the same chunk, block-change, or multi-block-change packets after ChunkVeil, that plugin may change the final fake block appearance. It should not reveal real underground blocks unless that plugin deliberately restores real block data. For the strictest protection, test your exact plugin stack with /chunkveil status, an xray/freecam client, and both hide-air: false and hide-air: true depending on how much cave/base shape you want to conceal.

[SIZE=5][B]Commands[/B]
[/SIZE]
[LIST]
[*][B]/chunkveil status[/B] - Shows runtime state, worlds, queue size, rewrite status, and metrics.
[*][B]/chunkveil reload[/B] - Reloads config and language files.
[*][B]/chunkveil refresh[/B] - Forces a rescan and refresh for online players.
[*][B]/chunkveil disable[/B] - Emergency switch that restores real chunks for online players.
[*][B]/chunkveil enable[/B] - Starts the runtime again.
[*][B]/chunkveil debug on|off[/B] - Toggles debug metrics.
[*][B]/chunkveil version[/B] - Shows the plugin version.
[/LIST]

Alias: [B]/cv[/B]

[SIZE=5][B]Permissions[/B]
[/SIZE]
[LIST]
[*][B]chunkveil.admin[/B] - Allows all ChunkVeil admin commands.
[*][B]chunkveil.status[/B]
[*][B]chunkveil.reload[/B]
[*][B]chunkveil.refresh[/B]
[*][B]chunkveil.toggle[/B]
[*][B]chunkveil.debug[/B]
[*][B]chunkveil.bypass[/B] - Bypasses all hiding for that player.
[/LIST]

[SIZE=5][B]Links[/B]
[/SIZE]
[LIST]
[*][B]Source Code:[/B] [URL]https://github.com/DeKaeyman/ChunkVeil[/URL]
[*][B]Bug Reports:[/B] [URL]https://github.com/DeKaeyman/ChunkVeil/issues[/URL]
[*][B]Releases:[/B] [URL]https://github.com/DeKaeyman/ChunkVeil/releases[/URL]
[*][B]ProtocolLib:[/B] [URL]https://www.spigotmc.org/resources/protocollib.1997/[/URL]
[/LIST]

[CENTER][B]ChunkVeil reduces underground information leaks. It does not claim to make every hacked client impossible to use.[/B][/CENTER]
