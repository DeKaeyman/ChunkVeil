# Changelog

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
