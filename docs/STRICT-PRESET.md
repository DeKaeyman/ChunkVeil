# Strict confidentiality preset

Use this posture when preventing underground disclosure matters more than visual smoothness or player visibility trade-offs.

```yaml
security:
  stop-server-on-startup-failure: true
  runtime-trip-action: STOP_SERVER

worlds:
  world:
    enabled: true
    hide-below-y: 0 # Keep this aligned to a 16-block boundary.
    min-y: -64
    default-fake-block: DEEPSLATE
    hide-air: true
    hide-entities: true
    hide-players: true

packet-protection:
  cancel-explosions: true
  cancel-world-events: true
  cancel-block-crack: true
  cancel-positional-sounds: true
  cancel-particles: true
  cancel-vibrations: true
  sanitize-light: true
```

Apply the same world settings separately to Nether or End worlds you choose to protect, using a dimension-appropriate fake block. `hide-players: true` can affect PvP and moderation visibility, so test it before deploying the preset broadly.

The normal generated configuration is the recommended performance-safe preset: all packet protections and underground entity hiding are enabled, while `hide-air` and `hide-players` remain disabled. Existing configurations are upgraded additively on startup, so newly introduced options appear without replacing explicit values or custom worlds.
