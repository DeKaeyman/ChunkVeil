# Changelog

## 1.2.0-beta.1

A huge performance release. The whole plugin was audited for lag and bugs on Paper and Folia, and every hot path was reworked and measured with a new stress test harness (100 fake players on one server). Protection is unchanged: the same blocks, entities and packets stay hidden, fail-closed as before.

### Performance

- **Chunk rewriting is about 9x faster** (452 µs to about 50 µs per chunk) and allocates about 20x less memory. Visible sections and biomes are copied straight through, fully hidden sections are written without decoding, and the output is byte-identical to the old rewriter (checked against it on 1500 random chunks).
- **Chunk rewrite cache:** when the same chunk is sent to several players (players together at spawn, in towns, reveal resends), it is rewritten once instead of once per player.
- **Reveal ray scans cost about half** (507 µs to 261 µs per scan in the 100-player test): blocks are read straight from the chunk sections with one chunk lookup per chunk instead of per block, and rays walk voxel by voxel (DDA), which also stops them seeing through diagonal walls.
- **Entity scans cost about half** (339 µs to 192 µs per scan): they only look at the concealed band instead of the full world height, and only as far as entities are actually sent to players (the tracking range instead of the view distance).
- **No more chunk re-hide lag spikes.** Chunks leaving a player's view used to be re-masked block by block on the main thread, about 13 ms each. That work is gone.
- **Reveals go to one player.** A reveal used to resend the chunk to every nearby player, twice; now only the player who needs it gets it.
- **`/chunkveil reload` no longer resends every chunk to everyone.** Only worlds whose concealment settings changed are resent, per player and spread over ticks. `/chunkveil disable` restores chunks the same way.
- Entity follow-up packets skip all work when nothing is hidden for a viewer; the bypass permission is cached instead of checked per packet and per block; packet types for disabled features are no longer subscribed; the per-player worker does nothing for players who cannot need it.
- In the 100-player stress test, ChunkVeil's total cost dropped 31% in the last optimization pass alone, and the server reached 8.1 instead of 6.9 TPS.

### Fixes

- **Entities:** hidden mobs could stay invisible for good, reveals of packet-only hidden entities did nothing, names, items and armor could leak right after a hidden spawn, entities were only re-checked when the viewer crossed a chunk, a mob that came back with a new entity id could no longer be hidden, and hidden mobs whose chunk unloaded stayed invisible until rejoin.
- **Chunks:** `min-y` was ignored by the chunk rewriter, the light of a section crossing an unaligned `min-y` was sent unchanged, sections with more than 256 block types near the cutoff could be corrupted on the client (direct palette width), and `cave_air`/`void_air` were treated as solid (carved caves filled with fake blocks under `hide-air: false`).
- **Minecraft 1.21.0 to 1.21.4:** the older chunk format (length-prefixed data arrays) is now supported; Paper 1.21.4 is in the boot-verified matrix.
- **End:** enabling the End with the default settings quarantined protection; the default band is now `hide-below-y: 48`, `min-y: 0`.
- **Reveals:** vehicles and standing still now get reveals (the scan no longer needs movement events), teleports scan the new location instead of the old one, and the scan radius follows per-world and per-player view distance.
- **Folia:** one chunk owned by another region no longer blocks a player's whole reveal queue, a rare unload race could leave a chunk fake for good, the disable/reload resend no longer skips chunks of neighbouring regions, reloads no longer let chunks through unprotected in the moment between the old and the new listener, API-hidden entities in other regions are released safely, dead hidden entities no longer pile up, and adaptive scan quality now uses the player's region TPS.
- Quitting players no longer trigger an entity reveal; timing "max" values in `/chunkveil status` are now the last 5 minutes instead of all-time.

### New

- `/chunkveil metrics`: raw counters and timings for scripts (every packet path is now timed).
- Emergency switches: `-Dchunkveil.disableFastTerrain=true`, `-Dchunkveil.disableRewriteCache=true`, `-Dchunkveil.disableEntityBandScan=true` turn the new fast paths off without a new build.
- `stress/`: a stress test harness for Paper and Folia (fake players, a driver plugin, JFR hotspot analysis, before/after runs). See `stress/README.md`.

### Compatibility

