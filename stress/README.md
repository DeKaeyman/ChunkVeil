# ChunkVeil stress test harness

Local Paper and Folia test servers with ChunkVeil, filled with fake players,
to see how the server holds up and which part of ChunkVeil costs the most
per tick.

Test servers only: the driver plugin teleports bots, breaks blocks and
spawns mobs, and the servers run with movement checks and packet limits off.

## What is in here

| Path | What it is |
|---|---|
| `stress.py` | The harness: `setup`, `run`, `analyze`, `start`, `rcon` |
| `bots/` | Bot client (Java, [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)). Real network clients, so every packet goes through ProtocolLib and ChunkVeil like for a real player |
| `driver/` | Server plugin `ChunkVeilStressDriver`: places and moves bots, mining, mobs, pre-generation, `stress stats` |
| `tools/JfrHotspots.java` | Turns the server's JFR recording into a ChunkVeil hotspot list |
| `servers/`, `cache/`, `results/` | Created by the harness (git-ignored) |

## Requirements

- Python 3.10+ (stdlib only)
- Java 25 for the servers and bots (26.x needs it), Java 21 for Gradle
  (found automatically in the default install folders; override with
  `STRESS_JAVA25` / `STRESS_JAVA21`)
- About 16 GB free RAM (10 GB server heap + 4 GB bots by default)

## 1. Set up a server

```
python stress/stress.py setup --platform paper
python stress/stress.py setup --platform folia
```

Default version is 26.2 (`--version` for another). Not 26.3 yet: with the
current ProtocolLib dev build (2026-10-06) ProtocolLib's light-data wrapper
fails to initialise on 26.3, so ChunkVeil drops concealed chunks and
quarantines (fail-closed, as designed). Retest with `--version 26.3` after a
new ProtocolLib build. Setup:

1. downloads the newest stable Paper/Folia build for that version (falls back
   to the newest build when there is no stable one, e.g. Folia alphas) and
   checks its SHA-256,
2. downloads the newest ProtocolLib dev build (the line that supports 26.x)
   and checks its SHA-256,
3. builds ChunkVeil from the working tree, the driver plugin and the bot
   client (MCProtocolLib version matching the Minecraft version),
4. writes the server config: offline mode, RCON, fixed seed, view distance
   10, no spawn protection, no pause when empty,
5. turns off what fights bots: Spigot movement checks
   (`moved-wrongly-threshold`, `moved-too-quickly-multiplier`), Paper's
   packet limiter and spam limiter, the join throttle, bStats and the
   ChunkVeil update checker. Paper's own anti-xray stays off so only
   ChunkVeil is measured,
6. pre-generates a 960-block radius (so world generation does not distort
   the numbers) and saves a pristine copy of the world. Every run starts
   from that copy.

Ports: Paper 25600 (RCON 25610), Folia 25601 (RCON 25611).

## 2. Run a test

```
python stress/stress.py run --platform paper --bots 100 --duration 600
python stress/stress.py run --platform folia --bots 200 --ramp 180 --duration 900
```

Phases:

1. **baseline** (30 s): the server with nobody online,
2. **ramp**: bots join evenly over `--ramp` seconds,
3. **steady** (`--duration`): mobs are spawned around cave bots, cave bots
   start mining, and this is the phase the report is about.

Bot roles (`--mix surface=50,cave=40,idle=10`):

- **surface**: sprint-speed flight over the terrain in long lines, loading new
  chunks the whole time (chunk packets, chunk rewrite),
- **cave**: teleported into underground air below the cutoff every 45 s,
  turning their head constantly (reveal scans, reveal queue, chunk resends,
  entity hiding, block changes when mining),
- **idle**: standing still like AFK players.

Folia only runs regions in parallel when players are far enough apart that
their loaded areas do not touch. With the default 700-block radius most bots
share one region. To test parallel regions, set up a bigger pre-generated
area and run with a bigger radius:

```
python stress/stress.py setup --platform folia --bot-radius 2500 --pregen-radius 2700 --skip-build
python stress/stress.py run --platform folia --bots 200 --radius 2500
```

The vanilla locator bar is turned off for runs (`gamerule locator_bar
false`): it updates a link between every pair of players on every move, and
with 100 moving bots it was the largest cost on the whole server. Use
`--locator-bar` to keep it on. Real servers with many players benefit from
turning it off too.

Other options: `--locator-bar`, `--radius`, `--mobs` (extra mobs per cave bot, default 6), `--mine`
(blocks broken per second per cave bot, default 2), `--view-distance`,
`--memory`, `--profile jfr|spark|none`, `--label`.

While it runs, a live line every 5 s shows players, TPS, MSPT and the most
expensive ChunkVeil parts in ms per tick. Ctrl+C stops the bots and the
server cleanly and still writes the report.

## 3. Read the report

Each run gets a folder in `stress/results/` with:

| File | Contents |
|---|---|
| `report.md` | The summary below, also printed at the end |
| `summary.json` | Same numbers, machine-readable |
| `samples.jsonl` | Raw samples every 5 s (`stress stats` + `chunkveil metrics` + bot status) |
| `server.jfr` | Java Flight Recorder recording of the whole run (open in JDK Mission Control for full detail) |
| `jfr-hotspots.json` | CPU sample breakdown for the steady phase |
| `gc.log`, `server-console.log`, `bots.log` | Logs |

The report has three parts:

1. **Server load per phase:** TPS, MSPT mean/p95/max, CPU cores used, GC
   time, heap. On Folia the MSPT columns are per region (mean and worst).
2. **ChunkVeil cost:** ChunkVeil's own timers, as ms per tick, calls per
   second and µs per call. They cover the worker (reveal scan, reveal queue,
   entity scan) and every packet path in the listener (`/chunkveil metrics`).
   On Paper compare the total with MSPT; on Folia it is summed over all
   threads, so compare it with the worst region.
3. **CPU samples (JFR):** which share of tick-thread and network-thread CPU
   samples contain ChunkVeil code, the ChunkVeil entry points (which feature),
   the hottest ChunkVeil methods, and the leaf methods where the CPU actually
   goes. "ProtocolLib only" counts samples in ProtocolLib outside ChunkVeil
   (the cost of having a listener at all).

Re-run the analysis of an old run with
`python stress/stress.py analyze stress/results/<folder>`.

## Other commands

```
python stress/stress.py start --platform paper --restore   # server only, join with a 26.x client
python stress/stress.py rcon --platform paper chunkveil metrics
python stress/stress.py rcon --platform folia stress stats
```

Driver commands (console/RCON): `stress stats`, `stress spread`,
`stress mine <n>`, `stress mobs <n>`, `stress pregen <radius>`,
`stress pregen status`, `stress reload`.

## Notes and limits

- Bots do not run physics: surface bots fly (creative) over the heightmaps
  the server sends, cave bots stand where the driver puts them. Movement checks
  are off, so the server accepts it. What matters for ChunkVeil (chunk sends,
  head turns, entities, block changes) is real.
- Bot client and server share the CPU when run on the same machine. For big
  bot counts, run the bots on a second machine (`java -jar
  stress/bots/build/libs/chunkveil-stress-bots-26.2.jar --host <server> ...`).
- Folia 26.3 is an alpha build at the time of writing.
- Bots send at most one position packet per server tick: since 26.3 the
  server kicks a client that sends two ("Invalid move player packet").
