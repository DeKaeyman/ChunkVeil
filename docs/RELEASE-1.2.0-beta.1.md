# ChunkVeil 1.2.0-beta.1 — Huge performance boost

ChunkVeil 1.2.0-beta.1 is the result of a full audit of the plugin for lag and bugs on Paper and Folia. Every hot path was reworked and then measured under load with a new stress test harness: real network bots (100 fake players on one server) flying over the surface, exploring caves below the cutoff, mining, and standing AFK, with Java Flight Recorder profiling every run. Protection is unchanged: the same blocks, entities and packets stay hidden, and everything still fails closed.

**What got faster:**

- Chunk rewriting is about **9x faster** and allocates about 20x less memory, with byte-identical output. When the same chunk goes to several players, it is now rewritten only once.
- Reveal ray scans and entity scans each cost **about half** of what they did. Rays read blocks straight from the chunk sections, and entity scans only look where entities can actually be hidden.
- **No more lag spikes** from chunk re-hiding (about 13 ms of main-thread work per chunk, gone), reveals no longer resend chunks to every nearby player twice, and `/chunkveil reload` no longer resends every chunk to everyone.
- In the 100-player stress test, the last optimization pass alone cut ChunkVeil's total cost by 31% and raised the server from 6.9 to 8.1 TPS. Compared with 1.1.0-rc.4 the total gain is much bigger, because the earlier audit fixes (chunk rewriting, re-hide spikes, broadcast reveals, reload) come on top.

**What got fixed:** hidden mobs that stayed invisible for good or leaked names, items and armor; the 1.21.0 to 1.21.4 chunk format; carved caves filled with fake blocks; the End quarantining with default settings; reveals that needed movement (vehicles, standing still) or scanned the old location after a teleport; a rare client-side chunk corruption near the cutoff; and a set of Folia edge cases around region ownership, reloads and dead entities. See the CHANGELOG for the full list.

**This is a beta.** Unit tests (153), the CI boot matrix and the stress tests pass, but please test on your server (ideally with an xray client) and report anything odd. Minecraft 26.3 is not supported yet because of a ProtocolLib issue on 26.3; use 26.2 or older until ProtocolLib fixes it.

New tools: `/chunkveil metrics` for scripts, and emergency switches (`-Dchunkveil.disableFastTerrain=true`, `-Dchunkveil.disableRewriteCache=true`, `-Dchunkveil.disableEntityBandScan=true`) that turn the new fast paths off without a new build.

## Short marketplace update text

**Title:** ChunkVeil 1.2.0-beta.1 — Huge Performance Boost

**Description:** A full performance rework: chunk rewriting about 9x faster, reveal and entity scans about half the cost, no more chunk re-hide lag spikes, reveals sent only to the player who needs them, and reloads that no longer resend every chunk. Also fixes invisible or leaking entities, the 1.21.0-1.21.4 chunk format, cave handling, End defaults and several Folia edge cases. Beta: please test and report.
