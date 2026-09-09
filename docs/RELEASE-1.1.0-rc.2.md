# ChunkVeil 1.1.0-rc.2 — Incompatible ProtocolLib named at startup

ChunkVeil 1.1.0-rc.2 turns the most common misconfiguration — a ProtocolLib build that does not support the server's Minecraft version — from a confusing first-join failure into a clear startup refusal. This was motivated by real 1.1.0-rc.1 field reports: ProtocolLib 5.4.0 works for most plugins on 26.x, so servers boot looking healthy, and ChunkVeil's protection only tripped fail-closed when the first chunk carrying a block entity was rewritten, quarantining traffic in a way that looks like a crashed server.

Startup now probes the exact ProtocolLib reflection chain the concealed-chunk path needs. On an incompatible build the server refuses to start under the default strict policy, and the log names the installed ProtocolLib version, the server version, and the download that fixes it. The same diagnosis is printed on runtime trips that carry the reflection-failure signature, and `/chunkveil verify` warns about the known-bad pairing (ProtocolLib 5.4.0 or older on Minecraft 1.21.9+/26.x) before any packet traffic exists.

Folia 26.1.2 joins 1.21.11 and 26.2 as a checksum-pinned, boot-verified experimental Folia target. Documentation now states bluntly that 1.21.9+/26.x require the current ProtocolLib development build, and recommends the `KICK_PLAYERS` trip action for survival servers so a security trip reads as an explicit message instead of a frozen world.

rc.2 also fixes a Folia-family security trip in the entity-spawn packet handler, reported on Canvas 26.2: when another plugin flushed a player's connection from a foreign thread (such as sending a chat message from the global region thread), a queued spawn packet reached ChunkVeil on a thread that did not own the spawned entity, a live-entity getter threw a region thread-check exception, and protection tripped fail-closed — experienced as a server-wide freeze after minutes of normal play. The packet path now never touches a live entity it does not own and always falls back to the packet's own data.

This remains a release candidate: Folia support is experimental until the gameplay matrix passes.

## Short marketplace update text

**Title:** ChunkVeil 1.1.0-rc.2 — Incompatible ProtocolLib Named at Startup

**Description:** The wrong ProtocolLib build (5.4.0 on 1.21.9+/26.x) is now caught at startup with a clear message naming the fix, instead of protection failing on the first join. Adds Folia 26.1.2 as a boot-verified target. Recommended for everyone testing the 1.1.0 release candidates.
