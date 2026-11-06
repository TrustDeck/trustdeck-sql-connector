#!/usr/bin/env bash
# TrustDeck SQL Connector
# Copyright 2026 Armin Müller
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

# Absolute path of the directory this script is in
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT_DIR/.env"
BASE_COMPOSE="$ROOT_DIR/docker/compose.yaml"
NETWORK_COMPOSE="$ROOT_DIR/docker/compose.trustdeck-network.yaml"

# Compose project name; set CONNECTOR_PROJECT to run several instances side by side
PROJECT="${CONNECTOR_PROJECT:-trustdeck-sql-connector}"
SERVICE="trustdeck-sql-connector"

# UID of the "connector" user inside the image (see docker/Dockerfile)
CONTAINER_UID=10001

# Helper for printing how to use
usage() {
  cat <<EOF
Usage: $0 <standalone|trustdeck> <start|stop|restart|status|logs|build|progress>

Setups:
  standalone  - Connector only; TrustDeck and Keycloak are reached through the URLs in .env
  trustdeck   - Connector joins the external Docker network 'trustdeck', so .env can use
                container names such as http://trustdeck-backend:8080 (TrustDeck on the same host)

Actions:
  start    - Build the image, (re)create the container, and wait until it is ready
             (or, for a short one-shot import, until it has finished)
  stop     - Stop and remove the container (the progress file is kept)
  restart  - stop, then start
  status   - Show container state, readiness, and the reason of the last pause
  logs     - Follow the container log
  build    - Build the image only
  progress - Show the last completed row, the latest throughput, and a summary of the
             dead-letter and review files (uses jq for the summary when installed)

Environment:
  CONNECTOR_PROJECT  Compose project name (default: trustdeck-sql-connector)
  HTTP(S)_PROXY, NO_PROXY  Passed to the image build when set

Examples:
  $0 trustdeck start
  $0 trustdeck logs
  $0 trustdeck progress
  CONNECTOR_PROJECT=connector-b $0 standalone start
EOF
  exit 1
}

# Decide whether Docker needs sudo. "sudo -E" keeps proxy variables for the image build.
# If your user is not in the docker group, run "sudo -v" before this script so sudo can prompt once.
if docker info >/dev/null 2>&1; then
  SUDO_DOCKER=()
elif command -v sudo >/dev/null 2>&1; then
  SUDO_DOCKER=(sudo -E)
else
  echo "Cannot talk to Docker and sudo is not available."
  echo "   - Is the Docker daemon running?"
  echo "   - Do you need to be in the 'docker' group or use sudo?"
  exit 1
fi

docker_cmd() {
  "${SUDO_DOCKER[@]}" docker "$@"
}

# Runs docker compose with the project name, .env, and the compose files of the selected setup
compose() {
  docker_cmd compose --project-name "$PROJECT" --env-file "$ENV_FILE" "${COMPOSE_FILES[@]}" "$@"
}

# Reads one variable from .env without exporting the whole file
env_value() {
  local name="$1"
  local default="${2:-}"
  local value
  value="$(sed -n "s/^${name}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")"
  echo "${value:-$default}"
}

# Fails early on missing files that Compose would otherwise replace with empty directories
check_files() {
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "Environment file not found: $ENV_FILE"
    echo "Copy .env.example to .env and fill it in."
    exit 1
  fi

  if [[ ! -f "$ROOT_DIR/config/instance/application.yaml" ]]; then
    echo "Instance configuration not found: $ROOT_DIR/config/instance/application.yaml"
    exit 1
  fi

  if [[ ! -d "$ROOT_DIR/config/instance/truststores" ]]; then
    echo "Truststore directory not found: $ROOT_DIR/config/instance/truststores"
    exit 1
  fi

  if [[ ! -d "$ROOT_DIR/drivers" ]]; then
    echo "Driver directory not found: $ROOT_DIR/drivers"
    exit 1
  fi
}

# The trustdeck network is created by the TrustDeck stack; without it TrustDeck is not running here
check_trustdeck_network() {
  if ! docker_cmd network inspect trustdeck >/dev/null 2>&1; then
    echo "Docker network 'trustdeck' does not exist. Start the TrustDeck stack first,"
    echo "or use the 'standalone' setup if TrustDeck runs elsewhere."
    exit 1
  fi
}

