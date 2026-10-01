#!/usr/bin/env bash
# 비동기 포인트 파이프라인 점검 (docs/architecture/ASYNC_POINT_FLOW.md §6.3, R6)
#
#   ./scripts/cdc/health.sh
#   CONNECT_URL=http://localhost:18083 ./scripts/cdc/health.sh
#
# 출력: 커넥터 상태, consumer group lag, DLT 적재·미재발행 건수
# 종료 코드: 커넥터가 RUNNING이 아니거나 미재발행 DLT가 있으면 1 (lag는 일시적일 수 있어 경고만 한다)
set -uo pipefail
export MSYS_NO_PATHCONV=1

connect_url="${CONNECT_URL:-http://localhost:18083}"
bootstrap="localhost:29092"
status=0

kbin() { docker exec -i kafka "/opt/kafka/bin/$1" --bootstrap-server "$bootstrap" "${@:2}"; }

echo "## connectors ($connect_url)"
for name in $(curl -s "$connect_url/connectors" | tr -d '[]"' | tr ',' ' '); do
  body=$(curl -s "$connect_url/connectors/$name/status")
  states=$(echo "$body" | grep -oE '"state":"[A-Z]+"' | grep -oE '[A-Z]+' | tr '\n' ' ')
  printf "  %-22s %s\n" "$name" "$states"
  if echo "$states" | grep -qvE '^(RUNNING ?)+$'; then
    status=1
  fi
done

echo "## consumer lag"
for group in member-point-service market-service; do
  kbin kafka-consumer-groups.sh --describe --group "$group" 2>/dev/null \
    | awk -v g="$group" 'NR>1 && $2 != "" && $2 != "TOPIC" {lag[$2]+=($6 ~ /^[0-9]+$/ ? $6 : 0)}
        END {for (t in lag) printf "  %-22s %-30s lag=%d%s\n", g, t, lag[t], (lag[t] > 0 ? "  (WARN)" : "")}'
done

echo "## DLT"
for dlt in $(kbin kafka-topics.sh --list 2>/dev/null | grep -E -- '-dlt$'); do
  total=$(kbin kafka-get-offsets.sh --topic "$dlt" 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
  replayed=$(kbin kafka-consumer-groups.sh --describe --group "dlt-replay.${dlt}" 2>/dev/null \
    | awk -v t="$dlt" '$2==t && $4 ~ /^[0-9]+$/ {s+=$4} END {print s+0}')
  pending=$((total - replayed))
  printf "  %-30s total=%d replayed=%d pending=%d%s\n" "$dlt" "$total" "$replayed" "$pending" \
    "$([ "$pending" -gt 0 ] && echo '  (CHECK)')"
  if [ "$pending" -gt 0 ]; then
    status=1
  fi
done

exit $status
