#!/bin/bash
# Starts the local test world (if it isn't already up), opens Minecraft straight into it,
# and stops the world again when the game quits. Used by "T3 Craft.app".
#
# Optional profile (T3CRAFT_PROFILE, default scripts/launcher.local; not committed), a shell file with:
#   T3CRAFT_USERNAME=Steve            # offline player name (default Player); keeps your op and inventory
#   T3CRAFT_SERVER_DIR=run-server     # which local world; another directory is another world
#   T3CRAFT_TITLE="T3 Craft"          # name in notifications
# A second world gets its own app: scripts/install-macos-app.sh --name "My World" --profile <file>.
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="${T3CRAFT_PROFILE:-$REPO/scripts/launcher.local}"
# shellcheck disable=SC1090
[ -f "$PROFILE" ] && . "$PROFILE"
USERNAME="${T3CRAFT_USERNAME:-Player}"
SERVER_DIR="${T3CRAFT_SERVER_DIR:-run-server}"
TITLE="${T3CRAFT_TITLE:-T3 Craft}"
LOGS="$HOME/Library/Logs/T3Craft"
mkdir -p "$LOGS"
cd "$REPO"
PORT="$(sed -n 's/^server-port=//p' "$SERVER_DIR/server.properties" 2>/dev/null)"
PORT="${PORT:-25565}"

notify() { osascript -e "display notification \"$1\" with title \"$TITLE\"" >/dev/null 2>&1; }

if ! JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null)"; then
  osascript -e 'display alert "T3 Craft needs Java 25 or newer" message "Install a JDK (for example Temurin 25) and open T3 Craft again."' >/dev/null
  exit 1
fi
export JAVA_HOME

if pgrep -f "java.*dli.env=client" >/dev/null; then
  notify "Minecraft is already running."
  exit 0
fi

# Optional SSH tunnels (scripts/tunnels.local, not committed): "local-port ssh-host remote-host:port".
# Useful when a remote T3 server only answers on its own loopback.
if [ -f "$REPO/scripts/tunnels.local" ]; then
  while read -r port host target; do
    case "$port" in ''|\#*) continue ;; esac
    if ! lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      ssh -f -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
        -L "$port:$target" "$host" >> "$LOGS/tunnels.log" 2>&1 || notify "Couldn't reach $host; its threads won't load."
    fi
  done < "$REPO/scripts/tunnels.local"
fi

started_server=0
if ! lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  notify "Starting your world…"
  nohup ./gradlew runServer -PserverDir="$SERVER_DIR" --args=nogui > "$LOGS/server.log" 2>&1 &
  started_server=1
  for _ in $(seq 1 90); do
    lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 && break
    sleep 2
  done
  if ! lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    osascript -e "display alert \"The world didn't start\" message \"See $LOGS/server.log\"" >/dev/null
    exit 1
  fi
fi

# Blocks until the game window is closed.
./gradlew runClient -Pjoin="localhost:$PORT" -Pusername="$USERNAME" > "$LOGS/client.log" 2>&1

# Only stop the world if this launcher started it. Never `gradlew --stop`: it kills running games.
if [ "$started_server" = 1 ]; then
  pkill -f "java.*dli.env=server" 2>/dev/null
fi