- ProtocolLib dev build of 2026-10-06 pinned for the 1.21.9+ and 26.x boot matrix.
- Minecraft 26.3 is not supported yet: with the current ProtocolLib dev build, ProtocolLib's light-data wrapper fails to initialise on 26.3, so ChunkVeil refuses to send concealed chunks and quarantines (fail-closed, as designed). It will be enabled once ProtocolLib fixes it.
- Documented: `hide-players` also removes a hidden player from the viewer's tab list (Paper behaviour), and chunk heightmaps are sent unchanged (see docs/COVERAGE.md).
- For big servers: the vanilla locator bar (`gamerule locator_bar`) costs a lot with many players; in the stress test it was the largest cost on the whole server.

## 1.1.0-rc.4

A running server is never taken down by a single bad packet anymore. Motivated by a Canvas 26.2 field report where one malformed chunk packet (out of 2.5 million, produced by one of three simultaneously installed anti-xray plugins) tripped the quarantine and kicked a full server.

- Added runtime failure tolerance (`security.runtime-failure-tolerance`, default 5 failures per 10 minutes): a packet that cannot be protected is dropped instead of sent — nothing unprotected ever leaves the server — the chunk is re-requested so the viewer gets it properly protected moments later, and protection keeps running. Only repeated failures within the window apply the configured `runtime-trip-action`, because that pattern means something is systematically wrong. The strict preset sets `max-failures: 1` to keep trip-on-first-failure. Startup behavior is unchanged: boot-time incompatibilities still stop the server under the strict startup policy.
- Tolerated failures are logged with real diagnostics — the parse position and buffer size for chunk data ("buffer ended early" now says where and how much) — and, for chunk failures, the log names plugins with actual evidence of modifying chunk packets (other MAP_CHUNK listeners, known rewriters like Orebfuscator) as the likely cause. No speculative startup warnings: conflicts are named only when a failure has actually happened.
- `/chunkveil verify` reports tolerated failures within the current window as a warning while confirming protection is still active. Packet-path health recovers to ENFORCED after a successful rewrite instead of showing FAILED forever.
- Closed a fail-open gap: after a legacy multi-block-change rewrite failure, subsequent multi-block packets were skipped unrewritten while protection appeared active. Every failing packet is now cancelled and counted instead.

## 1.1.0-rc.3

- Stopped thread-check log spam (and the resulting TPS stutter) on Folia-family servers when other plugins flush packets from their own threads, reported on Canvas 26.2 with TAB and FancyHolograms. Asking ProtocolLib to resolve the live entity for a spawn packet performs a server-internal entity lookup, which regionized servers reject on foreign threads and log with a full stack trace on every queued spawn packet. On Folia-family servers the entity-spawn handler now works purely from the packet's own id, uuid, position, and type and never resolves the live entity; this was already the tested fallback path, and it is the data the client actually receives. Paper keeps its existing behavior unchanged. With this, the packet listener's entire surface is packet data, static registry data, plain field reads, and thread-safe scheduler calls, so it is safe no matter which thread another plugin flushes a connection from.

## 1.1.0-rc.2

Turns the most common misconfiguration — a ProtocolLib build that does not support the server's Minecraft version — from a confusing first-join failure into a clear, named startup refusal. Motivated by real 1.1.0-rc.1 field reports (ProtocolLib 5.4.0 on Folia 26.x).

