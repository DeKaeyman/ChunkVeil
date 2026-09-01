# ChunkVeil 1.1.0-rc.1 — Experimental Folia support

ChunkVeil 1.1.0-rc.1 adds experimental Folia support in the same universal jar. There is no separate build: all scheduling now goes through Paper's modern scheduler APIs (global-region, async, and per-entity schedulers), which run on the main thread on plain Paper and on the owning region threads on Folia. Every platform branch is constant on Paper, so existing Paper behavior is preserved by construction.

The engine's single global tick worker became one repeating worker per online player on that player's entity scheduler, with unchanged per-player budgets, queue fairness, and reveal ordering. On Folia, chunk and entity access is guarded by region ownership and always degrades toward keeping data hidden — never toward revealing it — and runtime failures still fail closed exactly as on Paper.

Initial Folia targets are Folia 1.21.11 and 26.2 with the pinned ProtocolLib development build on Java 25. Both are checksum-pinned and boot-verified with `/chunkveil verify --ci` in the release-gated CI matrix, alongside the existing Paper matrix. Known Folia limitations in this release: `/chunkveil disable` restores chunks asynchronously per region (its chunk count reads 0), entities hidden in a region the viewer no longer owns may stay hidden until re-tracked or rejoin, and TPS-adaptive scan quality is inactive.

This is a release candidate: run it on a test server first, especially on Folia, and report issues with your exact server build, Java and ProtocolLib versions, and any region-related error logs.

## Short marketplace update text

**Title:** ChunkVeil 1.1.0-rc.1 — Experimental Folia Support

**Description:** One universal jar now runs on Paper and, experimentally, Folia (1.21.11 / 26.2 with the pinned ProtocolLib dev build on Java 25). Paper behavior is unchanged; on Folia everything unreachable across region boundaries stays hidden rather than leaking. Release candidate — test before production use.