# Resolves a host directory setting the way Compose does: relative to docker/
host_dir() {
  local configured
  configured="$(env_value "$1" "$2")"

  if [[ "$configured" == /* ]]; then
    echo "$configured"
  else
    echo "$ROOT_DIR/docker/$configured"
  fi
}

# Creates a mounted directory and makes it writable for the container user.
# Without this, Docker would create it owned by root and the connector could not write to it.
ensure_writable_dir() {
  local dir="$1"

  if [[ ! -d "$dir" ]]; then
    echo "Creating directory: $dir"
    mkdir -p "$dir" 2>/dev/null || sudo mkdir -p "$dir"
  fi

  if [[ "$(stat -c %u "$dir")" != "$CONTAINER_UID" ]]; then
    echo "Handing the directory to the container user (UID $CONTAINER_UID): $dir"
    if ! { chown "$CONTAINER_UID:$CONTAINER_UID" "$dir" 2>/dev/null || sudo chown "$CONTAINER_UID:$CONTAINER_UID" "$dir"; }; then
      echo "Could not change the owner; making the directory writable for everyone instead."
      chmod 0777 "$dir" 2>/dev/null || sudo chmod 0777 "$dir"
    fi
  fi
}

# Reads a state file, which belongs to the container user and is readable by its owner only
read_state_file() {
  local file="$1"
  [[ -f "$file" ]] || return 1
  cat "$file" 2>/dev/null || sudo cat "$file"
}

# Waits for the Docker health check, which calls the readiness endpoint.
# A one-shot import of a small table may already have finished; exit status 0 counts as success.
wait_for_ready() {
  local retries="${1:-36}"
  local delay="${2:-5}"
  local container
  container="$(compose ps -aq "$SERVICE")"

  if [[ -z "$container" ]]; then
    echo "The connector container does not exist."
    return 1
  fi

  echo "Waiting for the connector to become ready (the first health check runs after about 30s) ..."

  for i in $(seq 1 "$retries"); do
    local status
    status="$(docker_cmd inspect -f '{{if eq .State.Status "running"}}{{if .State.Health}}{{.State.Health.Status}}{{else}}running{{end}}{{else}}{{.State.Status}}:{{.State.ExitCode}}{{end}}' "$container" 2>/dev/null || echo "unknown")"

    case "$status" in
      healthy)
        echo "Connector is ready."
        return 0
        ;;
      exited:0)
        echo "One-shot import finished."
        compose logs --tail=5 --no-log-prefix "$SERVICE" || true
        return 0
        ;;
      unhealthy|exited:*|dead:*)
        echo "Connector is $status: either it could not start (configuration, database, or TrustDeck"
        echo "not reachable at startup), or it paused processing because TrustDeck, Keycloak, the"
        echo "database, or a state file is unavailable. Last log lines:"
        compose logs --tail=25 --no-log-prefix "$SERVICE" || true
        return 1
        ;;
    esac

    echo "  [$i/$retries] status: $status (retrying in ${delay}s ...)"
    sleep "$delay"
  done

  echo "Timed out waiting for the connector. Check: $0 $MODE logs"
  return 1
}

build() {
  echo "Building the connector image ..."
  compose build
}

start() {
  check_files
  [[ "$MODE" == "trustdeck" ]] && check_trustdeck_network
  ensure_writable_dir "$(host_dir STATE_HOST_DIR ../state)"
  ensure_writable_dir "$(host_dir STATISTICS_HOST_DIR ../statistics)"
  build

  echo "Starting the connector (project '$PROJECT', setup '$MODE', mode '$(env_value CONNECTOR_MODE ONE_SHOT)') ..."
  compose up -d

  wait_for_ready
  echo "Follow the log with: $0 $MODE logs"
}

stop() {
  echo "Stopping the connector (project '$PROJECT') ..."
  compose down
  echo "Connector stopped. The progress file is kept; the next start continues after the last completed row."
}

status() {
  compose ps -a

  local port
  port="$(env_value HEALTH_HOST_PORT 18081)"
  echo
  echo -n "Readiness (http://127.0.0.1:$port/actuator/health/readiness): "
  if curl -fsS "http://127.0.0.1:$port/actuator/health/readiness" 2>/dev/null; then
    echo
    return 0
  fi
  echo "not ready or not reachable"

  # A paused connector names the cause, and for TrustDeck failures the domain, when it pauses
  echo
  echo "Last pause, recovery, and completion events:"
  compose logs --no-log-prefix "$SERVICE" 2>/dev/null \
    | grep -E 'pausing processing|Recovery probe failed|processing resumed|Processing paused|completed after|connector stops' | tail -n 5 \
    || echo "  none found in the current container log"
}

logs() {
  compose logs -f --no-log-prefix --since 10m "$SERVICE"
}

# Summarizes one JSON Lines recovery file by status, category, and HTTP status
summarize_recovery_file() {
  local name="$1"
  local file="$2"
  local content

  echo "--- $name ($file)"
  if ! content="$(read_state_file "$file")"; then
    echo "  no entries"
    return
  fi

  if command -v jq >/dev/null; then
    printf '%s\n' "$content" | jq -r '"\(.status)\t\(.failureCategory)\t\(.httpStatus // "-")"' | sort | uniq -c
  else
    echo "  $(printf '%s\n' "$content" | grep -c . || true) entries (install jq for a breakdown)"
  fi
}

progress() {
  local state
  state="$(host_dir STATE_HOST_DIR ../state)"

  echo "=== Last completed row"
  local position
  if position="$(read_state_file "$state/progress.json")"; then
    if command -v jq >/dev/null; then
      printf '%s\n' "$position" | jq -r '"Table:    \(.source.table)\nKey:      \(.key.value)\nProgress: \(.progress.value)\nUpdated:  \(.updatedAt)"'
    else
      printf '%s\n' "$position"
    fi
  else
    echo "No progress file yet; no row has been completed."
  fi

  echo
  echo "=== Latest throughput (logged once per minute)"
  compose logs --no-log-prefix "$SERVICE" 2>/dev/null | grep -E 'Progress: |completed after' | tail -n 3 \
    || echo "  no progress line yet"

  echo
  echo "=== Recovery files"
  summarize_recovery_file "Dead-letter file" "$state/dlt.jsonl"
  summarize_recovery_file "Review file" "$state/review.jsonl"

  echo
  echo "=== Connector"
  status | tail -n +2
}

# --- Main script ---
MODE="${1:-}"
ACTION="${2:-}"

if [[ -z "$MODE" || -z "$ACTION" ]]; then
  usage
fi

case "$MODE" in
  standalone) COMPOSE_FILES=(-f "$BASE_COMPOSE") ;;
  trustdeck)  COMPOSE_FILES=(-f "$BASE_COMPOSE" -f "$NETWORK_COMPOSE") ;;
  *) usage ;;
esac

case "$ACTION" in
  start)    start ;;
  stop)     check_files; stop ;;
  restart)  check_files; stop; start ;;
  status)   check_files; status ;;
  logs)     check_files; logs ;;
  build)    check_files; build ;;
  progress) check_files; progress ;;
  *) usage ;;
esac
