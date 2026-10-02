#!/usr/bin/env bash
# 정산 지급 요청 형식(item / chunk)과 묶음 크기 비교 (docs/architecture/ASYNC_POINT_FLOW.md §18.6 6번, 결과 ⑱)
#
#   ./scripts/bench/settlement/sweep-chunk.sh
#   SWEEP_PHASE1="item chunk500" SWEEP_PHASE2="chunk100 chunk500" SWEEP_ROUNDS=1 ./scripts/bench/settlement/sweep-chunk.sh
#
# 시간대에 따라 환경이 흔들리므로(⑯) 비교 대상을 번갈아 잰다. 블록마다 앱 재기동 → warmup 1회 → ROUNDS회.
#   1단계: item / chunk500을 번갈아 2번씩 (A B A B)
#   2단계: 묶음 크기 100 / 250 / 500 / 1000을 정순 → 역순으로 (100 250 500 1000 1000 500 250 100)
# MP·Market의 트랜잭션 크기(max-batch-items 100 / 500)와 나머지 설정은 기본값 그대로다.
# 끝(정상·오류 모두): 앱을 기본 설정(item)으로 다시 띄운다.
# 결과: reports/bench/settlement/sweep-chunk-results.csv, sweep-chunk-summary.txt, sweep-chunk.log, stage-breakdown-chunk.csv
set -euo pipefail

S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$S/../../../reports/bench/settlement"
CSV="$OUT/sweep-chunk-results.csv"
STAGES="$OUT/stage-breakdown-chunk.csv"
SUMMARY="$OUT/sweep-chunk-summary.txt"
LOG="$OUT/sweep-chunk.log"
PHASE1="${SWEEP_PHASE1:-item chunk500 item chunk500}"
PHASE2="${SWEEP_PHASE2:-chunk100 chunk250 chunk500 chunk1000 chunk1000 chunk500 chunk250 chunk100}"
ROUNDS="${SWEEP_ROUNDS:-2}"
WARMUP_N="${SWEEP_WARMUP_N:-1000}"
run=0

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

restore() {
  set +e
  log "restore: apps default (item)"
  "$S/apps.sh" stop > /dev/null
  "$S/apps.sh" start async member_id > /dev/null
  docker exec -e MYSQL_PWD=memberpoint-root-local-password member-point-mysql mysql -uroot -e \
    "UPDATE performance_schema.setup_instruments SET ENABLED='NO', TIMED='NO' WHERE NAME IN ('wait/synch/mutex/innodb/log_files_mutex','wait/synch/mutex/innodb/log_writer_mutex');" 2> /dev/null
  log "=== restored. done"
}

market_args() { # market_args <label> → item | chunkN
  case "$1" in
    item) echo "--market.point-transport.settlement-request-format=item" ;;
    chunk*) echo "--market.point-transport.settlement-request-format=chunk --market.point-transport.settlement-request-chunk-size=${1#chunk}" ;;
  esac
}

measure() { # measure <label>
  local label="$1"
  "$S/apps.sh" stop > /dev/null
  MARKET_APP_ARGS="$(market_args "$label")" "$S/apps.sh" start async member_id > /dev/null
  run=$((run + 1)); "$S/run.sh" "$run" "$WARMUP_N" 50 "warmup-$label" > /dev/null
  for _ in $(seq 1 "$ROUNDS"); do
    run=$((run + 1))
    line=$(RUN_METRICS_CSV="$CSV" "$S/redo-run.sh" "$run" "$label" | tail -1)
    "$S/stage-breakdown.sh" "$run" "$label" >> "$STAGES" || log "stage breakdown failed for run $run"
    log "$label run=$run $line | stages(cdc_first,mp_span,market_tail) $(tail -1 "$STAGES" | cut -d, -f8-10)"
    case "$line" in *,PASS) ;; *) log "VERIFY FAIL or bad result → stop"; exit 1 ;; esac
  done
}

# label별 중앙값 (짝수 개면 낮은 쪽). market_done(5), mp_done(4), p95(6), spend_max(7) + 정산 준비 커밋·구간
summarize() {
  awk -F, 'FNR == 1 { file++; next }
    file == 1 { k=$2; md[k]=md[k]" "$5; mp[k]=mp[k]" "$4; p95[k]=p95[k]" "$6; mx[k]=mx[k]" "$7; if ($14 != "PASS") bad[k]=1; seen[k]=1 }
    file == 2 { k=$2; cm[k]=cm[k]" "$3; cdc[k]=cdc[k]" "$8; span[k]=span[k]" "$9; tail[k]=tail[k]" "$10 }
    function med(s,   a,n,i,j,t) { n=split(s,a," "); if (n == 0) return "-"; for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if (a[j]+0 < a[i]+0) { t=a[i]; a[i]=a[j]; a[j]=t }; return a[int((n+1)/2)] }
    function all(s) { gsub(/^ /, "", s); return s }
    END { for (k in seen) printf "%s n=%d market_done=%s [%s] mp_done=%s p95=%s spend_max=%s commit=%s cdc_first=%s mp_span=%s market_tail=%s %s\n",
            k, split(all(md[k]), x, " "), med(md[k]), all(md[k]), med(mp[k]), med(p95[k]), med(mx[k]), med(cm[k]), med(cdc[k]), med(span[k]), med(tail[k]), bad[k] ? "FAIL" : "PASS" }' "$CSV" "$STAGES" | sort
}

log "=== sweep-chunk start: phase1=[$PHASE1] phase2=[$PHASE2] rounds=$ROUNDS warmup=$WARMUP_N"
trap restore EXIT
"$S/apps.sh" stop > /dev/null || true
"$S/cleanup.sh" runs > /dev/null
rm -f "$CSV"
"$S/stage-breakdown.sh" --header > "$STAGES"

for label in $PHASE1; do measure "$label"; done
log "phase1 done"
for label in $PHASE2; do measure "$label"; done

summarize > "$SUMMARY"
log "=== summary"; tee -a "$LOG" < "$SUMMARY"
