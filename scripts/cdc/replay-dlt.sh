#!/usr/bin/env bash
# DLT 메시지를 원 토픽에 재발행한다 (docs/architecture/ASYNC_POINT_FLOW.md §6.1 R3)
#
#   ./scripts/cdc/replay-dlt.sh market.settlement-payout-requests-dlt --dry-run   # 재발행 대상 건수만 확인
#   ./scripts/cdc/replay-dlt.sh market.settlement-payout-requests-dlt             # 재발행
#
# - 원인을 해결한 뒤 실행한다. 소비 측이 멱등이므로 같은 메시지가 다시 처리되어도 안전하다.
# - 전용 consumer group(dlt-replay.<dlt-topic>)으로 읽으므로 이미 재발행한 메시지는 다시 보내지 않는다.
# - key/value만 재발행한다 (DLT 헤더는 버린다). 파티션은 key 해시로 원래와 같게 정해진다.
# - 읽은 메시지는 임시 파일에 남긴다. 재발행이 실패하면 그 파일로 다시 보낼 수 있다.
set -euo pipefail
export MSYS_NO_PATHCONV=1

dlt="${1:?usage: replay-dlt.sh <topic>-dlt [--dry-run]}"
mode="${2:-}"
target="${dlt%-dlt}"
if [ "$target" = "$dlt" ]; then
  echo "DLT 토픽 이름은 -dlt로 끝나야 한다: $dlt" >&2
  exit 1
fi
group="dlt-replay.${dlt}"
bootstrap="localhost:29092"
tab=$'\t'

kbin() { docker exec -i kafka "/opt/kafka/bin/$1" --bootstrap-server "$bootstrap" "${@:2}"; }

end_offset=$(kbin kafka-get-offsets.sh --topic "$dlt" 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
replayed=$(kbin kafka-consumer-groups.sh --describe --group "$group" 2>/dev/null \
  | awk -v t="$dlt" '$2==t && $4 ~ /^[0-9]+$/ {s+=$4} END {print s+0}')
pending=$((end_offset - replayed))
echo "dlt=$dlt target=$target total=$end_offset replayed=$replayed pending=$pending"

if [ "$mode" = "--dry-run" ] || [ "$pending" -le 0 ]; then
  exit 0
fi

file="$(mktemp -t dlt-replay-XXXXXX.tsv)"
kbin kafka-console-consumer.sh --topic "$dlt" --group "$group" --from-beginning \
  --timeout-ms 10000 --property print.key=true --property key.separator="$tab" 2>/dev/null > "$file" || true
count=$(wc -l < "$file" | tr -d ' ')
echo "read $count messages -> $file"
if [ "$count" -eq 0 ]; then
  exit 0
fi

kbin kafka-console-producer.sh --topic "$target" \
  --property parse.key=true --property key.separator="$tab" < "$file"
echo "replayed $count messages to $target"
