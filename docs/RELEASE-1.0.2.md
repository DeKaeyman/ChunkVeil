# ChunkVeil 1.0.2 — Multiplayer block desync fix

ChunkVeil 1.0.2 fixes cross-player packet corruption affecting all supported versions. Block change, multi-block change, and standalone light update packets are broadcast as a single instance shared by every tracking player. Rewriting them in place for a player with a hidden chunk corrupted the same packet for players entitled to real data: an underground player near an observer saw their own placed or mined blocks turn into the fake block and desync into unminable ghost blocks, including wrongful "Flying is not enabled" kicks.

Per-player rewrites now operate on packet clones, matching the chunk path's existing behaviour. Verified with a two-player place/break/mine session on Paper 1.21.11: the underground player keeps real block data while observers continue to see only the fake block, and concealed light stays sanitized.

Updating is strongly recommended for every multiplayer server. No configuration changes are required.

## Short marketplace update text

**Title:** ChunkVeil 1.0.2 — Multiplayer Block Desync Fix

**Description:** Fixes blocks placed or mined by an underground player turning into the fake block and becoming unminable ghost blocks (with wrongful fly-kicks) when another player was nearby. Strongly recommended update for all multiplayer servers; no config changes needed.
