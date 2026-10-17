#!/usr/bin/env bash
# 전체 동시 진행 한도 비교 (docs/architecture/HOT_MARKET_ADMISSION.md §6)
#   ./scripts/bench/resilience/sweep-total.sh [first_run=61]
# 한도마다 Market만 재기동 → 마켓 16개에 총 100/s, 60초 (multi-market.sh). 정순 → 역순. 0 = 제한 없음. 끝나면 기본 설정으로 다시 띄운다.
set -uo pipefail
S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPS="$S/../settlement/apps.sh"
OUT="$S/../../../reports/bench/resilience"
run="${1:-61}"
LIMITS="${SWEEP_TOTALS:-0 6 10 16 24 24 16 10 6 0}"
MARKETS="${SWEEP_MARKETS:-16}"; RATE="${SWEEP_RATE:-100}"
METRICS="--management.endpoints.web.exposure.include=health,metrics --server.tomcat.mbeanregistry.enabled=true"
LOG="$OUT/sweep-total.log"
trap 'ONLY_MARKET=1 MARKET_APP_ARGS="$METRICS" "$APPS" start async member_id > /dev/null; echo "[$(date +%T)] restored" | tee -a "$LOG"' EXIT
for limit in $LIMITS; do
  ONLY_MARKET=1 MARKET_APP_ARGS="$METRICS --market.prediction.admission.max-in-flight-total=$limit" "$APPS" start async member_id > /dev/null
  echo "[$(date +%T)] run=$run total=$limit markets=$MARKETS rate=$RATE" | tee -a "$LOG"
  "$S/multi-market.sh" "$run" "$MARKETS" "$RATE" 2>&1 | tee -a "$LOG" | grep -q SUMMARY || { echo "no summary for run $run" | tee -a "$LOG"; exit 1; }
  run=$((run + 1))
done
