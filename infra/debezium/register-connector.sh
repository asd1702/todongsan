#!/usr/bin/env bash
# Debezium 커넥터 등록/갱신 (PUT이므로 재실행해도 안전하다)
#   ./infra/debezium/register-connector.sh market-outbox
#   CONNECT_URL=http://localhost:18083 ./infra/debezium/register-connector.sh market-outbox
set -euo pipefail

name="${1:?connector name required (e.g. market-outbox)}"
connect_url="${CONNECT_URL:-http://localhost:18083}"
config_file="$(dirname "$0")/connectors/${name}.json"

curl -sf -X PUT -H "Content-Type: application/json" \
  --data @"${config_file}" \
  "${connect_url}/connectors/${name}/config" > /dev/null

sleep 3
curl -s "${connect_url}/connectors/${name}/status"
echo
