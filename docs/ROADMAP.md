# ChunkVeil Roadmap

This roadmap keeps ChunkVeil focused on one practical goal: helping Paper server admins reduce underground client-side information leaks without turning the plugin into a broad anti-cheat or gameplay system.

For the scan-friendly implementation checklist, see `docs/TODO.md`.

ChunkVeil should stay useful for survival, SMP, factions, raiding, semi-anarchy, and private servers that want better protection against xray, ESP, freecam, hidden-base discovery, cave scouting, block entity leaks, and underground entity leaks.

## Product Direction

ChunkVeil is a visibility-aware underground packet protection plugin.

It should not try to detect every hacked client or punish players. Instead, it should reduce what the client can learn before the player should realistically know it. The best future features are the ones that help admins configure, verify, optimize, and trust that protection.

Good feature fit:

- Prevents or reduces underground data leaks.
- Helps admins understand what protection is active.
- Improves compatibility with Paper, ProtocolLib, and common anti-xray stacks.
- Improves performance under real player load.
- Makes setup safer and clearer.
- Adds useful server-side controls without changing normal gameplay.

Poor feature fit:

- Combat checks, movement checks, kill aura checks, or punishment systems.
- Economy, claims, factions, or unrelated moderation features.
- Cosmetic systems that do not improve underground protection.
- Broad anti-cheat branding that promises more than the plugin can deliver.

## Current Strengths

- Packet-level chunk section rewriting for hidden underground areas.
- Configurable fake block per world.
- View-based reveal scanning instead of a basic radius-only reveal.
- Persistent reveals until chunks leave the player's render distance.
- Rewrites later block updates while a chunk is hidden.
- Cancels hidden block entity update packets.
- Optional underground entity hiding.
- Admin commands for status, reload, refresh, debug, enable, and emergency disable.
- Paper 1.21.x support with a separate Paper 26.1 build target.

## Completed

### Fail-Closed Runtime Protection

Status: implemented.

ChunkVeil now refuses to run in a weaker protection mode when ProtocolLib or raw chunk packet rewriting is not compatible.

Implemented behavior:

- Removed post-send chunk masking fallback.
- Startup refuses runtime protection when required ProtocolLib chunk packet support is unavailable.
- Startup refuses runtime protection when raw chunk packet rewriting cannot initialize.
- Runtime fails closed if a critical packet rewrite or packet inspection error appears after real packets start flowing.
- Hidden chunk packets are cancelled when rewrite fails, before runtime protection shuts down.
- `/chunkveil status` shows the disabled reason.
- `/chunkveil enable` reports why runtime protection could not be started.
- Fail-closed errors are logged as clear `SEVERE` messages with admin guidance to install a compatible ProtocolLib build.

Known limitation:

Some ProtocolLib incompatibilities only appear when real chunk packets are sent, usually after a player joins. ChunkVeil handles this by failing closed immediately when the first incompatible packet path is detected.

## Protection Policy

Status: active project policy.

ChunkVeil should fail closed.

If ProtocolLib is missing, incompatible, or raw chunk packet rewriting cannot initialize, ChunkVeil should not enable its runtime protection. It should log a clear startup error and disable itself instead of sending real chunk data and trying to mask it later.

Required behavior:

- No post-send chunk masking fallback.
- No partial protection mode that silently skips chunk packet rewriting.
- No "best effort" enable when the main chunk rewrite path is unavailable.
- Clear admin-facing error messages when startup is refused.
- `/chunkveil status` should make it obvious when the runtime is disabled because compatibility checks failed.

Why it matters:

ChunkVeil's security model depends on hiding underground chunk data before the client receives it. If that cannot be guaranteed, the safest behavior is to stop instead of giving admins false confidence.

## 0.3.x - Admin Confidence

Goal: make ChunkVeil easier to install, verify, and support on real servers.

### Compatibility Command

Add `/chunkveil compat`.

Show:

- Server Minecraft version.
- Paper API version when available.
- Java version.
- ProtocolLib version.
- Raw chunk packet rewriting status.
- Last compatibility/startup failure reason, if any.
- Enabled worlds.
- Warnings for unsupported setups.

Why it matters:

Admins need to know whether ChunkVeil is safely rewriting chunk packets before they test with xray/freecam clients.

### Player Inspect Command

Add `/chunkveil inspect <player>`.

Show:

- World.
- Bypass permission state.
- Client view distance.
- Effective scan radius.
- Visible chunk count.
- Queued chunk update count.
- Hidden entity count.
- Last reveal scan age.
- Whether the player's current chunk is hidden or revealed.

Why it matters:

When one player reports strange visuals, admins need player-specific diagnostics instead of only global counters.

### Config Validation

Validate `config.yml` at startup and reload.

Warn about:

- Worlds listed in config that do not exist.
- Enabled worlds with `hide-below-y <= min-y`.
- Invalid or unsafe fake blocks.
- Very high ray counts.
- `hide-air: true` with high scan settings.
- Nether or End enabled with an overworld-style fake block.
- No enabled worlds.

