#!/bin/bash
# Starts the local test world (if it isn't already up), opens Minecraft straight into it,
# and stops the world again when the game quits. Used by "T3 Craft.app".
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$HOME/Library/Logs/T3Craft"
mkdir -p "$LOGS"
cd "$REPO"

notify() { osascript -e "display notification \"$1\" with title \"T3 Craft\"" >/dev/null 2>&1; }

if ! JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null)"; then
  osascript -e 'display alert "T3 Craft needs Java 25 or newer" message "Install a JDK (for example Temurin 25) and open T3 Craft again."' >/dev/null
  exit 1
fi
export JAVA_HOME

if pgrep -f "java.*dli.env=client" >/dev/null; then
  notify "Minecraft is already running."
  exit 0
fi

started_server=0
if ! lsof -iTCP:25565 -sTCP:LISTEN >/dev/null 2>&1; then
  notify "Starting your world…"
  nohup ./gradlew runServer --args=nogui > "$LOGS/server.log" 2>&1 &
  started_server=1
  for _ in $(seq 1 90); do
    lsof -iTCP:25565 -sTCP:LISTEN >/dev/null 2>&1 && break
    sleep 2
  done
  if ! lsof -iTCP:25565 -sTCP:LISTEN >/dev/null 2>&1; then
    osascript -e "display alert \"The world didn't start\" message \"See $LOGS/server.log\"" >/dev/null
    exit 1
  fi
fi

# Blocks until the game window is closed.
./gradlew runClient -Pjoin=localhost:25565 > "$LOGS/client.log" 2>&1

# Only stop the world if this launcher started it. Never `gradlew --stop`: it kills running games.
if [ "$started_server" = 1 ]; then
  pkill -f "java.*dli.env=server" 2>/dev/null
fi
