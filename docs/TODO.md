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

- [ ] Move chunk update work budgets into config.
- [ ] Make priority chunk updates per player per tick configurable.
- [ ] Make regular chunk updates per player per tick configurable.
- [ ] Add entity scan interval/budget config.
- [ ] Add scan timing metrics.
- [ ] Track reveal scan time.
- [ ] Track chunk masking time.
- [ ] Track entity scan time.
- [ ] Track queue processing time.
- [ ] Show average/max timings in debug/status output.
- [ ] Cache reveal ray directions per yaw bucket and scan profile.
- [ ] Add optional adaptive scan quality.
- [ ] Reduce ray counts automatically below a configured TPS threshold.

## Reveal Logic

- [ ] Add reveal mode config.
- [ ] Keep current behavior as `view` mode.
- [ ] Add `hybrid` reveal mode.
- [ ] Add `strict` reveal mode for hidden-base or raiding servers.
- [ ] Add `distance` reveal mode for low-cost setups.
- [ ] Add optional re-hide delay.
- [ ] Add underground entry smoothing for caves, tunnels, and bases.
- [ ] Add per-world reveal profile overrides.

## Masking Options

- [ ] Add optional per-material replacement rules.
- [ ] Add replacement profile presets.
- [ ] Add `light` profile.
- [ ] Add `balanced` profile.
- [ ] Add `strict` profile.
- [ ] Add `base-protection` profile.
- [ ] Replace single `hide-entities` option with category support while keeping backward compatibility.
- [ ] Add entity category for mobs.
- [ ] Add entity category for item drops.
- [ ] Add entity category for minecarts.
- [ ] Add entity category for armor stands.
- [ ] Add entity category for item frames.
- [ ] Add entity category for projectiles.
- [ ] Add entity category for players.
- [ ] Add explicit `hide-fluids` option.

## Compatibility And Safety

- [~] Enforce fail-closed behavior for known startup and runtime packet rewrite failures.
- [ ] Add stronger startup self-test where possible.
- [ ] Check ProtocolLib version at startup.
- [ ] Check supported server version range at startup.
- [ ] Check required chunk packet wrappers at startup.
- [ ] Check chunk packet coordinate access at startup if possible.
- [ ] Check chunk data buffer access at startup if possible.
- [ ] Keep runtime fail-closed for incompatibilities that only appear on real packets.
- [ ] Add compatibility test matrix in CI.
- [ ] Test Paper 1.21.8.
- [ ] Test Paper 1.21.11.
- [ ] Test Paper 26.1.x only if raw chunk rewrite support is restored or explicitly supported.
- [ ] Test Java 21.
- [ ] Test Java 25 only for supported Paper 26.1 builds.

## Leak Hardening

- [ ] Audit packet coverage for block changes.
- [ ] Audit packet coverage for multi-block changes.
- [ ] Audit packet coverage for block entity data.
- [ ] Audit packet coverage for entity spawn packets.
- [ ] Audit packet coverage for entity follow-up packets.
- [ ] Audit packet coverage for sounds/events that could expose hidden underground entities or blocks.
- [ ] Audit explosion-related behavior.
- [ ] Audit fluid update behavior.
- [ ] Improve refresh behavior around `/chunkveil reload`.
- [ ] Improve refresh behavior around `/chunkveil disable`.
- [ ] Improve refresh behavior around `/chunkveil enable`.
- [ ] Improve refresh behavior around world changes.
- [ ] Improve refresh behavior around teleports.
- [ ] Improve refresh behavior around render distance changes.
- [ ] Refresh players when `chunkveil.bypass` changes, if detectable.

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

## Not Planned

- [ ] Do not add bans, alerts, punishments, or cheat detection.
- [ ] Do not add combat anti-cheat checks.
- [ ] Do not add movement anti-cheat checks.
- [ ] Do not add claims, factions, land protection, or economy systems.
- [ ] Do not add Spigot-first support if it weakens Paper reliability.
- [ ] Do not add Folia support until the scheduler/state model is redesigned for region threading.
