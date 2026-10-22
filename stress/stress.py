#!/usr/bin/env python3
"""
ChunkVeil stress test harness (Paper + Folia). Python 3.10+, stdlib only.

  python stress/stress.py setup   --platform paper            # download, build, configure, pre-generate
  python stress/stress.py run     --platform paper --bots 100 --duration 600
  python stress/stress.py analyze stress/results/<run-dir>    # re-run the analysis of a finished run
  python stress/stress.py start   --platform folia            # just start the server (for manual play)
  python stress/stress.py rcon    --platform paper "chunkveil metrics"

See stress/README.md for what each step does and how to read the report.
"""
import argparse
import datetime as dt
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import Rcon, RconError  # noqa: E402

STRESS_DIR = Path(__file__).resolve().parent
REPO_DIR = STRESS_DIR.parent
CACHE_DIR = STRESS_DIR / "cache"
SERVERS_DIR = STRESS_DIR / "servers"
RESULTS_DIR = STRESS_DIR / "results"
USER_AGENT = "ChunkVeil-stress-harness (+https://github.com/DeKaeyman/ChunkVeil)"
RCON_PASSWORD = "chunkveil-stress"
LEVEL_SEED = "8675309"

PLATFORMS = {
    "paper": {"server_port": 25600, "rcon_port": 25610},
    "folia": {"server_port": 25601, "rcon_port": 25611},
}

# MCProtocolLib release matching each Minecraft version (bot client protocol).
MCPL_FOR_VERSION = {"26.3": "26.3", "26.2": "26.2", "26.1.2": "26.1-1", "26.1.1": "26.1-1", "1.21.11": "1.21.11-1"}

AIKAR_FLAGS = [
    "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
    "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
    "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
    "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
    "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
    "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem",
    "-XX:MaxTenuringThreshold=1", "-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true",
]


# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------

def log(message):
    print(f"[{dt.datetime.now():%H:%M:%S}] {message}", flush=True)


def find_java(major):
    """Java executable of a given major version (Adoptium/Oracle default locations), or None."""
    env = os.environ.get(f"STRESS_JAVA{major}")
    if env:
        return env
    patterns = [
        f"C:/Program Files/Eclipse Adoptium/jdk-{major}*/bin/java.exe",
        f"C:/Program Files/Java/jdk-{major}*/bin/java.exe",
        f"/usr/lib/jvm/*{major}*/bin/java",
        f"/Library/Java/JavaVirtualMachines/*{major}*/Contents/Home/bin/java",
    ]
    for pattern in patterns:
        matches = sorted(glob.glob(pattern))
        if matches:
            return matches[-1]
    return None


def server_java():
    java = find_java(25)
    if not java:
        sys.exit("Java 25 is required for Minecraft 26.x servers (set STRESS_JAVA25 to its java executable).")
    return java


def gradle_env():
    env = dict(os.environ)
    java21 = find_java(21)
    if java21:
        env["JAVA_HOME"] = str(Path(java21).parent.parent)
    return env


def http_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url, target, sha256=None):
    target = Path(target)
    if target.exists() and (sha256 is None or sha256_of(target) == sha256):
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    log(f"Downloading {url}")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    tmp = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(request, timeout=300) as response, open(tmp, "wb") as out:
        shutil.copyfileobj(response, out)
    if sha256 is not None:
        actual = sha256_of(tmp)
        if actual != sha256:
            tmp.unlink()
            raise SystemExit(f"Checksum mismatch for {url}: expected {sha256}, got {actual}")
    tmp.replace(target)
    return target


def server_dir(platform, version):
    return SERVERS_DIR / f"{platform}-{version}"


def read_setup(platform, version):
    path = server_dir(platform, version) / "stress-setup.json"
    if not path.exists():
        sys.exit(f"{path.parent} is not set up yet: run  python stress/stress.py setup --platform {platform} --version {version}")
    return json.loads(path.read_text(encoding="utf-8"))


def yaml_set(path, dotted_key, value):
    """Sets a scalar in a block-style YAML file (Paper/Spigot configs), keeping everything else.

    Creates missing keys (2-space indentation). Good enough for the generated
    server configs; not a general YAML writer.
    """
    path = Path(path)
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    keys = dotted_key.split(".")
    start, end, indent = 0, len(lines), 0
    for depth, key in enumerate(keys):
        found = None
        pattern = re.compile(r"^(\s*)(['\"]?)" + re.escape(key) + r"\2\s*:(.*)$")
        for i in range(start, end):
            match = pattern.match(lines[i])
            if match and len(match.group(1)) == indent:
                found = i
                break
        last = depth == len(keys) - 1
        if found is None:
            insert_at = end
            new_lines = []
            for d in range(depth, len(keys)):
                prefix = " " * (indent + 2 * (d - depth))
                if d == len(keys) - 1:
                    new_lines.append(f"{prefix}{keys[d]}: {value}")
                else:
                    new_lines.append(f"{prefix}{keys[d]}:")
            lines[insert_at:insert_at] = new_lines
            break
        if last:
            comment = ""
            lines[found] = f"{' ' * indent}{key}: {value}{comment}"
            break
        # Narrow to the child block of this key.
        child_start = found + 1
        child_end = child_start
        while child_end < len(lines):
            line = lines[child_end]
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and (len(line) - len(line.lstrip())) <= indent:
                break
            child_end += 1
        start, end, indent = child_start, child_end, indent + 2
        # Paper uses 2-space indentation; detect the real child indent if present.
        for i in range(child_start, child_end):
            stripped = lines[i].strip()
            if stripped and not stripped.startswith("#"):
                indent = len(lines[i]) - len(lines[i].lstrip())
                break
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_gradle(args, cwd=REPO_DIR):
    wrapper = REPO_DIR / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper)] + args
    log("gradle " + " ".join(args))
    subprocess.run(command, cwd=cwd, env=gradle_env(), check=True)


