#!/usr/bin/env bash
set -euo pipefail

: "${PAPER_URL:?PAPER_URL is required}"
: "${PAPER_SHA256:?PAPER_SHA256 is required}"
: "${PROTOCOLLIB_URL:?PROTOCOLLIB_URL is required}"
: "${PROTOCOLLIB_SHA256:?PROTOCOLLIB_SHA256 is required}"
: "${CHUNKVEIL_JAR:?CHUNKVEIL_JAR is required}"

work="${RUNNER_TEMP:-/tmp}/chunkveil-compat-${PAPER_VERSION:-unknown}"
mkdir -p "$work/plugins"
curl --fail --location --retry 3 "$PAPER_URL" --output "$work/paper.jar"
curl --fail --location --retry 3 "$PROTOCOLLIB_URL" --output "$work/plugins/ProtocolLib.jar"
echo "$PAPER_SHA256  $work/paper.jar" | sha256sum --check --strict
echo "$PROTOCOLLIB_SHA256  $work/plugins/ProtocolLib.jar" | sha256sum --check --strict
cp "$CHUNKVEIL_JAR" "$work/plugins/ChunkVeil.jar"
printf 'eula=true\n' > "$work/eula.txt"
mkfifo "$work/console"
exec 3<>"$work/console"

(
  cd "$work"
  java -Xms512M -Xmx1G -jar paper.jar --nogui < console > server.log 2>&1
) &
server_pid=$!
ready=false
for _ in $(seq 1 180); do
  if grep -q 'Done (' "$work/server.log" 2>/dev/null; then ready=true; break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  sleep 1
done

if [[ "$ready" == true ]]; then
  printf 'chunkveil verify\n' >&3
  sleep 3
  printf 'stop\n' >&3
fi
for _ in $(seq 1 30); do
  kill -0 "$server_pid" 2>/dev/null || break
  sleep 1
done
kill "$server_pid" 2>/dev/null || true
wait "$server_pid" 2>/dev/null || true
cat "$work/server.log"

test "$ready" == true
grep -q 'ProtocolLib chunk listener enabled' "$work/server.log"
grep -q 'ChunkVeil enabled for worlds' "$work/server.log"
if grep -Eq 'ChunkVeil security state TRIPPED|Strict startup policy is stopping|Error occurred while enabling ChunkVeil' "$work/server.log"; then
  exit 1
fi