Why it matters:

Most bad experiences will come from misconfiguration, not from the core idea.

### Clearer Status Output

Improve `/chunkveil status`.

Group output into:

- Runtime.
- Compatibility.
- Worlds.
- Players and queues.
- Packet protection.
- Block update protection.
- Entity protection.
- Debug/performance counters.

Why it matters:

The current status command is useful, but it can become more readable as the plugin grows.

## 0.4.x - Performance Controls

Goal: give admins predictable performance on different server sizes.

### Configurable Work Budgets

Move hardcoded processing limits into config.

Useful settings:

```yaml
performance:
  max-priority-chunk-updates-per-player-per-tick: 24
  max-regular-chunk-updates-per-player-per-tick: 1
  max-entity-scan-interval-millis: 500
```

Why it matters:

Small servers can use stronger settings. Larger servers need stricter per-tick budgets.

### Adaptive Scan Quality

Add optional automatic scan scaling.

Example:

```yaml
adaptive-performance:
  enabled: true
  reduce-rays-below-tps: 18.5
  minimum-front-rays: 6
  minimum-side-rays: 3
  minimum-back-rays: 1
```

Why it matters:

Admins should not have to choose between protection and server stability during temporary lag.

### Scan Timing Metrics

Track:

- Reveal scan time.
- Chunk masking time.
- Entity scan time.
- Queue processing time.
- Average and max values over the last minute.

Expose through:

- `/chunkveil status`.
- `/chunkveil debug on`.
- Future metrics export.

Why it matters:

Admins need real numbers before changing ray counts or hide-air behavior.

### Direction Cache

Cache reveal ray directions per yaw bucket and scan profile.

Why it matters:

The reveal scan runs often. Avoiding repeated vector allocation is a clean optimization that does not change behavior.

## 0.5.x - Reveal Logic Profiles

Goal: make protection match different server types without forcing admins to understand every low-level setting.

### Reveal Modes

Add configurable reveal mode:

```yaml
reveal:
  mode: view
```

Modes:

- `view`: current visibility-based reveal style.
- `hybrid`: cheap nearby reveal plus view-based reveal farther out.
- `strict`: more conservative reveal for hidden-base or raiding servers.
- `distance`: simple lower-cost mode for servers that prefer performance over precision.

Why it matters:

A peaceful SMP and a raiding server do not need the same reveal behavior.

### Re-Hide Delay

Add optional delay before revealed chunks are hidden again after leaving range or visibility.

Example:

```yaml
reveal:
  rehide-delay-seconds: 8
```

Why it matters:

This can reduce visual flicker and repeated packet work when players move around chunk borders.

### Underground Entry Smoothing

When a player enters an underground area, reveal nearby connected chunks more smoothly.

Why it matters:

Players should not see harsh fake walls when they legitimately enter a cave, tunnel, or base.

### Per-World Reveal Profiles

Allow worlds to override global reveal settings.

Why it matters:

The Nether, End, custom worlds, and overworld caves have different shapes and performance needs.

## 0.6.x - Better Masking Options

Goal: let admins hide more types of underground information without making the default setup heavy.

### Replacement Rules

Add optional per-material replacement rules.

Example:

```yaml
worlds:
  world:
    replacements:
      DIAMOND_ORE: DEEPSLATE
      CHEST: DEEPSLATE
      BARREL: DEEPSLATE
      SPAWNER: DEEPSLATE
```

Why it matters:

Admins may want containers, spawners, or special blocks handled differently from normal stone.

### Replacement Profiles

Add presets:

- `light`: hide valuable solid blocks, keep air visible.
- `balanced`: recommended default.
- `strict`: hide air and underground shapes more aggressively.
- `base-protection`: stronger protection for hidden rooms, storage, and entities.

Why it matters:

Most admins prefer choosing a profile over tuning many settings manually.

### Entity Categories

Replace the single `hide-entities` option with optional categories while keeping backward compatibility.

Categories:

- mobs.
- item drops.
- minecarts.
- armor stands.
- item frames.
- projectiles.
- players.

Why it matters:

Some servers want to hide mobs and minecarts but never players. Others want stronger base protection.

### Fluid And Cave Shape Controls

Add explicit handling for underground air and fluids.

Useful options:

```yaml
hide-air: false
hide-fluids: false
```

Why it matters:

Air and fluids reveal cave shapes, farms, and base layouts. They are also expensive to rewrite, so admins need clear control.

## 0.7.x - Version Compatibility And Fail-Closed Startup

Goal: make future Minecraft/Paper support explicit, testable, and safe.

### Strict Startup Compatibility Checks

Add startup checks before ChunkVeil registers runtime listeners.

Required checks:

- ProtocolLib is installed and enabled.
- ProtocolLib exposes the required chunk packet wrappers.
- Chunk packet coordinates can be read.
- Chunk data buffers can be inspected and rewritten.
- Fake block state IDs can be resolved.
- The server version is in the supported compatibility range.