# ----------------------------------------------------------------------------
# Server process
# ----------------------------------------------------------------------------

class Server:
    def __init__(self, platform, version, memory="10G", jfr_path=None, gc_log=None, log_path=None, extra_jvm_args=None):
        self.platform = platform
        self.version = version
        self.dir = server_dir(platform, version)
        self.ports = PLATFORMS[platform]
        self.memory = memory
        self.jfr_path = jfr_path
        self.gc_log = gc_log
        self.log_path = Path(log_path) if log_path else self.dir / "logs" / "stress-console.log"
        self.process = None
        self.rcon = None
        self.extra_jvm_args = list(extra_jvm_args or [])

    def start(self):
        args = [server_java(), f"-Xms{self.memory}", f"-Xmx{self.memory}"] + AIKAR_FLAGS
        if self.gc_log:
            args.append(f"-Xlog:gc*:file={Path(self.gc_log).as_posix()}:time,uptime:filecount=1,filesize=100M")
        if self.jfr_path:
            # 'profile' samples Java stacks every 10 ms (~1-2% overhead).
            args.append(f"-XX:StartFlightRecording=filename={Path(self.jfr_path).as_posix()},settings=profile,dumponexit=true")
        args += self.extra_jvm_args
        args += ["-jar", "server.jar", "--nogui"]
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        log(f"Starting {self.platform} {self.version} on port {self.ports['server_port']} ({self.memory} heap)")
        creation = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        self.console = open(self.log_path, "w", encoding="utf-8", errors="replace")
        self.process = subprocess.Popen(args, cwd=self.dir, stdin=subprocess.PIPE, stdout=self.console,
                                        stderr=subprocess.STDOUT, creationflags=creation)
        return self

    def wait_ready(self, timeout=300):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.process.poll() is not None:
                raise SystemExit(f"Server exited during startup (code {self.process.returncode}); see {self.log_path}")
            try:
                self.rcon = Rcon("127.0.0.1", self.ports["rcon_port"], RCON_PASSWORD, timeout=30).connect()
                self.rcon.command("list")
                log("Server is ready (RCON connected)")
                return self
            except (OSError, RconError):
                time.sleep(2)
        raise SystemExit(f"Server not ready after {timeout}s; see {self.log_path}")

    def command(self, text):
        try:
            return self.rcon.command(text)
        except (OSError, RconError):
            # Reconnect once (the server may have dropped an idle connection).
            self.rcon = Rcon("127.0.0.1", self.ports["rcon_port"], RCON_PASSWORD, timeout=30).connect()
            return self.rcon.command(text)

    def stop(self, timeout=180):
        if self.process is None or self.process.poll() is not None:
            return
        log("Stopping server")
        try:
            self.command("stop")
        except Exception:  # noqa: BLE001 - fall back to stdin
            try:
                self.process.stdin.write(b"stop\n")
                self.process.stdin.flush()
            except Exception:  # noqa: BLE001
                pass
        try:
            self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            log("Server did not stop in time, killing it")
            self.process.kill()
            self.process.wait()
        if self.rcon:
            self.rcon.close()
        self.console.close()


# ----------------------------------------------------------------------------
# setup
# ----------------------------------------------------------------------------

def resolve_papermc_build(project, version):
    builds = http_json(f"https://fill.papermc.io/v3/projects/{project}/versions/{version}/builds")
    if isinstance(builds, dict):
        builds = builds.get("builds", [])
    if not builds:
        raise SystemExit(f"No {project} builds for {version}")
    stable = [b for b in builds if b.get("channel") == "STABLE"]
    build = (stable or builds)[0]
    download_info = build["downloads"]["server:default"]
    return {
        "build": build["id"], "channel": build.get("channel"), "time": build.get("time"),
        "url": download_info["url"], "sha256": download_info["checksums"]["sha256"],
    }


def resolve_protocollib():
    release = http_json("https://api.github.com/repos/dmulloy2/ProtocolLib/releases/tags/dev-build")
    for asset in release.get("assets", []):
        if asset["name"] == "ProtocolLib.jar":
            digest = asset.get("digest") or ""
            return {
                "url": asset["browser_download_url"], "updated": asset["updated_at"],
                "sha256": digest.split(":", 1)[1] if digest.startswith("sha256:") else None,
            }
    raise SystemExit("ProtocolLib dev-build has no ProtocolLib.jar asset")


