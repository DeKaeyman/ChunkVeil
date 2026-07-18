# Compatibility verification

ChunkVeil keeps two different kinds of evidence separate:

- **Unit/fixture tests** validate packet transformations and failure behaviour without a Minecraft server.
- **Boot matrix** downloads checksum-pinned Paper and ProtocolLib jars, starts a real server, waits for both plugins and the packet listener to initialize, runs `/chunkveil verify`, and shuts the server down cleanly.

Boot success proves initialization compatibility. It does not claim that every packet path was exercised by a real client; those paths remain `INITIALIZED` until real traffic exercises them.

## Pinned next-snapshot matrix

| Paper artifact | ProtocolLib artifact | Java | Current evidence |
| --- | --- | --- | --- |
| 1.21.8 build 60 | 5.4.0 stable | 21 | Pending CI boot |
| 1.21.11 build 132 | dev asset updated 2026-07-13 | 25 | Pending CI boot |
| 26.1.2 build 74 | dev asset updated 2026-07-13 | 25 | Pending CI boot |
| 26.2 build 62 | dev asset updated 2026-07-13 | 25 | Pending CI boot |

The canonical versions, release history, exact URLs, and SHA-256 values are maintained once in [`release-metadata.json`](../release-metadata.json). Gradle reads the development/dependency versions from it, CI derives its boot matrix from it, and `update.json` is generated with `./gradlew syncUpdateManifest`. The upstream `dev-build` URL is mutable, so CI deliberately fails when its pinned checksum changes.

The published 0.5.0 verification claims in the README remain historical release evidence. Do not change a pending row above to passed until the corresponding CI boot job succeeds.
