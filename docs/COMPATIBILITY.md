# Compatibility verification

ChunkVeil keeps two different kinds of evidence separate:

- **Unit/fixture tests** validate packet transformations and failure behaviour without a Minecraft server.
- **Boot matrix** downloads checksum-pinned Paper and ProtocolLib jars, starts a real server, waits for both plugins and the packet listener to initialize, runs `/chunkveil verify`, and shuts the server down cleanly.

Boot success proves initialization compatibility. It does not claim that every packet path was exercised by a real client; those paths remain `INITIALIZED` until real traffic exercises them.

## Pinned 1.0.2 matrix

| Paper artifact | ProtocolLib artifact | Java | Current evidence |
| --- | --- | --- | --- |
| 1.21.8 build 60 | 5.4.0 stable | 21 | CI boot passed; 1.0.0 multi-player gameplay passed |
| 1.21.11 build 132 | dev asset updated 2026-08-18 | 25 | CI boot passed; 1.0.1/1.0.2 fixes gameplay passed |
| 26.1.2 build 74 | dev asset updated 2026-08-18 | 25 | CI boot passed; 1.0.0 multi-player gameplay passed |
| 26.2 build 62 | dev asset updated 2026-08-18 | 25 | CI boot passed; 1.0.0 multi-player gameplay passed |

1.0.2 fixes cross-player corruption of shared broadcast packets (block changes, multi-block changes, standalone light updates): per-player rewrites now operate on clones. The fix was verified with a two-player place/break/mine session on Paper 1.21.11, confirming underground players keep real block data while observers keep seeing fake blocks. 1.0.1 changed only the explosion-center decoding. That path was verified three ways: unit fixtures for both packet layouts, field-layout inspection of the extracted `ClientboundExplodePacket` class from the pinned Paper 1.21.11 and 26.2 artifacts (both use the 1.21.2+ `Vec3` center), and manual TNT/wind-charge gameplay on Paper 1.21.11 confirming the previous quarantine trip no longer occurs.

The canonical versions, release history, exact URLs, and SHA-256 values are maintained once in [`release-metadata.json`](../release-metadata.json). Gradle reads the development/dependency versions from it, CI derives its boot matrix from it, and `update.json` is generated with `./gradlew syncUpdateManifest`. The upstream `dev-build` URL is mutable, so CI deliberately fails when its pinned checksum changes.

The release-candidate tag passed its complete suite and exact boot matrix in [GitHub Actions run 29653866419](https://github.com/DeKaeyman/ChunkVeil/actions/runs/29653866419), and the four listed Minecraft versions passed multi-player gameplay testing and continued server use. The stable tagged release reruns the same gated matrix before publishing its assets.
