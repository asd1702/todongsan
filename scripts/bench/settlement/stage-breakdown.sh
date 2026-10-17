#!/usr/bin/env bash
# 정산 run 1회의 구간별 시간 (docs/architecture/ASYNC_POINT_FLOW.md §17.11)
#
#   ./scripts/bench/settlement/stage-breakdown.sh <run> [label]   # CSV 한 줄 출력 (헤더는 --header)
#
# 모두 정산 API 시작(k6) 기준 ms. 두 DB의 outbox created_at(DATETIME(3), KST)과 run 디렉터리의 폴러 시각(VM 시계)을 쓴다.
#   requests_committed : 정산 API 응답 시각 = 정산 준비 TX(detail·요청 outbox) 커밋 직후. outbox created_at은 TX 시작 시각으로 모두 같아서 쓰지 않는다
#   mp_first/mp_last : MP가 첫/마지막 적립 결과 outbox를 쓴 시각 (적립 TX 안)
#   mp_done          : MP 지급 이력이 N건이 된 시각 (폴러)
#   market_done      : 마켓 SETTLED 시각 (폴러)
# 파생: cdc_first = mp_first - requests_committed (요청 CDC 전달 + MP 첫 배치), mp_span = mp_last - mp_first, market_tail = market_done - mp_last
set -euo pipefail

if [ "${1:-}" = "--header" ]; then
  echo "run,label,requests_committed,mp_first,mp_last,mp_done,market_done,cdc_first,mp_span,market_tail"; exit 0
fi
run="${1:?run}"; label="${2:-}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$DIR/../../../reports/bench/settlement"
. "$DIR/ids.sh"
market_id=$((MARKET_BASE + run))
pq() { docker exec -i -e MYSQL_PWD="${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" member-point-mysql mysql -uroot -N memberpoint "$@" 2>/dev/null; }

run_dir="$OUT/runs/$(awk -F, -v r="$run" '$5 == r {d=$33} END {print d}' "$OUT/results.csv")"
[ -d "$run_dir" ] || { echo "no run dir for run $run" >&2; exit 1; }
settle_line=$(grep '"metric":"http_req_duration","type":"Point"' "$run_dir/k6.json" \
  | grep -oE '"time":"[^"]+","value":[0-9.]+,"tags":\{[^}]*"name":"settle' | head -1)
settle_end=$(date -d "$(echo "$settle_line" | sed -E 's/"time":"([^"]+)".*/\1/')" +%s%3N)
settle_dur=$(echo "$settle_line" | sed -E 's/.*"value":([0-9.]+).*/\1/')
t0=$(awk -v e="$settle_end" -v d="$settle_dur" 'BEGIN {printf "%.0f", e - d}')

written=$settle_end
read -r mp_first mp_last <<< "$(pq -e "SELECT ROUND(UNIX_TIMESTAMP(MIN(created_at)) * 1000), ROUND(UNIX_TIMESTAMP(MAX(created_at)) * 1000)
                                     FROM point_outbox_event WHERE aggregate_id = '$market_id';" | tr '\t' ' ')"
mp_done=$(tr -d '\r\n' < "$run_dir/mp_done")
market_done=$(tr -d '\r\n' < "$run_dir/market_done")

awk -v r="$run" -v l="$label" -v t0="$t0" -v w="$written" -v f="$mp_first" -v la="$mp_last" -v md="$mp_done" -v kd="$market_done" \
  'BEGIN { printf "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d\n", r, l, w-t0, f-t0, la-t0, md-t0, kd-t0, f-w, la-f, kd-la }'
