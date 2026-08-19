# Changelog

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