- Fixed a Folia-family security trip in the entity-spawn packet handler (reported on Canvas 26.2): when another plugin flushes a player's connection from a foreign thread — for example sending a chat message from the global thread — a queued spawn packet reached ChunkVeil on a thread that did not own the spawned entity, live-entity getters threw a region thread-check exception, and protection tripped fail-closed (experienced as a server-wide freeze). The packet path now never touches a live entity it does not own and falls back to the packet's own id/uuid/position data, which is always safe. The VEIL OFF banner also recognizes thread-check trips and no longer suggests a ProtocolLib change for them.
- Startup now probes the exact ProtocolLib reflection chain the concealed-chunk path needs. On an incompatible build (for example 5.4.0 on 1.21.9+/26.x, which only fails once the first chunk carrying a block entity is rewritten), the server refuses to start under the default strict policy and the log names the installed ProtocolLib version, the server version, and the download that fixes it.
- The VEIL OFF banner recognizes the reflection-failure signature on runtime trips too and prints the same "likely cause: your ProtocolLib build does not support Minecraft X" diagnosis with the dev-build link.
- `/chunkveil verify` warns about the known-bad pairing (ProtocolLib ≤ 5.4.0 on Minecraft 1.21.9+/26.x) even before any packet traffic exists.
- Added Folia 26.1.2 to the checksum-pinned, boot-verified target matrix (real rc.1 testers run it).
- Documentation: the ProtocolLib picker in the README and marketplace pages now states bluntly that 5.4.0 does NOT support 1.21.9+/26.x even though most other plugins run fine with it; the bug-report template asks for the matching build explicitly; `KICK_PLAYERS` is documented as the recommended trip action for survival servers because a quarantined world looks like a crashed server while a kick carries a clear message.
- On Folia, `/chunkveil refresh` now reports that the refresh was scheduled per region instead of implying synchronous completion. Internal cleanups: the platform-split hidden-entity validation shares one reveal condition, and lifecycle fan-out loops share one helper.

## 1.1.0-rc.1

Experimental Folia support in the same universal jar. Paper behavior is preserved by construction: every platform branch is constant on Paper, and all scheduling now uses Paper's modern scheduler APIs, which run on the main thread there.

- Added experimental Folia support (`folia-supported: true`). Initial targets are Folia 1.21.11 and 26.2 with the pinned ProtocolLib dev build; both are boot-verified in CI but not yet gameplay-verified.
- Replaced every legacy Bukkit scheduler call with a shared routing layer (`VeilScheduler`) over Paper's global-region, async, and per-entity schedulers. A Gradle check now fails the build if legacy scheduler usage reappears in runtime sources.
- The engine's single global tick worker is now one repeating worker per online player on that player's entity scheduler. Per-player budgets, queue fairness, reveal/hide ordering, and adoption of untracked players are unchanged; on Paper all workers still run on the main thread.
- Queue-processing timing is now recorded per player per tick instead of per server tick; `/chunkveil predict` scales the queue term by player count accordingly, and status, report, and debug output label the metric as per-player.
- Per-player chunk bookkeeping uses concurrent structures, so chunk-unload cleanup stays synchronous on both platforms and a fast unload/reload cycle cannot erase a fresh hidden-chunk marker.
- On Folia, chunk and entity access is guarded by region ownership and always degrades toward staying hidden: rays stop at unowned chunks, queued updates for unowned chunks are skipped like unloaded ones, and entities in unowned regions stay hidden until they can be re-validated.
- On Folia, the wide-radius entity scan collects candidates from owned loaded chunks instead of `getNearbyEntities`, and hidden-entity validation no longer uses global UUID lookups. Adaptive scan quality treats Folia as a full-speed server because there is no global TPS.
- Lifecycle commands (`reload`, `disable`, `enable`, `debug`) run their transitions on the global region thread on Folia; `refresh` and `inspect` fan out to each player's owning thread. All of these remain synchronous on Paper.
- Added a `runFolia` Gradle task (isolated `run/folia` directory, port 25580, auto-downloaded ProtocolLib) and a Folia boot matrix in CI alongside the existing Paper matrix, with pinned SHA-256 Folia builds in `release-metadata.json` and region-ownership violations failing the boot check.
- Known Folia limitations while support is experimental: `/chunkveil disable` restores chunks asynchronously per region (the chunk count reads 0), and entities hidden in a region the viewer no longer owns may stay hidden for that viewer until they are re-tracked or the viewer rejoins.

## 1.0.2

- Fixed cross-player packet corruption for block changes, multi-block changes, and standalone light updates. These broadcast packets are a single instance shared by every tracking player, and rewriting them in place for one player leaked the fake block data to players who should have received real data. Underground players near an observer saw their own placed or mined blocks turn into the fake block and desync into unminable ghost blocks (including "Flying is not enabled" kicks). Per-player rewrites now operate on a clone, matching the chunk packet path.

## 1.0.1

- Fixed explosion packet inspection on Minecraft 1.21.2+ where the explosion center moved from three doubles into a single `Vec3` field. With `cancel-explosions: true` (the default), any explosion tripped the security state and quarantined all protected packet traffic until restart, which players experienced as a frozen world. The handler now reads the modern vector layout and falls back to the legacy doubles layout on 1.21/1.21.1.

