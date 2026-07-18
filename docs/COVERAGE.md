# Packet Coverage

This document applies to the development version after ChunkVeil 0.5.0.

It lists every information path ChunkVeil knows about, whether it is protected, under which conditions, and how that protection is verified. The goal is not to claim perfection — it is to show exactly where the boundaries are.

## Verification levels

- **Automated** — covered by the packet regression test suite (`src/test`), run in CI on every push and against every claimed Paper API version.
- **Manual** — verified by hand on the release's verified server versions (xray/freecam client, `/chunkveil status` counters, `/chunkveil inspect`).
- **Not audited** — not yet analyzed in depth; treat as unprotected until this table says otherwise.
- **Documented gap** — intentionally not covered; the trade-off is documented.

At runtime, `/chunkveil verify` separately reports whether each enabled category is merely **INITIALIZED**, has been **EXERCISED** successfully by real packets, is **PARTIAL**, or has **FAILED** and tripped quarantine.

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
| Particle packets | Yes | `packet-protection.cancel-particles: true` (default), concealed source position | Manual |
| Sculk vibration particles | Yes | Covered by particle filtering on modern versions | Manual |
| Legacy vibration packets | Yes | `packet-protection.cancel-vibrations: true` (default), when exposed by ProtocolLib | Manual |
| Block and sky light arrays | Yes for fully concealed sections | `packet-protection.sanitize-light: true` (default) | Automated transformation + Manual packet path |

## Known boundaries

| Information path | Covered | Why |
| --- | --- | --- |
| Light in a section crossed by `hide-below-y` | Partial | The section is left intact to avoid corrupting visible lighting above the cutoff. Align the cutoff to a 16-block boundary to remove this edge. |
| Cave/tunnel shapes with `hide-air: false` | No | Intentional default trade-off, documented everywhere. Enable `hide-air: true` to close it. |
| Anything above `hide-below-y` | No | Out of product scope by design. |
| Plugins rewriting packets after ChunkVeil | No | ChunkVeil uses a late listener priority, but cannot control other plugins. Test your stack. |
| Combat/movement/mining-behaviour cheats | No | ChunkVeil is not an anti-cheat. |
| World-seed inference | No | Seed cracking uses terrain and structure observations outside ChunkVeil's concealed packet region. Use separate seed-protection tooling if this is in your threat model. |

## Fail-closed behaviour

For every enabled protected path, a critical inspection or rewrite failure cancels the triggering packet and atomically trips the packet listener. While `TRIPPED`, subsequent registered protected packet types remain cancelled. ChunkVeil stops its background runtime without refreshing real chunks and reports the failure through console warnings, administrator notifications, `/chunkveil verify`, and `VeilProtectionStatusEvent`.

During initial server boot, `security.stop-server-on-startup-failure: true` stops the server if protection cannot initialize. An explicit administrator `/chunkveil disable` is different: it intentionally restores real chunks before disabling protection.
