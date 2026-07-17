[CENTER]
[IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-1.png[/IMG]
[SIZE=4][B]Packet-level anti-xray, anti-ESP, and freecam protection for underground chunks.[/B][/SIZE]

[COLOR=#2b6cb0]Hide underground chunks, caves, ores, entities, and base layouts before modified clients can read them.[/COLOR]
[/CENTER]

[SIZE=5][B]What is ChunkVeil?[/B][/SIZE]

ChunkVeil is a free, open-source [B]Paper + ProtocolLib anti-xray protection plugin[/B] focused on underground information leaks.

Most anti-xray plugins focus mainly on ores. ChunkVeil is designed to protect more of the underground world: caves, hidden bases, underground rooms, block entities, entity spawns, and later block updates can all leak useful information to modified clients.

ChunkVeil helps reduce [B]xray, ESP, freecam scouting, hidden-base discovery, cave discovery, ore scouting, and PieChart-style underground leaks[/B]. It does not claim to make every hacked client impossible to use, and it is not a full gameplay anti-cheat.

[SIZE=5][B]Why Server Admins Use It[/B][/SIZE]

[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-3.png[/IMG][/CENTER]

[LIST]
[*][B]More than ore obfuscation[/B] - Helps hide caves, underground terrain, ores, hidden rooms, and base layouts.
[*][B]Anti-ESP support[/B] - Optionally hides underground entities while their chunk is hidden.
[*][B]Freecam resistance[/B] - Underground chunks stay masked until the player can realistically see or reach them.
[*][B]Packet-level chunk masking[/B] - While protection is active, hidden chunk packets are rewritten before they are sent. A packet that cannot be rewritten safely is cancelled instead of sent.
[*][B]Configurable fake blocks[/B] - Replaces hidden blocks with a per-world fake block such as DEEPSLATE, NETHERRACK, or END_STONE.
[*][B]View-based reveals[/B] - Reveals chunks with a visibility scan instead of exposing everything in a simple radius.
[*][B]Persistent reveals[/B] - Keeps revealed chunks visible until they leave the player's render distance.
[*][B]Block update protection[/B] - Rewrites later block update packets while a chunk is hidden.
[*][B]Block entity protection[/B] - Cancels hidden block entity update packets below the protected Y range.
[*][B]Secondary leak protection[/B] - Can cancel hidden underground explosion, world event, block break animation, and positional sound packets.
[*][B]Adaptive scan quality[/B] - Optional TPS-aware ray reduction helps busy servers keep tick time predictable.
[*][B]Fail-closed safety[/B] - If ProtocolLib or raw chunk rewriting is not compatible, ChunkVeil disables runtime protection instead of pretending it is safe.
[*][B]Tested, not just claimed[/B] - An automated packet regression test suite runs in CI, and [B]/chunkveil verify[/B] gives owners a single PASS/WARN/FAIL protection check.
[*][B]Admin tools[/B] - Status, compatibility diagnostics, player inspect, diagnostic reports, performance prediction, reload, refresh, debug metrics, permissions, and emergency runtime disable.
[*][B]Update notifications[/B] - Admins get an in-game notice with a download link when a newer release compatible with the server's exact Minecraft version is available.
[/LIST]

[SIZE=5][B]Requirements[/B][/SIZE]

[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-2.png[/IMG][/CENTER]

[LIST]
[*][B]Paper[/B] 1.21.x or 26.x
[*][B]Java[/B] 21 or newer (Paper 26.x itself requires Java 25)
[*][B]ProtocolLib[/B] compatible with your Paper/Minecraft version
[/LIST]

[COLOR=#b45309][B]Important:[/B][/COLOR] ProtocolLib version matters. Pick the build that matches your server version:

[LIST]
[*]Paper 1.21.4 - 1.21.8: ProtocolLib 5.4.0 (stable release)
[*]Paper 1.21.9 - 1.21.11 and 26.x: the newest ProtocolLib release or development build that lists your exact version
[/LIST]

ProtocolLib downloads:
[URL]https://www.spigotmc.org/resources/protocollib.1997/[/URL]
[URL]https://github.com/dmulloy2/ProtocolLib/releases[/URL]

ChunkVeil ships as a single universal jar.

[B]Verified with ChunkVeil 0.5.0[/B] (this exact combination was run on a real server):
[LIST]
[*]Paper 26.1.2 + newest ProtocolLib dev build for 26.1 + Java 25
[*]Paper 1.21.11 + newest ProtocolLib build for 1.21.11 + Java 21+
[*]Paper 1.21.8 + ProtocolLib 5.4.0 + Java 21+
[/LIST]

[B]Expected, not verified[/B] (should work, but was not run before release): other Paper 1.21.x and 26.x builds with a matching ProtocolLib build.

[B]Unsupported:[/B] Spigot, Folia, and versions before 1.21.

If an expected combination misbehaves, ChunkVeil is designed to fail closed rather than leak, and [B]/chunkveil verify[/B] will tell you what went wrong. [B]/chunkveil compat[/B] remains an alias.

[SIZE=5][B]How It Works[/B][/SIZE]

[CENTER][IMG]https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/docs/assets/simple/banner-4.png[/IMG][/CENTER]

[LIST=1]
[*]Underground data starts hidden from the player.
[*]Hidden underground blocks are replaced with a configurable fake block.
[*]ChunkVeil scans what the player can realistically reveal using view rays.
[*]Real chunks are restored when they become visible or reachable.
[*]Later hidden block/entity updates are masked or cancelled where possible.
[/LIST]

ChunkVeil's core rule: [B]while protection is active, a hidden chunk never leaves the server with real underground block data in it.[/B] If the main packet rewrite path cannot start, or a critical rewrite incompatibility appears at runtime, the unsafe packet is cancelled and ChunkVeil fails closed - it disables protection and warns loudly in the console and to online admins. There is no insecure fallback mode: protection is either working as designed or unmistakably off.

ChunkVeil is primarily designed for the overworld. Nether and End can be configured, but they are disabled by default because their terrain and fake block choices usually need separate testing.

[SIZE=5][B]Visual Comparison[/B][/SIZE]

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

[SIZE=5][B]Compatibility With Anti-Xray[/B][/SIZE]

ChunkVeil can run alongside Paper's built-in anti-xray and packet-based plugins such as Orebfuscator. Paper anti-xray usually runs before ProtocolLib sees the outgoing chunk packet, and ChunkVeil then applies its underground hiding pass to the packet the player is about to receive.

ChunkVeil uses a late ProtocolLib packet priority and declares Orebfuscator as an optional soft dependency so, when both plugins are installed, ChunkVeil is more likely to apply its hidden-chunk rewrite after other packet modifiers. Hidden chunks and hidden block updates are still rewritten for players who already have the chunk loaded.

If another plugin rewrites the same chunk, block-change, or multi-block-change packets after ChunkVeil, that plugin may change the final fake block appearance. It should not reveal real underground blocks unless that plugin deliberately restores real block data.

For the strictest protection, test your exact plugin stack with /chunkveil status, an xray/freecam client, and both hide-air: false and hide-air: true depending on how much cave/base shape you want to conceal.

[SIZE=5][B]Installation[/B][/SIZE]

[LIST=1]
[*]Install Paper 1.21.x or 26.x.
[*]Install Java 21 or newer (Java 25 for Paper 26.x).
[*]Install ProtocolLib compatible with your Paper version: [URL]https://www.spigotmc.org/resources/protocollib.1997/[/URL]
[*]Put ChunkVeil in your server's plugins folder.
[*]Start the server once to generate config.yml and lang.yml.
[*]Run [B]/chunkveil status[/B] in-game or from console.
[/LIST]

[SIZE=5][B]Default Overworld Config[/B][/SIZE]

[CODE]worlds:
  world:
    enabled: true
    hide-below-y: 0
    min-y: -64
    default-fake-block: DEEPSLATE
    hide-air: false
    hide-entities: true
    hide-players: false[/CODE]

[SIZE=5][B]Recommended Settings[/B][/SIZE]

[B]hide-air: false[/B] is recommended for most servers. It keeps air visible and only fakes non-air underground blocks, which is much lighter.

[B]hide-air: true[/B] is stronger for hidden-base protection because it also hides cave shapes and empty rooms, but it costs more.

[B]hide-entities: true[/B] hides underground mobs, item drops, minecarts, armor stands, and similar entities while their chunk is hidden.

[B]hide-players: false[/B] is the default because hiding players can affect PvP and moderation.

[SIZE=5][B]Commands[/B][/SIZE]

[LIST]
[*][B]/chunkveil status[/B] - Shows runtime state, worlds, queue size, rewrite status, and metrics.
[*][B]/chunkveil verify[/B] - One PASS/WARN/FAIL check of the whole protection state: ProtocolLib, runtime, rewrite path, failures, versions, worlds, secondary protection, other packet plugins, and config. ([B]/chunkveil compat[/B] is an alias.)
[*][B]/chunkveil inspect <player>[/B] - Shows a player's current ChunkVeil state, visible chunks, queue count, view distance, and bypass state.
[*][B]/chunkveil report[/B] - Creates a diagnostic report file for troubleshooting.
[*][B]/chunkveil predict <players> <ramGb> <cpuTier> [viewDistance][/B] - Estimates performance from live timing samples.
[*][B]/chunkveil update[/B] - Checks now whether a newer compatible release is available.
[*][B]/chunkveil reload[/B] - Reloads config and language files. Add [B]--check[/B] to validate the config from disk without applying it.
[*][B]/chunkveil refresh[/B] - Forces a rescan and refresh for online players.
[*][B]/chunkveil disable[/B] - Emergency switch that restores real chunks for online players.
[*][B]/chunkveil enable[/B] - Starts the runtime again.
[*][B]/chunkveil debug on|off[/B] - Toggles debug metrics.
[*][B]/chunkveil version[/B] - Shows the plugin version.
[/LIST]

Alias: [B]/cv[/B]

[SIZE=5][B]Permissions[/B][/SIZE]

[LIST]
[*][B]chunkveil.admin[/B] - Allows all ChunkVeil admin commands.
[*][B]chunkveil.status[/B]
[*][B]chunkveil.verify[/B]
[*][B]chunkveil.inspect[/B]
[*][B]chunkveil.report[/B]
[*][B]chunkveil.predict[/B]
[*][B]chunkveil.update[/B]
[*][B]chunkveil.reload[/B]
[*][B]chunkveil.refresh[/B]
[*][B]chunkveil.toggle[/B]
[*][B]chunkveil.debug[/B]
[*][B]chunkveil.version[/B]
[*][B]chunkveil.bypass[/B] - Bypasses all hiding for the player.
[/LIST]

[SIZE=5][B]Recommended First Test[/B][/SIZE]

[LIST=1]
[*]Join with an admin account.
[*]Run [B]/chunkveil verify[/B] and read the PASS/WARN/FAIL verdict.
[*]Run [B]/chunkveil status[/B] for counters.
[*]Go underground below the configured hide-below-y.
[*]Move in and out of caves, tunnels, or hidden rooms.
[*]Test [B]/chunkveil inspect <yourname>[/B].
[*]Test [B]/chunkveil report[/B] before reporting bugs.
[*]Test [B]/chunkveil refresh[/B].
[*]Test [B]/chunkveil disable[/B] to restore real chunks for online players.
[*]Use [B]/chunkveil debug on[/B] while testing.
[/LIST]

[SIZE=5][B]Update Notifications[/B][/SIZE]

ChunkVeil periodically checks a small version manifest on GitHub and tells admins (permission [B]chunkveil.update[/B]) when a newer release is available [B]for their exact Minecraft version[/B]. Releases that do not declare compatibility with the running server version are never offered.

The checker only reads version metadata. It never downloads or installs anything. Disable it with [B]update-checker.enabled: false[/B] in config.yml.

[SIZE=5][B]Anonymous Usage Statistics[/B][/SIZE]

ChunkVeil uses [URL='https://bstats.org/plugin/bukkit/ChunkVeil/32677']bStats[/URL] to collect anonymous aggregate usage statistics: server count, player counts, Minecraft/Java versions, and a few ChunkVeil-specific charts such as hide-air usage. No world data, no player data, and no server address is ever sent.

Opt out with [B]metrics.enabled: false[/B] in config.yml, or globally for all plugins in plugins/bStats/config.yml.

[CENTER][IMG]https://bstats.org/signatures/bukkit/ChunkVeil.svg[/IMG][/CENTER]

[SIZE=5][B]Links[/B][/SIZE]

[LIST]
[*][B]Source Code:[/B] [URL]https://github.com/DeKaeyman/ChunkVeil[/URL]
[*][B]Bug Reports:[/B] [URL]https://github.com/DeKaeyman/ChunkVeil/issues[/URL]
[*][B]Releases:[/B] [URL]https://github.com/DeKaeyman/ChunkVeil/releases[/URL]
[*][B]ProtocolLib:[/B] [URL]https://www.spigotmc.org/resources/protocollib.1997/[/URL]
[/LIST]

[CENTER][B]ChunkVeil reduces underground information leaks. It does not replace a full anti-cheat and does not claim to make every hacked client impossible to use.[/B][/CENTER]
