# ChunkVeil TODO

This file tracks concrete work items from `docs/ROADMAP.md`.

Statuses:

- `[x]` Done.
- `[~]` Partly done or needs follow-up.
- `[ ]` Not started.

## Done

- [x] Implement fail-closed runtime protection.
- [x] Remove post-send chunk masking fallback.
- [x] Refuse runtime startup when ProtocolLib does not expose required chunk packet support.
- [x] Refuse runtime startup when raw chunk packet rewriting cannot initialize.
- [x] Cancel hidden chunk packets when rewrite fails.
- [x] Disable runtime protection after critical packet rewrite or packet inspection failures.
- [x] Show disabled reason in `/chunkveil status`.
- [x] Show enable failure reason in `/chunkveil enable`.
- [x] Log fail-closed errors as clear `SEVERE` messages.
- [x] Update roadmap with fail-closed policy and completed status.
- [x] Rewrite Modrinth and Spigot descriptions with stronger server-admin search wording.

## Next

- [x] Add `/chunkveil compat`.
- [x] Show server Minecraft version in compatibility output.
- [x] Show Paper API/version info when available.
- [x] Show Java version.
- [x] Show ProtocolLib version.
- [x] Show raw chunk packet rewrite status.
- [x] Show last compatibility/startup failure reason.
- [x] Show enabled worlds.
- [x] Show unsupported setup warnings.
- [x] Improve `/chunkveil status` grouping.
- [x] Add config validation at startup and reload.
- [x] Warn when configured worlds do not exist.
- [x] Warn when `hide-below-y <= min-y`.
- [x] Warn when fake block settings are invalid or risky.
- [x] Warn when ray counts are unusually high.
- [x] Warn when `hide-air: true` is combined with expensive scan settings.
- [x] Warn when Nether or End use suspicious overworld-style fake blocks.
- [x] Warn when no worlds are enabled.

## Admin Diagnostics

- [x] Add `/chunkveil inspect <player>`.
- [x] Show player world.
- [x] Show bypass permission state.
- [x] Show client view distance.
- [x] Show effective scan radius.
- [x] Show visible chunk count.
- [x] Show queued chunk update count.
- [x] Show hidden entity count.
- [x] Show last reveal scan age.
- [x] Show whether the player's current chunk is hidden or revealed.
- [x] Add `/chunkveil report`.
- [x] Include plugin version in report.
- [x] Include server version in report.
- [x] Include Java version in report.
- [x] Include ProtocolLib version in report.
- [x] Include enabled worlds in report.
- [x] Include chunk packet rewrite status in report.
- [x] Include last compatibility failure reason in report.
- [x] Include key config values in report.
- [x] Include metrics snapshot in report.

## Performance

- [x] Move chunk update work budgets into config.
- [x] Make priority chunk updates per player per tick configurable.
- [x] Make regular chunk updates per player per tick configurable.
- [x] Add entity scan interval/budget config.
- [x] Add scan timing metrics.
- [x] Track reveal scan time.
- [x] Track chunk masking time.
- [x] Track entity scan time.
- [x] Track queue processing time.
- [x] Show average/max timings in debug/status output.
- [x] Cache reveal ray directions per yaw bucket and scan profile.
- [x] Add `/chunkveil predict <players> <ramGb> <cpuTier> [viewDistance]`.
- [x] Use live timing samples in performance predictions when available.
- [x] Add optional adaptive scan quality.
- [x] Reduce ray counts automatically below a configured TPS threshold.

## Compatibility And Safety

- [x] Enforce fail-closed behavior for known startup and runtime packet rewrite failures.
- [x] Add stronger startup self-test where possible.
- [x] Warn when the installed ProtocolLib build is not known to support the current Minecraft/Paper version.
- [x] Check supported server version range at startup.
- [x] Check required chunk packet wrappers at startup.
- [x] Check chunk packet coordinate access at startup if possible.
- [x] Check chunk data buffer access at startup if possible.
- [x] Keep runtime fail-closed for incompatibilities that only appear on real packets.
- [x] Add compatibility test matrix in CI.
- [x] Test Paper 1.21.11.
- [x] Test Java 21.
- [x] Test Paper 1.21.8 before claiming broader 1.21.x support.

## Leak Hardening

- [x] Audit packet coverage for block changes.
- [x] Audit packet coverage for multi-block changes.
- [x] Audit packet coverage for block entity data.
- [x] Audit packet coverage for entity spawn packets.
- [x] Audit packet coverage for entity follow-up packets.
- [x] Audit packet coverage for fluid updates.
- [x] Cancel explosion packets (`EXPLOSION`) when the center is in a hidden underground zone.
- [x] Cancel world event packets (`WORLD_EVENT`) at hidden underground positions.
- [x] Cancel block break animation packets (`BLOCK_BREAK_ANIMATION`) at hidden underground positions.
- [x] Cancel positional sound packets at hidden underground positions.
  - [x] Cancel entity sounds for hidden entities (`ENTITY_SOUND`).
  - [x] Cancel `SOUND_EFFECT` and `NAMED_SOUND_EFFECT` when originating from a hidden underground position.
- [x] Improve refresh behavior around `/chunkveil reload`.
- [x] Improve refresh behavior around `/chunkveil disable`.
- [x] Improve refresh behavior around `/chunkveil enable`.
- [x] Improve refresh behavior around world changes.
- [x] Improve refresh behavior around teleports.
- [x] Improve refresh behavior around render distance changes.
- [x] Refresh players when `chunkveil.bypass` changes, if detectable.

## Bug Fixes

- [x] Fix: sub-16 hidden range triggers fail-closed. `ChunkPacketBlockRewriter` now handles partial hidden sections.
- [x] Fix: non-16-aligned `hideBelowY` exposes partial top section briefly. Partial top sections are rewritten before the MAP_CHUNK packet is sent.
- [x] Fix: `writeAirAwarePalette` hardcoded `new long[256]`. Packet rewrite now derives packed array sizing from the active bits-per-entry and section size.
- [ ] Fix/verify: `cancelHiddenPositionalSound` reads sound position from `getIntegers()` indices 0/1/2, assuming ProtocolLib exposes sound identifier and category via typed modifiers leaving x/y/z as the first integers. Confirm this holds for both `CUSTOM_SOUND_EFFECT` and `NAMED_SOUND_EFFECT` on the supported server versions. Failure mode is silent (sounds not cancelled), not a crash.

## Code Quality

- [x] Remove dead code: `VeilWorldSettings.hiddenSectionCount()` and its wrapper `VeilSettings.hiddenSectionCount(World)` are never called.
- [x] Add a `canUse()` permission guard to the `version` subcommand in `VeilCommand`.
- [x] Reset `VeilMetrics` on `reloadVeil()` so that `/chunkveil predict` uses timing samples from the current config, not a previous one.
- [x] Split `chunkMaskTiming` into two separate metrics: one for netty-thread binary packet rewriting (`ProtocolChunkListener`) and one for main-thread multi-block sending (`VeilEngine.sendChunkMode`).
- [ ] Migrate `VeilLang` from deprecated `ChatColor.translateAlternateColorCodes` to the Adventure/MiniMessage API (Paper 1.21+ preferred path).

## Documentation And Marketplace

- [x] Add roadmap.
- [x] Add TODO.
- [x] Update Modrinth description.
- [x] Update Spigot description.
- [ ] Update README with fail-closed behavior.
- [ ] Update README with troubleshooting for incompatible ProtocolLib versions.
- [ ] Add recommended ProtocolLib version notes per supported Minecraft/Paper version.
- [ ] Add release checklist to docs.
- [ ] Add screenshots or logs showing fail-closed behavior.
