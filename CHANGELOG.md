# Changelog

## 0.2.1

Paper 26.1 compatibility release.

- Added separate Paper 26.1.x build and run targets.
- Added Java 25 toolchain support for Paper 26.1.x.
- Updated the Gradle wrapper and run-paper plugin for newer Paper downloads.
- Disabled raw chunk packet rewriting on Minecraft 26.x and fall back to post-send chunk masking.
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