Why it matters:

ChunkVeil should not run unless it can protect chunks before the client receives them.

### Runtime Disable On Rewrite Failure

If packet section rewriting breaks after startup, disable ChunkVeil's runtime protection and restore online players instead of continuing in a weaker mode.

Required behavior:

- Stop packet/listener processing.
- Restore online players to real chunks where possible.
- Log the exact failure reason once.
- Mark the runtime as disabled.
- Tell admins to update Paper, ProtocolLib, or ChunkVeil.

Why it matters:

Failing closed is safer than silently allowing real underground chunk data to reach clients.

### Remove Post-Send Masking

Remove any logic that sends a real chunk first and masks it after the client has already received it.

Why it matters:

Post-send masking can leak underground data to modified clients during the gap between the real chunk packet and the later fake block updates.

### Keep One Supported Rewrite Path

Keep the supported protection path simple:

- Rewrite outgoing hidden chunk sections before send.
- Strip or cancel hidden block entity data before send.
- Rewrite hidden later block updates.
- Cancel hidden entity packets where configured.

Why it matters:

A smaller protection model is easier to audit, test, and explain to admins.

### Compatibility Test Matrix

Add CI/build checks for supported targets.

Recommended matrix:

- Paper 1.21.8.
- Paper 1.21.11.
- Paper 26.1.x.
- Java 21.
- Java 25 for Paper 26.1 builds.

Why it matters:

Compatibility claims should be backed by repeatable checks.

## 0.8.x - Leak Hardening

Goal: close edge cases that can reveal underground data after initial chunk send.

### Packet Coverage Audit

Review packet types for:

- Block changes.
- Multi-block changes.
- Block entity data.
- Entity spawn and follow-up packets.
- Sound/event packets that may expose hidden entities or blocks.
- Explosion and fluid update related behavior.

Why it matters:

The first chunk packet is only part of the leak surface.

### Cache Refresh Safety

Improve refresh behavior around:

- `/chunkveil reload`.
- `/chunkveil disable`.
- `/chunkveil enable`.
- World changes.
- Teleports.
- Render distance changes.
- Bypass permission changes.

Why it matters:

Client cache state can cause confusing visuals if chunks are not refreshed consistently.

### Bypass Refresh

When a player's `chunkveil.bypass` permission changes, refresh their visible chunks.

Why it matters:

Staff toggling vanish/moderation permissions should not keep stale fake or real chunk states.

## 0.9.x - Observability And Support

Goal: reduce support time and make bug reports actionable.

### Debug Report Command

Add `/chunkveil report`.

Generate a text report with:

- Plugin version.
- Server version.
- Java version.
- ProtocolLib version.
- Enabled worlds.
- Chunk packet rewrite status.
- Last compatibility failure reason, if any.
- Key config values.
- Metrics snapshot.
- Recent warning counts.

Why it matters:

Bug reports become much easier to diagnose.

### Optional Metrics Export

Consider bStats or a simple local metrics summary.

Track:

- Chunk packet rewrite enabled.
- Enabled worlds count.
- Hidden chunk packets.
- Rewritten chunk packets.
- Runtime compatibility failures.
- Block updates rewritten.
- Entity packets cancelled.

Why it matters:

Metrics help guide development based on real server usage.

## 1.0 - Stable Admin Release

Goal: make ChunkVeil feel production-ready for admins who do not want to read source code.

Release checklist:

- Clear compatibility command.
- Config validation.
- Stable default config.
- Performance budgets.
- At least `light`, `balanced`, and `strict` profiles.
- Updated Modrinth and Spigot descriptions.
- Updated screenshots.
- Tested startup, reload, disable, enable, and refresh.
- Tested xray/freecam visual comparison.
- Tested hidden entities and block entities.
- Tested at least one larger player simulation or profiling session.

## Marketplace Positioning

Useful search terms and phrases:

- Minecraft anti xray.
- Paper anti xray.
- anti ESP.
- freecam protection.
- hidden base protection.
- underground base protection.
- cave xray protection.
- ore obfuscation alternative.
- ProtocolLib anti xray.
- chunk packet protection.
- block entity leak protection.

Suggested short summaries:

- Packet-level anti-xray, anti-ESP, and freecam protection for underground chunks on Paper servers.
- Hide underground chunks, caves, ores, entities, and base layouts before modified clients can read them.
- Visibility-based underground protection for Paper servers using ProtocolLib packet masking.

Best honest positioning:

ChunkVeil reduces underground information leaks. It does not claim to block every hacked client, replace a full anti-cheat, or make xray impossible in every situation.

## Not Planned

These are intentionally out of scope unless the project direction changes:

- Bans, alerts, punishments, or cheat detection.
- Combat anti-cheat checks.
- Movement anti-cheat checks.
- Claims, factions, or land protection.
- Economy integration.
- Spigot-first support if it requires losing Paper-specific reliability.
- Folia support until the internal scheduler/state model is designed for region threading.