def cmd_setup(args):
    platform, version = args.platform, args.version
    directory = server_dir(platform, version)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "plugins").mkdir(exist_ok=True)

    # 1. Server jar (latest stable build, else latest), verified by checksum.
    build = resolve_papermc_build(platform, version)
    jar = download(build["url"], CACHE_DIR / f"{platform}-{version}-{build['build']}.jar", build["sha256"])
    shutil.copy2(jar, directory / "server.jar")
    log(f"{platform} {version} build {build['build']} ({build['channel']})")

    # 2. Latest ProtocolLib dev build (the only line supporting 1.21.9+ / 26.x).
    protocollib = resolve_protocollib()
    stamp = protocollib["updated"][:10]
    pl_jar = download(protocollib["url"], CACHE_DIR / f"ProtocolLib-dev-{stamp}.jar", protocollib["sha256"])
    shutil.copy2(pl_jar, directory / "plugins" / "ProtocolLib.jar")
    log(f"ProtocolLib dev build of {protocollib['updated']}")

    # 3. ChunkVeil (current working tree), the driver plugin and the bot client.
    mcpl = args.mcpl or MCPL_FOR_VERSION.get(version)
    if not mcpl:
        raise SystemExit(f"No MCProtocolLib version known for {version}; pass --mcpl")
    if not args.skip_build:
        run_gradle(["shadowJar"])
        run_gradle(["-p", "stress/driver", "jar"])
        run_gradle(["-p", "stress/bots", "shadowJar", f"-PmcplVersion={mcpl}"])
    chunkveil = sorted((REPO_DIR / "build" / "libs").glob("ChunkVeil-*.jar"), key=lambda p: p.stat().st_mtime)[-1]
    shutil.copy2(chunkveil, directory / "plugins" / "ChunkVeil.jar")
    shutil.copy2(STRESS_DIR / "driver" / "build" / "libs" / "ChunkVeilStressDriver.jar",
                 directory / "plugins" / "ChunkVeilStressDriver.jar")
    bots_jar = STRESS_DIR / "bots" / "build" / "libs" / f"chunkveil-stress-bots-{mcpl}.jar"
    if not bots_jar.exists():
        raise SystemExit(f"Bot client jar missing: {bots_jar}")

    # 4. Config before the first boot.
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    write_server_properties(directory, platform, args)
    bstats = directory / "plugins" / "bStats"
    bstats.mkdir(exist_ok=True)
    (bstats / "config.yml").write_text("enabled: false\nlogFailedRequests: false\n", encoding="utf-8")

    setup_info = {
        "platform": platform, "version": version, "serverBuild": build, "protocolLib": protocollib,
        "chunkveilJar": chunkveil.name, "mcpl": mcpl, "botsJar": str(bots_jar.relative_to(REPO_DIR)),
        "pregenRadius": args.pregen_radius, "botRadius": args.bot_radius,
        "createdAt": dt.datetime.now().isoformat(timespec="seconds"),
    }
    (directory / "stress-setup.json").write_text(json.dumps(setup_info, indent=2), encoding="utf-8")

    # 5. First boot generates every config file; then patch them.
    if not (directory / "config" / "paper-global.yml").exists() or args.reconfigure:
        server = Server(platform, version, memory=args.memory).start()
        try:
            server.wait_ready()
        finally:
            server.stop()
    patch_configs(directory, args)

    # 6. Pre-generate the test area and keep a pristine copy of the world.
    if args.pregen_radius > 0:
        pregenerate(platform, version, args.pregen_radius, args.memory)
    log(f"Setup done: {directory}")


def write_server_properties(directory, platform, args):
    ports = PLATFORMS[platform]
    properties = {
        "server-port": ports["server_port"],
        "enable-rcon": "true",
        "rcon.port": ports["rcon_port"],
        "rcon.password": RCON_PASSWORD,
        "broadcast-rcon-to-ops": "false",
        "online-mode": "false",
        "enforce-secure-profile": "false",
        "prevent-proxy-connections": "false",
        "max-players": 2000,
        "view-distance": args.view_distance,
        "simulation-distance": args.simulation_distance,
        "spawn-protection": 0,
        "level-seed": LEVEL_SEED,
        "level-name": "world",
        "allow-flight": "true",
        "player-idle-timeout": 0,
        "pause-when-empty-seconds": -1,
        "difficulty": "normal",
        "network-compression-threshold": 256,
        "sync-chunk-writes": "false",
        "motd": f"ChunkVeil stress ({platform})",
        "white-list": "false",
        "enable-command-block": "false",
    }
    path = directory / "server.properties"
    existing = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                existing[key] = value
    existing.update({k: str(v) for k, v in properties.items()})
    path.write_text("\n".join(f"{k}={v}" for k, v in existing.items()) + "\n", encoding="utf-8")


