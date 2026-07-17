# Packet Coverage

This document applies to ChunkVeil 0.5.0.

It lists every information path ChunkVeil knows about, whether it is protected, under which conditions, and how that protection is verified. The goal is not to claim perfection — it is to show exactly where the boundaries are.

## Verification levels

- **Automated** — covered by the packet regression test suite (`src/test`), run in CI on every push and against every claimed Paper API version.
- **Manual** — verified by hand on the release's verified server versions (xray/freecam client, `/chunkveil status` counters, `/chunkveil inspect`).
- **Not audited** — not yet analyzed in depth; treat as unprotected until this table says otherwise.
- **Documented gap** — intentionally not covered; the trade-off is documented.

## Chunk data (the critical path)

| Information path | Covered | Conditions | Verification |
| --- | --- | --- | --- |
| Initial chunk block states below cutoff | Yes | Protected world, below `hide-below-y` | Automated |
| Single-value palette sections | Yes | — | Automated |
| Indirect palette sections (1-8 bits) | Yes | Including non-vanilla low bit counts | Automated |
| Direct palette sections (≥9 bits) | Yes | — | Automated |
| Partial sections crossing `hide-below-y` | Yes | Blocks below cutoff faked, above kept | Automated |
| Negative world heights (`min-y: -64`) | Yes | — | Automated |
| 26.x little-endian section block counts | Yes | Minecraft 26.x format | Automated |
| Biome data pass-through integrity | Yes | Hidden and visible sections | Automated |
| Trailing packet data (light, block entities) integrity | Yes | Preserved byte-identical | Automated |
| Malformed or truncated chunk buffers | Fail-closed | Rewrite throws → packet cancelled → protection shuts down | Automated (rewrite failure) + Manual (cancellation path) |
| Non-empty block count consistency | Yes | Client-visible counts match faked content | Automated |

## Block and block entity updates

| Information path | Covered | Conditions | Verification |
| --- | --- | --- | --- |
| Block entities in the initial chunk packet | Yes | Below cutoff while chunk hidden | Manual |
| Block entity update packets | Yes | Below cutoff, hidden for viewer | Manual |
| Single block change packets | Yes | Hidden block for viewer | Manual |
| Multi-block change packets | Yes | Hidden blocks rewritten to fake block | Manual |

## Entities

| Information path | Covered | Conditions | Verification |
| --- | --- | --- | --- |
| Entity spawn packets (all spawn types) | Yes | `hide-entities: true`, entity in hidden area | Manual |
| Entity follow-up packets (metadata, equipment, movement, velocity, effects, sounds, mount/attach, attributes) | Yes | Entity currently hidden for the viewer | Manual |
| Entity destroy packets | Passed through | Used to un-track hidden entities | Manual |
| Player entities underground | Optional | `hide-players: true` (off by default) | Manual |

## Secondary information

| Information path | Covered | Conditions | Verification |
| --- | --- | --- | --- |
| Explosion packets | Yes | `packet-protection.cancel-explosions: true` (default) | Manual |
| World event packets | Yes | `packet-protection.cancel-world-events: true` (default) | Manual |
| Block break animations | Yes | `packet-protection.cancel-block-crack: true` (default) | Manual |
| Positional sound packets | Yes | `packet-protection.cancel-positional-sounds: true` (default) | Manual |

## Known boundaries

| Information path | Covered | Why |
| --- | --- | --- |
| Particle packets | No | Not audited yet. Underground particles (e.g. from machines or mob farms) may be visible. Audit planned. |
| Sculk vibration / game event packets | No | Not audited yet. Audit planned. |
| Light data | No | Light is sent unmodified. Cave lighting can weakly hint at hollow spaces. |
| Cave/tunnel shapes with `hide-air: false` | No | Intentional default trade-off, documented everywhere. Enable `hide-air: true` to close it. |
| Anything above `hide-below-y` | No | Out of product scope by design. |
| Plugins rewriting packets after ChunkVeil | No | ChunkVeil uses a late listener priority, but cannot control other plugins. Test your stack. |
| Combat/movement/mining-behaviour cheats | No | ChunkVeil is not an anti-cheat. |

## Fail-closed behaviour

For every path marked *fail-closed*: if ChunkVeil cannot safely inspect or rewrite a critical packet, the packet is cancelled and runtime protection shuts down with loud console warnings, admin notifications, and a `VeilProtectionStatusEvent`. There is no silent fallback to sending real data. Use `/chunkveil verify` to confirm the current state at any time.
