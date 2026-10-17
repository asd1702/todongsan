#!/usr/bin/env bash
# 인기 마켓 입장 제한 한도 비교 (docs/architecture/HOT_MARKET_ADMISSION.md §3)
#   ./scripts/bench/resilience/sweep-admission.sh [first_run=31]
# 한도마다 Market만 재기동(MP는 데워진 그대로) → 인기 마켓 50/s + 다른 마켓 5/s + 조회 10/s, 장애 없음.
# 시간대 흔들림을 상쇄하려고 정순 → 역순으로 잰다. 0 = 제한 없음. 끝나면 Market을 기본 설정으로 다시 띄운다.
set -uo pipefail
S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPS="$S/../settlement/apps.sh"
OUT="$S/../../../reports/bench/resilience"
run="${1:-31}"
LIMITS="${SWEEP_LIMITS:-0 2 4 8 16 16 8 4 2 0}"
RATE="${SWEEP_RATE:-50}"; OTHER_RATE="${SWEEP_OTHER_RATE:-5}"
METRICS="--management.endpoints.web.exposure.include=health,metrics --server.tomcat.mbeanregistry.enabled=true"
LOG="$OUT/sweep-admission.log"
trap 'ONLY_MARKET=1 MARKET_APP_ARGS="$METRICS" "$APPS" start async member_id > /dev/null; echo "[$(date +%T)] restored" | tee -a "$LOG"' EXIT
for limit in $LIMITS; do
  ONLY_MARKET=1 MARKET_APP_ARGS="$METRICS --market.prediction.admission.max-in-flight-per-market=$limit" \
    "$APPS" start async member_id > /dev/null
  label="limit$limit"
  echo "[$(date +%T)] run=$run $label rate=$RATE other=$OTHER_RATE" | tee -a "$LOG"
  "$S/spend-stall.sh" "$run" 0 "$label" "$RATE" "$OTHER_RATE" > /dev/null 2>&1
  grep "SUMMARY" "$OUT/$run-$label/analysis.txt" | tee -a "$LOG" || { echo "no summary for run $run (see $OUT/$run-$label/k6.log)" | tee -a "$LOG"; exit 1; }
  run=$((run + 1))
done