## 1.0.0

First stable release. Promotes the multi-player-tested `1.0.0-rc.1` code without functional changes after continued error-free server use on Paper 1.21.8, 1.21.11, 26.1.2, and 26.2.

## 1.0.0-rc.1

First 1.0 release candidate. Manually gameplay-tested with multiple players on Paper 1.21.8, 1.21.11, 26.1.2, and 26.2 in addition to the automated test and boot matrix.

- Existing configurations are now upgraded additively: newly introduced defaults are written to `config.yml` without overwriting explicit values or custom worlds.
- Documented both a performance-safe recommended posture and a strict confidentiality preset with `hide-air: true`.

- Security trips can no longer be cleared with `/enable` or `/reload`; quarantine persists until restart. Runtime trip policy is configurable as `QUARANTINE`, `KICK_PLAYERS`, or `STOP_SERVER`.
- Packet health now separates `OBSERVED` decoding from `ENFORCED` concealment, while `/verify` separates readiness from evidence and offers a machine-readable `--ci` verdict required by compatibility boots.
- Fixed stale hidden-entity UUID/ID mappings and entity-ID reuse, added missing-entity cleanup and oldest-validation-age diagnostics, and added lifecycle regression tests.
- Light updates now require readable wrappers and exact 2,048-byte arrays. Unexpected structures fail closed instead of being recorded as successful.
- Added public/full diagnostic report modes and narrowed vibration documentation to origin filtering until destination payloads are validated manually.
- Expanded `/chunkveil report` with a configuration checksum, enabled plugin versions, per-path packet health and timestamps, critical-failure context, and anonymized player rows that omit names, addresses, and coordinates.
- Clarified `/chunkveil predict` as an experimental current-workload estimate, documented its assumptions, lowered its confidence wording, and fixed an optimistic entity-scan activity discount.
- Centralized build, compatibility, pinned artifact, and release data in `release-metadata.json`; Gradle and the CI boot matrix now consume it, and `update.json` has a generation task.
- Added ten production-linked packet coordinate fixtures for modern multi-block changes, positional sounds, explosions, particles, and vibrations.
- Added a checksum-pinned real Paper/ProtocolLib boot matrix for 1.21.8, 1.21.11, 26.1.2, and 26.2, with boot evidence kept separate from client packet-path evidence.
- Entity visibility scans now use a deterministic rotating window, preventing capped scans from repeatedly starving entities at the end of the candidate list; diagnostics count inspected and deferred candidates.
- Added confidentiality-first failure handling: critical packet errors cancel the triggering packet immediately, atomically trip the listener, and quarantine subsequent protected traffic without restoring real chunks.
- Chunk block-state, block-entity, and light changes are now prepared on a cloned packet and committed atomically.
- Added a strict startup policy that stops the server during boot when mandatory protection cannot initialize; runtime trips do not stop an already-running server.
- Added fault-injection coverage for every protected packet category and a documented strict configuration preset.
- `/chunkveil verify` now distinguishes initialized packet listeners from paths proven exercised on the running server, and reports health separately for terrain, block data, entities, effects, sounds, and lighting.
- Added Paper 26.2 to the manually verified compatibility set after successful multi-player testing.
- Added filtering for particle packets and legacy vibration packets originating in concealed underground positions. Modern sculk vibrations are covered through their vibration particles.
- Added block-light and sky-light sanitization for fully concealed chunk sections and standalone light updates.
- Added packet-protection settings, verification output, diagnostics, counters, and regression tests for the new protections.
- Secondary packet inspection failures now fail closed instead of only logging a warning.
- Documented the remaining unavoidable boundaries: cutoff-crossing light sections, behavior analysis, world-seed inference, and later packet modifiers.

## 0.5.0

Verification release: automated packet regression tests, `/chunkveil verify`, and a protection status API.

