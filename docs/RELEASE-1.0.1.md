# ChunkVeil 1.0.1 — Explosion packet fix

ChunkVeil 1.0.1 fixes a protection-breaking bug on Minecraft 1.21.2 and newer, including all currently supported Paper versions. The explosion packet stores its center as a `Vec3` on these versions, while ChunkVeil 1.0.0 only understood the older flattened-doubles layout. With `cancel-explosions: true` (the default), the first explosion of any kind tripped the fail-closed security state and quarantined all protected packet traffic until restart, which players experienced as a frozen world.

1.0.1 reads the modern vector layout first and falls back to the legacy doubles layout on 1.21 and 1.21.1. The decoding is covered by unit fixtures for both layouts, the packet field layout was verified against the extracted classes of the pinned Paper 1.21.11 and 26.2 artifacts, and the fix was gameplay-tested with TNT and wind charges on Paper 1.21.11.

Updating is strongly recommended for every server. No configuration changes are required.

## Short marketplace update text

**Title:** ChunkVeil 1.0.1 — Explosion Packet Fix

**Description:** Fixes explosions tripping the security quarantine (world appears frozen until restart) on Minecraft 1.21.2+ with the default `cancel-explosions: true`. Strongly recommended update for all servers. No config changes needed.
