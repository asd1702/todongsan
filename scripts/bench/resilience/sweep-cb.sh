#!/usr/bin/env bash
# 서킷 브레이커 before/after (docs/architecture/SPEND_CIRCUIT_BREAKER.md §5)
#   ./scripts/bench/resilience/sweep-cb.sh [first_run=11]
# 설정(label:서킷:차감 readTimeout ms:rate)마다 앱 재기동 → spend-stall(MP 30초 정지). 끝나면 앱을 기본 설정으로 다시 띄운다.
set -uo pipefail
S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPS="$S/../settlement/apps.sh"
run="${1:-11}"
CONFIGS="${SWEEP_CB_CONFIGS:-A-off-5s:false:5000:25 B-on-2s:true:2000:25 C-on-5s:true:5000:25 D-off-2s:false:2000:25 A-off-5s:false:5000:15 B-on-2s:true:2000:15}"
METRICS="--management.endpoints.web.exposure.include=health,metrics --server.tomcat.mbeanregistry.enabled=true"
LOG="$S/../../../reports/bench/resilience/sweep-cb.log"
trap '"$APPS" stop > /dev/null; MARKET_APP_ARGS="$METRICS" "$APPS" start async member_id > /dev/null; echo "[$(date +%T)] restored" | tee -a "$LOG"' EXIT
for c in $CONFIGS; do
  IFS=: read -r label cb rt rate <<< "$c"
  "$APPS" stop > /dev/null
  MARKET_APP_ARGS="$METRICS --market.member-point.spend-circuit-breaker-enabled=$cb --client.member-point.spend-read-timeout-millis=$rt" \
    "$APPS" start async member_id > /dev/null
  echo "[$(date +%T)] run=$run $label rate=$rate" | tee -a "$LOG"
  "$S/spend-stall.sh" "$run" 30 "$label-r$rate" "$rate" > /dev/null 2>&1
  tail -2 "$S/../../../reports/bench/resilience/$run-$label-r$rate/analysis.txt" | tee -a "$LOG"
  run=$((run + 1))
done
