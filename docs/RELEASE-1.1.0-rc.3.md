# ChunkVeil 1.1.0-rc.3 — No more thread-check spam from other plugins' packet flushes

ChunkVeil 1.1.0-rc.3 fixes thread-check log spam (and the resulting TPS stutter) on Folia-family servers, reported on Canvas 26.2 with TAB and FancyHolograms installed. Plugins like these send packets from their own async threads, which flushes queued entity-spawn packets on those threads. ChunkVeil's spawn handler asked ProtocolLib to resolve the live entity, and that resolution performs a server-internal entity lookup that regionized servers reject on foreign threads, logging a full stack trace for every queued spawn packet — holograms refresh constantly, so logs flooded and TPS stuttered. Protection itself never tripped; this was purely spam.

On Folia-family servers the spawn handler now works entirely from the packet's own data (entity id, uuid, position, and type) and never resolves the live entity. That was already the tested fallback path for unresolvable entities, and the packet data is what the client actually receives, so hiding behavior is unchanged. Paper keeps its existing live-entity path untouched.

After this change the packet listener's entire surface consists of packet data, static registry lookups, plain field reads, and thread-safe scheduler calls — safe regardless of which thread another plugin flushes a connection from, which closes this class of incompatibility for good.

## Short marketplace update text

**Title:** ChunkVeil 1.1.0-rc.3 — Folia Log-Spam Fix

**Description:** Fixes thread-check log spam and TPS stutter on Folia-family servers when plugins like TAB or FancyHolograms flush packets from their own threads. The entity-spawn handler now works purely from packet data on Folia. Strongly recommended for all Folia testers; no changes for Paper.