- Added a packet regression test suite for the chunk rewrite path: single-value/indirect/direct palettes, partial sections crossing `hide-below-y`, `hide-air` on and off, negative world heights, the 26.x little-endian section format, biome and trailing-data pass-through integrity, idempotence, and malformed/truncated buffers. The suite runs in CI on every push, including against older claimed Paper API versions.
- Added `/chunkveil verify`: one owner-facing PASS/WARN/FAIL check covering ProtocolLib, runtime protection, the chunk rewrite path, critical failures since startup, verified vs expected Minecraft versions, per-world protection summary, secondary packet protection, other packet-modifying plugins, and config validation. `/chunkveil compat` is now an alias for it, and the `chunkveil.compat` permission was renamed to `chunkveil.verify`.
- Added `/chunkveil reload --check`: validates `config.yml` from disk, including YAML syntax errors, without applying anything.
- Added a public `com.dekaeyman.chunkveil.api.VeilProtectionStatusEvent`, fired when protection enables, is disabled by an admin, or fails closed. Monitoring plugins can now track "actually protected" instead of "plugin loaded".
- ChunkVeil remembers the last critical packet failure since startup; security trips now require a restart and cannot be cleared in-process.
- When runtime protection is inactive, ChunkVeil now shows a console banner on shutdown/refusal, logs a rate-limited reminder every 30 minutes, and warns admins in-game on join.
- Language files fall back to the embedded defaults for messages missing from an older `lang.yml`.
- Documented exact packet coverage and known boundaries in `docs/COVERAGE.md`.
- Releases now include SHA-256 checksums, and CI write permissions are scoped to the release job only.

## 0.4.0

Update notifications and usage statistics.

- Added an in-game update checker driven by a hosted version manifest (`update.json`). Updates are only offered when a release declares compatibility with the server's exact Minecraft version. Admins with `chunkveil.update` get a clickable notice on join, and `/chunkveil update` checks on demand.
- Added anonymous aggregate usage statistics through bStats with ChunkVeil-specific charts (opt-out via `metrics.enabled: false` or the global bStats config). bStats is shaded and relocated into the plugin jar.
- The distributed jar is now built with `shadowJar` instead of `jar`.

## 0.3.0

Single universal jar with native Minecraft 26.x packet rewriting.

- Added Minecraft 26.x chunk section format support to the raw chunk packet rewriter. 26.x now uses the same before-send rewrite path as 1.21.x.
- Removed the temporary post-send chunk masking fallback introduced in 0.2.1. All supported versions now fail closed instead of falling back.
- Merged the separate 1.21 and 26.1 builds into one universal jar.

## 0.2.3

Diagnostics and fail-closed hardening.

- Fail closed on packet rewrite incompatibility: the unsafe packet is cancelled and runtime protection shuts down instead of sending real chunk data.
- Added `/chunkveil compat` compatibility diagnostics.
- Added configuration validation with startup warnings.
- Added `/chunkveil inspect <player>` and `/chunkveil report` diagnostic reports.
- Added performance budgets and `/chunkveil predict` prediction metrics.

## 0.2.2

Release packaging fix.

- Run Gradle on Java 21 in GitHub Actions while keeping Java 25 available for Paper 26.1 builds.
- Renamed the Paper 1.21 artifact to include `paper-1.21` in the jar name.

## 0.2.1

Paper 26.1 compatibility release.

- Added separate Paper 26.1.x build and run targets.
- Added Java 25 toolchain support for Paper 26.1.x.
- Updated the Gradle wrapper and run-paper plugin for newer Paper downloads.
- Disabled raw chunk packet rewriting on Minecraft 26.x and fall back to post-send chunk masking. *(Historical: this fallback was removed in 0.3.0; 26.x now uses the same before-send rewrite path as 1.21.x, and unsupported situations fail closed instead.)*
- Kept Paper 1.21.11 on the existing packet rewrite path.

## 0.2.0

First stable release.

- Removed the beta version suffix.
- Optimized view reveal scanning with directional rays and scan skipping.
- Added configurable front, side, and back reveal ray counts.
- Added configurable movement, yaw, pitch, and forced refresh thresholds.
- Configured the local Paper run task to load the plugin jar automatically.
- Kept official support focused on Paper 1.21.11 with a compatible ProtocolLib build.

## 0.1.0-beta.1

Initial public beta release.

- Added packet-level underground chunk section rewriting.
- Added configurable per-world fake blocks and hidden Y ranges.
- Added 360-degree view-scan based chunk revealing.
- Added hidden block update and block entity update protection.
- Added optional underground entity hiding.
- Added admin commands, permissions, metrics, debug logging, and emergency runtime disable.
- Added configurable language file.
