#!/usr/bin/env bash
# 정산 sync/async 비교 실험 전체 실행 (docs/architecture/ASYNC_POINT_FLOW.md §15.2)
#
#   ./scripts/bench/settlement/suite.sh [rounds=3] [rate=50] [N 목록="100 1000"]
#
# 설정: sync / async+member_id / async+market_id
# 설정마다: 앱 기동 → warmup run(N=10, 결과에 label=warmup으로 남김) → N × rounds 측정
# run 번호는 1부터 순서대로 쓰므로 시작 전에 기존 실험 run 데이터를 정리한다 (회원 풀은 유지).
set -euo pipefail

rounds="${1:-3}"; rate="${2:-50}"; sizes="${3:-100 1000}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIGS=("sync na" "async member_id" "async market_id")

total=$(( ${#CONFIGS[@]} * (1 + $(echo "$sizes" | wc -w) * rounds) ))
if [ "$total" -gt 40 ]; then echo "run 수 $total > 40 (run 번호 대역 초과)" >&2; exit 1; fi
echo "suite: rounds=$rounds rate=$rate sizes=[$sizes] runs=$total"

"$DIR/cleanup.sh" runs
"$DIR/seed.sh" members

run=1
for config in "${CONFIGS[@]}"; do
  read -r mode key <<< "$config"
  "$DIR/apps.sh" start "$mode" "$key"
  "$DIR/run.sh" "$run" 10 "$rate" warmup > /dev/null; run=$((run + 1))
  for n in $sizes; do
    for round in $(seq 1 "$rounds"); do
      "$DIR/run.sh" "$run" "$n" "$rate" "round$round" | tail -1
      run=$((run + 1))
    done
  done
done
"$DIR/apps.sh" stop
echo "done: reports/bench/settlement/results.csv"
