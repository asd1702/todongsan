#!/usr/bin/env bash
# 예측 확정 묶음 처리 before/after (docs/architecture/PREDICTION_BATCH_CONFIRM.md §4 4번)
#   ./scripts/bench/resilience/sweep-batch.sh [first_run=81]
# 설정(label:묶음 사용:묶음 크기[:마켓별 한도:전체 한도])마다 Market만 재기동 → 인기 마켓 50/s + 다른 마켓 5/s + 조회 10/s (spend-stall.sh, 장애 없음).
# 입장 제한은 기본(마켓별 8, 전체 16). 끝나면 Market을 기본 설정으로 다시 띄운다.
set -uo pipefail
S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPS="$S/../settlement/apps.sh"
OUT="$S/../../../reports/bench/resilience"
run="${1:-81}"
CONFIGS="${SWEEP_BATCH_CONFIGS:-off:false:50 on50:true:50 off:false:50 on50:true:50}"
RATE="${SWEEP_RATE:-50}"; OTHER_RATE="${SWEEP_OTHER_RATE:-5}"
METRICS="--management.endpoints.web.exposure.include=health,metrics --server.tomcat.mbeanregistry.enabled=true"
LOG="$OUT/sweep-batch.log"
trap 'ONLY_MARKET=1 MARKET_APP_ARGS="$METRICS" "$APPS" start async member_id > /dev/null; echo "[$(date +%T)] restored" | tee -a "$LOG"' EXIT
for c in $CONFIGS; do
  IFS=: read -r label enabled size per total <<< "$c"
  args="$METRICS --market.prediction.batch-confirm.enabled=$enabled --market.prediction.batch-confirm.max-batch-size=$size"
  args="$args --market.prediction.admission.max-in-flight-per-market=${per:-8} --market.prediction.admission.max-in-flight-total=${total:-16}"
  args="$args --logging.level.com.todongsan.marketservice.market.service.PredictionConfirmBatcher=DEBUG"
  ONLY_MARKET=1 MARKET_APP_ARGS="$args" "$APPS" start async member_id > /dev/null
  echo "[$(date +%T)] run=$run batch=$label rate=$RATE other=$OTHER_RATE" | tee -a "$LOG"
  "$S/spend-stall.sh" "$run" 0 "batch-$label" "$RATE" "$OTHER_RATE" > /dev/null 2>&1
  grep "SUMMARY" "$OUT/$run-batch-$label/analysis.txt" | tee -a "$LOG" || { echo "no summary for run $run" | tee -a "$LOG"; exit 1; }
  grep "DB 예측 상태\|MP 차감 이력" "$OUT/$run-batch-$label/analysis.txt" | tee -a "$LOG"
  # 2건 이상 묶음만 DEBUG로 남는다: 묶음 수, 묶인 건수, 평균·최대 크기
  grep -o "Prediction batch confirmed. marketId=[0-9]*, size=[0-9]*" "$S/../../../reports/bench/settlement/market.log" \
    | awk -F'size=' '{n++; s+=$2; if ($2 > m) m = $2} END {printf "BATCHES(size>=2): batches=%d items=%d avg=%.1f max=%d\n", n, s, (n ? s / n : 0), m}' \
    | tee -a "$LOG"
  run=$((run + 1))
done