def patch_configs(directory, args):
    """Turns off everything that would interfere with bots (anti-cheat, rate limits)."""
    log("Patching server configs for bots (movement checks, packet limiter, join limits)")
    bukkit = directory / "bukkit.yml"
    yaml_set(bukkit, "settings.connection-throttle", "-1")
    spigot = directory / "spigot.yml"
    yaml_set(spigot, "settings.moved-wrongly-threshold", "1000.0")
    yaml_set(spigot, "settings.moved-too-quickly-multiplier", "1000.0")
    yaml_set(spigot, "settings.timeout-time", "120")
    yaml_set(spigot, "settings.restart-on-crash", "false")
    yaml_set(spigot, "stats.disable-saving", "true")
    paper_global = directory / "config" / "paper-global.yml"
    yaml_set(paper_global, "packet-limiter.all-packets.max-packet-rate", "1000000.0")
    yaml_set(paper_global, "spam-limiter.incoming-packet-threshold", "1000000")
    yaml_set(paper_global, "misc.max-joins-per-tick", "200")
    paper_world = directory / "config" / "paper-world-defaults.yml"
    # Paper's own anti-xray stays off: we measure ChunkVeil alone.
    yaml_set(paper_world, "anticheat.anti-xray.enabled", "false")
    chunkveil = directory / "plugins" / "ChunkVeil" / "config.yml"
    if chunkveil.exists():
        yaml_set(chunkveil, "update-checker.enabled", "false")
        yaml_set(chunkveil, "metrics.enabled", "false")
    driver = directory / "plugins" / "ChunkVeilStressDriver" / "config.yml"
    if driver.exists():
        yaml_set(driver, "radius", str(args.bot_radius))
    write_server_properties(directory, directory.name.split("-")[0], args)


def pregenerate(platform, version, radius, memory):
    directory = server_dir(platform, version)
    server = Server(platform, version, memory=memory).start()
    try:
        server.wait_ready()
        log(server.command(f"stress pregen {radius}"))
        while True:
            time.sleep(10)
            status = server.command("stress pregen status")
            log(status)
            if "running=false" in status:
                break
        if platform == "paper":
            log(server.command("save-all flush"))  # Folia has no save-all; its shutdown saves
    finally:
        server.stop()
    template = directory / "world-template"
    if template.exists():
        shutil.rmtree(template)
    template.mkdir()
    for name in ("world", "world_nether", "world_the_end"):
        source = directory / name
        if source.exists():
            shutil.copytree(source, template / name)
    log(f"World template saved to {template}")


def restore_world(directory):
    template = directory / "world-template"
    if not template.exists():
        log("No world template (setup without pre-generation): using the current world")
        return
    for source in template.iterdir():
        target = directory / source.name
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)
    log("World restored from template")


# ----------------------------------------------------------------------------
# run
# ----------------------------------------------------------------------------

def parse_chunkveil_metrics(text):
    """`/chunkveil metrics` output -> {'gauges':{}, 'counters':{}, 'timings':{name:(samples,totalNanos)}}."""
    result = {"gauges": {}, "counters": {}, "timings": {}}
    for line in text.splitlines():
        parts = line.strip().split()
        if not parts:
            continue
        pairs = dict(p.split("=", 1) for p in parts[1:] if "=" in p)
        if parts[0] == "chunkveil_metrics":
            result["gauges"] = pairs
        elif parts[0] == "counters":
            result["counters"] = {k: int(v) for k, v in pairs.items()}
        elif parts[0] == "timing":
            result["timings"][pairs["name"]] = (int(pairs["samples"]), int(pairs["totalNanos"]))
    return result


class Poller(threading.Thread):
    """Samples server stats + ChunkVeil metrics every interval into samples.jsonl."""

    def __init__(self, server, out_path, bots_status, interval):
        super().__init__(daemon=True)
        self.server = server
        self.out = open(out_path, "a", encoding="utf-8")
        self.bots_status = bots_status
        self.interval = interval
        self.phase = "baseline"
        self.stop_event = threading.Event()
        self.previous = None

    def run(self):
        while not self.stop_event.is_set():
            started = time.time()
            try:
                self.sample()
            except Exception as error:  # noqa: BLE001 - keep polling
                log(f"poll failed: {error}")
            self.stop_event.wait(max(0.5, self.interval - (time.time() - started)))
        self.out.close()

    def sample(self):
        now = time.time()
        stats = json.loads(self.server.command("stress stats"))
        metrics = parse_chunkveil_metrics(self.server.command("chunkveil metrics"))
        metrics.setdefault("timings", {})
        bots = {}
        if self.bots_status and Path(self.bots_status).exists():
            try:
                bots = json.loads(Path(self.bots_status).read_text(encoding="utf-8"))
            except ValueError:
                bots = {}
        record = {"t": now, "phase": self.phase, "stats": stats, "chunkveil": metrics, "bots": bots}
        self.out.write(json.dumps(record) + "\n")
        self.out.flush()
        self.print_live(record)
        self.previous = record

    def print_live(self, record):
        stats = record["stats"]
        line = f"{self.phase:<9} players {stats.get('players', 0):>4}  TPS {stats.get('tps', 0):5.2f}  MSPT {stats.get('mspt', 0):6.2f}"
        if "worstRegionMspt" in stats:
            line += f" (worst region {stats['worstRegionMspt']:.1f}, {stats.get('regions', 0)} regions)"
        if self.previous:
            seconds = record["t"] - self.previous["t"]
            costs = []
            tps = max(0.1, min(20.0, record["stats"].get("tps", 20.0)))
            for name, (samples, nanos) in record["chunkveil"]["timings"].items():
                before = self.previous["chunkveil"]["timings"].get(name, (0, 0))
                ms_per_tick = (nanos - before[1]) / 1e6 / seconds / tps
                if ms_per_tick > 0.005:
                    costs.append((ms_per_tick, name))
            costs.sort(reverse=True)
            top = ", ".join(f"{n} {v:.2f}" for v, n in costs[:4])
            line += f"  | ChunkVeil ms/tick: {top or '-'}"
        log(line)


