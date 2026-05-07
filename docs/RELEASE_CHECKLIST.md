# ChunkVeil Release Checklist

## Before Uploading

- Build locally with `./gradlew build`.
- Test on a clean Paper 1.21.11 server with Java 21.
- Test with the ProtocolLib build you name on the resource page.
- Run `/chunkveil status` after startup.
- Test `/chunkveil reload`, `/chunkveil refresh`, `/chunkveil disable`, and `/chunkveil enable`.
- Confirm `plugins/ChunkVeil/config.yml` and `plugins/ChunkVeil/lang.yml` generate correctly.
- Test one normal player and one player with `chunkveil.bypass`.
- Test teleporting between enabled and disabled worlds.
- Test chunk unload/reload by moving beyond render distance and returning.
- Test hidden entities with `hide-entities: true`.
- Test performance with realistic player count or a small public beta.

## Compatibility Notes To Publish

- Paper 1.21.11 only until more versions are tested.
- Java 21 required.
- ProtocolLib required.
- Nether and End are disabled by default.
- Packet internals can break when Paper, Minecraft, or ProtocolLib changes.

## Public Release Advice

- Publish the source on GitHub with a clear license.
- Use GitHub Releases for downloadable jars.
- Keep issues enabled so server owners can report bugs.
- Keep a public changelog.
- Do not sell it as a guaranteed all-client anti-cheat.
- Promise clear support boundaries: supported server version, required ProtocolLib version, and response expectations.

## Good Bug Report Requirements

- Exact Paper build.
- Exact ProtocolLib build.
- ChunkVeil version.
- Full startup log.
- Config file.
- Steps to reproduce.
- Screenshots or video when visual.
- Whether the issue happens without other plugins.