def cmd_run(args):
    platform, version = args.platform, args.version
    setup = read_setup(platform, version)
    directory = server_dir(platform, version)
    label = args.label or f"{args.bots}bots"
    run_dir = RESULTS_DIR / f"{dt.datetime.now():%Y%m%d-%H%M%S}-{platform}-{version}-{label}"
    run_dir.mkdir(parents=True)
    restore_world(directory)
    radius = args.radius or setup.get("botRadius", 700)
    if radius + 16 * args.view_distance > setup.get("pregenRadius", 0):
        log(f"Warning: bot radius {radius} + view distance reaches past the pre-generated "
            f"{setup.get('pregenRadius')} blocks; world generation will be part of the measurement.")
    driver_config = directory / "plugins" / "ChunkVeilStressDriver" / "config.yml"
    if driver_config.exists():
        yaml_set(driver_config, "radius", str(radius))

    config = {
        "platform": platform, "version": version, "bots": args.bots, "mix": args.mix,
        "rampSeconds": args.ramp, "durationSeconds": args.duration, "baselineSeconds": args.baseline,
        "mobsPerCaveBot": args.mobs, "minePerSecond": args.mine, "viewDistance": args.view_distance,
        "memory": args.memory, "profile": args.profile, "interval": args.interval, "setup": setup,
        "radius": args.radius or setup.get("botRadius", 700),
        "withoutChunkVeil": args.without_chunkveil,
        "locatorBar": args.locator_bar,
        "jvmArgs": args.jvm_arg,
        "startedAt": dt.datetime.now().isoformat(timespec="seconds"),
    }
    (run_dir / "run-config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")

    plugin_jar = directory / "plugins" / "ChunkVeil.jar"
    parked_jar = directory / "ChunkVeil.jar.parked"
    if args.build:
        run_gradle(["shadowJar"])
    # Always test the newest local build, not the jar copied at setup time.
    built = sorted((REPO_DIR / "build" / "libs").glob("ChunkVeil-*.jar"), key=lambda path: path.stat().st_mtime)
    if built and not args.without_chunkveil:
        shutil.copy2(built[-1], plugin_jar)
        config["chunkveilJar"] = f"{built[-1].name} ({dt.datetime.fromtimestamp(built[-1].stat().st_mtime):%Y-%m-%d %H:%M})"
        (run_dir / "run-config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
        log(f"Testing {config['chunkveilJar']}")
    if parked_jar.exists() and not plugin_jar.exists():
        parked_jar.replace(plugin_jar)  # left over from an interrupted run
    if args.without_chunkveil and plugin_jar.exists():
        plugin_jar.replace(parked_jar)
        log("ChunkVeil removed for this run (comparison baseline)")
    jfr = run_dir / "server.jfr" if args.profile == "jfr" else None
    server = Server(platform, version, memory=args.memory, jfr_path=jfr, gc_log=run_dir / "gc.log",
                    log_path=run_dir / "server-console.log", extra_jvm_args=args.jvm_arg).start()
    bots_process = None
    poller = None
    phases = {}
    try:
        server.wait_ready()
        log(server.command("stress reload"))
        if not args.locator_bar:
            # The vanilla locator bar (1.21.6+) updates waypoint links between
            # every pair of players on every move: with 100 moving bots it was
            # the top server cost and dragged everything else down. Off by
            # default so runs measure ChunkVeil; --locator-bar keeps it.
            for rule in ("locator_bar", "locatorBar"):
                reply = server.command(f"gamerule {rule} false")
                if "unknown" not in reply.lower() and "incorrect" not in reply.lower():
                    log(f"Locator bar off ({rule}): {reply.strip()}")
                    break
        bots_status = run_dir / "bots-status.json"
        poller = Poller(server, run_dir / "samples.jsonl", bots_status, args.interval)
        poller.start()
        phases["baseline"] = [time.time(), None]
        time.sleep(args.baseline)
        phases["baseline"][1] = time.time()

        # Ramp: bots join evenly over the ramp period.
        java = server_java()
        join_interval = max(20, int(args.ramp * 1000 / max(1, args.bots)))
        bot_args = [java, "-Xmx4G", "-jar", str(REPO_DIR / setup["botsJar"]),
                    "--port", str(PLATFORMS[platform]["server_port"]), "--count", str(args.bots),
                    "--join-interval-ms", str(join_interval), "--mix", args.mix,
                    "--view-distance", str(args.view_distance), "--radius", str(radius),
                    "--status-file", str(bots_status)]
        log(f"Starting {args.bots} bots ({args.mix}), one every {join_interval} ms")
        poller.phase = "ramp"
        phases["ramp"] = [time.time(), None]
        bots_log = open(run_dir / "bots.log", "w", encoding="utf-8", errors="replace")
        creation = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        bots_process = subprocess.Popen(bot_args, cwd=run_dir, stdout=bots_log, stderr=subprocess.STDOUT,
                                        creationflags=creation)
        time.sleep(args.ramp + 15)
        phases["ramp"][1] = time.time()

        if args.mobs > 0:
            log(server.command(f"stress mobs {args.mobs}"))
        if args.mine > 0:
            log(server.command(f"stress mine {args.mine}"))
        if args.profile == "spark":
            log(server.command("spark profiler start --thread * --timeout " + str(args.duration)))
        poller.phase = "steady"
        phases["steady"] = [time.time(), None]
        time.sleep(args.duration)
        phases["steady"][1] = time.time()
        if args.profile == "spark":
            log(server.command("spark profiler stop --save-to-file"))
    except KeyboardInterrupt:
        log("Interrupted: stopping bots and server")
    finally:
        if poller:
            poller.stop_event.set()
            poller.join(timeout=30)
        if bots_process and bots_process.poll() is None:
            log("Stopping bots")
            bots_process.terminate()
            try:
                bots_process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                bots_process.kill()
        server.stop()
        if parked_jar.exists():
            parked_jar.replace(plugin_jar)
        (run_dir / "phases.json").write_text(json.dumps(phases, indent=2), encoding="utf-8")
    analyze(run_dir)


# ----------------------------------------------------------------------------
# analyze
# ----------------------------------------------------------------------------

def percentile(values, fraction):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def mean(values):
    return sum(values) / len(values) if values else 0.0


TIMING_LABELS = {
    "reveal_scan": "Reveal ray scan (worker)",
    "queue": "Chunk reveal queue + resend (worker)",
    "entity_scan": "Entity hide/reveal scan (worker)",
    "chunk_rewrite": "Chunk section rewrite (inside packet_chunk)",
    "packet_chunk": "Packet: chunk (deep clone + rewrite + light)",
    "packet_entity_spawn": "Packet: entity spawn",
    "packet_entity_follow_up": "Packet: entity follow-ups",
    "packet_block_change": "Packet: block change",
    "packet_multi_block_change": "Packet: multi-block change",
    "packet_block_entity": "Packet: block entity",
    "packet_light": "Packet: light update",
    "packet_explosion": "Packet: explosion",
    "packet_world_event": "Packet: world event",
    "packet_block_crack": "Packet: block crack",
    "packet_positional_sound": "Packet: positional sound",
    "packet_particle": "Packet: particle",
    "packet_vibration": "Packet: vibration",
}
NESTED = {"chunk_rewrite"}


def analyze(run_dir):
    run_dir = Path(run_dir)
    records = [json.loads(line) for line in (run_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    config = json.loads((run_dir / "run-config.json").read_text(encoding="utf-8"))
    phases = json.loads((run_dir / "phases.json").read_text(encoding="utf-8")) if (run_dir / "phases.json").exists() else {}
    if len(records) < 2:
        log("Not enough samples to analyze")
        return

    summary = {"config": config, "phases": {}}
    for phase in ("baseline", "ramp", "steady"):
        rows = [r for r in records if r["phase"] == phase]
        if len(rows) < 2:
            continue
        first, last = rows[0], rows[-1]
        seconds = last["t"] - first["t"]
        stats = [r["stats"] for r in rows]
        phase_summary = {
            "seconds": round(seconds, 1),
            "players": max(s.get("players", 0) for s in stats),
            "tpsMean": round(mean([s.get("tps", 20) for s in stats]), 2),
            "tpsMin": round(min(s.get("minRegionTps", s.get("tps", 20)) for s in stats), 2),
            "msptMean": round(mean([s.get("mspt", 0) for s in stats]), 2),
            "msptP95": round(percentile([s.get("msptP95", s.get("worstRegionMspt", s.get("mspt", 0))) for s in stats], 0.95), 2),
            "msptMax": round(max(s.get("msptMax", s.get("worstRegionMspt", 0)) for s in stats), 2),
            "heapUsedMbMax": max(s.get("heapUsedMb", 0) for s in stats),
            "gcMillisPerSecond": round((last["stats"].get("gcMillis", 0) - first["stats"].get("gcMillis", 0)) / max(seconds, 1), 2),
            "cpuCoresUsed": round((last["stats"].get("processCpuNanos", 0) - first["stats"].get("processCpuNanos", 0)) / 1e9 / max(seconds, 1), 2),
        }
        if config["platform"] == "folia":
            phase_summary["regionsMax"] = max(s.get("regions", 0) for s in stats)
            phase_summary["worstRegionMsptMean"] = round(mean([s.get("worstRegionMspt", 0) for s in stats]), 2)
        # ChunkVeil cost from cumulative timings.
        timings = {}
        total_ms_per_second = 0.0
        # Per tick at the TPS actually reached: at 7 TPS a tick gets 1/7 of
        # each second's work, not 1/20.
        ticks_per_second = max(0.1, min(20.0, phase_summary["tpsMean"]))
        for name, (samples, nanos) in last["chunkveil"].get("timings", {}).items():
            before = first["chunkveil"].get("timings", {}).get(name, (0, 0))
            calls = samples - before[0]
            ms_per_second = (nanos - before[1]) / 1e6 / max(seconds, 1)
            timings[name] = {
                "msPerTick": round(ms_per_second / ticks_per_second, 4),
                "msPerSecond": round(ms_per_second, 3),
                "callsPerSecond": round(calls / max(seconds, 1), 1),
                "microsPerCall": round((nanos - before[1]) / 1e3 / calls, 2) if calls else 0.0,
            }
            if name not in NESTED:
                total_ms_per_second += ms_per_second
        total_ms_per_tick = total_ms_per_second / ticks_per_second
        counters = {}
        for name, value in last["chunkveil"].get("counters", {}).items():
            counters[name] = round((value - first["chunkveil"].get("counters", {}).get(name, 0)) / max(seconds, 1), 2)
        phase_summary["chunkveil"] = {
            "msPerTickTotal": round(total_ms_per_tick, 3),
            "msPerSecondTotal": round(total_ms_per_second, 1),
            # Share of one thread's time (1000 ms per second).
            "threadSharePercent": round(total_ms_per_second / 10.0, 1),
            "timings": dict(sorted(timings.items(), key=lambda kv: -kv[1]["msPerTick"])),
            "countersPerSecond": counters,
        }
        summary["phases"][phase] = phase_summary

    # JFR hotspots for the steady phase.
    jfr = run_dir / "server.jfr"
    if jfr.exists() and "steady" in phases:
        log("Analyzing JFR recording (steady phase)")
        start_ms, end_ms = int(phases["steady"][0] * 1000), int((phases["steady"][1] or time.time()) * 1000)
        result = subprocess.run([server_java(), str(STRESS_DIR / "tools" / "JfrHotspots.java"), str(jfr),
                                 str(start_ms), str(end_ms)], capture_output=True, text=True)
        if result.returncode == 0:
            (run_dir / "jfr-hotspots.json").write_text(result.stdout, encoding="utf-8")
            summary["jfr"] = json.loads(result.stdout)
        else:
            log("JFR analysis failed: " + result.stderr[-2000:])

    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    report = render_report(summary)
    (run_dir / "report.md").write_text(report, encoding="utf-8")
    print()
    print(report)
    log(f"Report: {run_dir / 'report.md'}")


def render_report(summary):
    config = summary["config"]
    out = [f"# ChunkVeil stress run: {config['platform']} {config['version']}, {config['bots']} bots", ""]
    setup = config.get("setup", {})
    out.append(f"- Server build {setup.get('serverBuild', {}).get('build')}, ProtocolLib dev of "
               f"{setup.get('protocolLib', {}).get('updated')}, {config.get('chunkveilJar', setup.get('chunkveilJar'))}")
    out.append(f"- Mix {config['mix']}, view distance {config['viewDistance']}, ramp {config['rampSeconds']} s, "
               f"steady {config['durationSeconds']} s, mobs/cave bot {config['mobsPerCaveBot']}, "
               f"mining {config['minePerSecond']}/s per cave bot, heap {config['memory']}")
    out.append("")
    out.append("## Server load per phase")
    out.append("")
    out.append("| Phase | Players | TPS mean | TPS min | MSPT mean | MSPT p95 | MSPT max | CPU cores | GC ms/s | Heap max MB |")
    out.append("|---|---|---|---|---|---|---|---|---|---|")
    for phase, p in summary["phases"].items():
        out.append(f"| {phase} | {p['players']} | {p['tpsMean']} | {p['tpsMin']} | {p['msptMean']} | {p['msptP95']} | "
                   f"{p['msptMax']} | {p['cpuCoresUsed']} | {p['gcMillisPerSecond']} | {p['heapUsedMbMax']} |")
    steady = summary["phases"].get("steady") or summary["phases"].get("ramp")
    if steady:
        cv = steady["chunkveil"]
        out.append("")
        out.append("## ChunkVeil cost (steady phase)")
        out.append("")
        if config.get("withoutChunkVeil"):
            out.append("ChunkVeil was **not installed** in this run (comparison baseline).")
        elif config["platform"] == "paper":
            out.append(f"Total measured: **{cv['msPerSecondTotal']} ms per second = {cv['threadSharePercent']}% of one "
                       f"thread**, {cv['msPerTickTotal']:.2f} ms per tick at the {steady['tpsMean']} TPS reached "
                       f"(mean MSPT {steady['msptMean']} ms). Nearly all of it runs on the main thread on Paper.")
        else:
            out.append(f"Total measured: **{cv['msPerSecondTotal']} ms per second summed over all region and network "
                       f"threads** ({cv['threadSharePercent']}% of one thread). Folia runs regions in parallel, so "
                       f"compare with the worst region MSPT ({steady.get('worstRegionMsptMean')} ms mean).")
        out.append("")
        out.append("| Component | ms / s | ms / tick | calls / s | µs / call |")
        out.append("|---|---|---|---|---|")
        for name, t in cv["timings"].items():
            if t["msPerTick"] < 0.0005 and t["callsPerSecond"] == 0:
                continue
            label = TIMING_LABELS.get(name, name)
            out.append(f"| {label} | {t['msPerSecond']:.1f} | {t['msPerTick']:.3f} | {t['callsPerSecond']} | {t['microsPerCall']} |")
        out.append("")
        out.append("Worker timings include everything they call: the reveal queue includes resending chunks, whose "
                   "packet handling is also counted under Packet: chunk. Chunk section rewrite is part of Packet: chunk "
                   "and is not added to the total.")
        out.append("")
        out.append("Per second: " + ", ".join(f"{k} {v}" for k, v in cv["countersPerSecond"].items() if v))
    jfr = summary.get("jfr")
    if jfr:
        out.append("")
        out.append("## CPU samples (JFR, steady phase)")
        out.append("")
        out.append("| Thread group | Samples | With ChunkVeil | % | ProtocolLib only |")
        out.append("|---|---|---|---|---|")
        for group, g in sorted(jfr["groups"].items(), key=lambda kv: -kv[1]["samples"]):
            pct = g["chunkveil"] / g["samples"] * 100 if g["samples"] else 0
            out.append(f"| {group} | {g['samples']} | {g['chunkveil']} | {pct:.1f}% | {g['protocolLibOutsideChunkVeil']} |")
        for key, title in (("chunkveilEntryPointsByThreadGroup", "ChunkVeil entry points (where the time starts)"),
                           ("chunkveilInnermostMethods", "Hottest ChunkVeil methods (inclusive)"),
                           ("leafMethodsInChunkveilSamples", "Where the CPU is inside ChunkVeil samples (leaf frames)"),
                           ("leafMethodsOnTickThreads", "Hottest leaf methods on tick threads (whole server, context)")):
            out.append("")
            out.append(f"### {title}")
            out.append("")
            for entry in jfr.get(key, [])[:15]:
                out.append(f"- {entry['samples']:>7}  `{entry['name']}`")
    out.append("")
    return "\n".join(out)


# ----------------------------------------------------------------------------
# small commands
# ----------------------------------------------------------------------------

def cmd_analyze(args):
    analyze(Path(args.run_dir))


def cmd_start(args):
    read_setup(args.platform, args.version)
    if args.restore:
        restore_world(server_dir(args.platform, args.version))
    server = Server(args.platform, args.version, memory=args.memory).start()
    log(f"Console log: {server.log_path}. Join with a 26.x client on localhost:{server.ports['server_port']} "
        "(offline mode). Press Ctrl+C to stop.")
    try:
        server.wait_ready()
        while server.process.poll() is None:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()


def cmd_rcon(args):
    ports = PLATFORMS[args.platform]
    with Rcon("127.0.0.1", ports["rcon_port"], RCON_PASSWORD) as rcon:
        print(rcon.command(" ".join(args.command)))


def main():
    parser = argparse.ArgumentParser(description="ChunkVeil stress test harness")
    sub = parser.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
        # 26.2 by default: on 26.3 the current ProtocolLib dev build fails to
        # initialise its light-data wrapper, so ChunkVeil (correctly) refuses
        # to send concealed chunks and quarantines. Use --version 26.3 to retest.
        p.add_argument("--version", default="26.2", help="Minecraft version (default 26.2)")
        p.add_argument("--memory", default="10G", help="server heap (default 10G)")

    p = sub.add_parser("setup", help="download, build, configure and pre-generate a test server")
    common(p)
    p.add_argument("--mcpl", help="MCProtocolLib version for the bots (default: matches --version)")
    p.add_argument("--view-distance", type=int, default=10)
    p.add_argument("--simulation-distance", type=int, default=8)
    p.add_argument("--bot-radius", type=int, default=700, help="bots stay within this distance of 0,0")
    p.add_argument("--pregen-radius", type=int, default=960, help="pre-generate this radius (0 = skip)")
    p.add_argument("--skip-build", action="store_true", help="reuse already built jars")
    p.add_argument("--reconfigure", action="store_true", help="boot once more to regenerate configs")
    p.set_defaults(func=cmd_setup)

    p = sub.add_parser("run", help="run a stress test and write a report")
    common(p)
    p.add_argument("--bots", type=int, default=100)
    p.add_argument("--mix", default="surface=50,cave=40,idle=10")
    p.add_argument("--ramp", type=int, default=120, help="seconds over which bots join")
    p.add_argument("--duration", type=int, default=600, help="steady phase seconds after the ramp")
    p.add_argument("--baseline", type=int, default=30, help="seconds measured with no bots")
    p.add_argument("--mobs", type=int, default=6, help="extra mobs spawned around each cave bot")
    p.add_argument("--mine", type=int, default=2, help="blocks broken per second by each cave bot")
    p.add_argument("--view-distance", type=int, default=10, help="client view distance of the bots")
    p.add_argument("--interval", type=float, default=5.0, help="seconds between samples")
    p.add_argument("--profile", choices=["jfr", "spark", "none"], default="jfr")
    p.add_argument("--radius", type=int, help="bots stay within this distance of 0,0 (default: setup value). "
                   "On Folia, a larger radius spreads players over more regions")
    p.add_argument("--build", action="store_true", help="build ChunkVeil (gradlew shadowJar) before the run")
    p.add_argument("--jvm-arg", action="append", default=[],
                   help="extra server JVM argument, repeatable (e.g. -Dchunkveil.disableRewriteCache=true)")
    p.add_argument("--locator-bar", action="store_true",
                   help="keep the vanilla locator bar on (off by default: it costs O(players^2) per move)")
    p.add_argument("--without-chunkveil", action="store_true",
                   help="run the same test with ChunkVeil removed, as a comparison baseline")
    p.add_argument("--label", help="name suffix for the results folder")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("analyze", help="(re)build the report of a finished run")
    p.add_argument("run_dir")
    p.set_defaults(func=cmd_analyze)

    p = sub.add_parser("start", help="start a test server for manual play")
    common(p)
    p.add_argument("--restore", action="store_true", help="restore the pristine pre-generated world first")
    p.set_defaults(func=cmd_start)

    p = sub.add_parser("rcon", help="send a console command to a running test server")
    p.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
    p.add_argument("command", nargs="+")
    p.set_defaults(func=cmd_rcon)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
