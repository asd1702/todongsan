#!/usr/bin/env bash
# Market 결과 반영 배치 크기·Debezium poll 간격 비교 자동 실행 (docs/architecture/ASYNC_POINT_FLOW.md §17.11)
#
#   ./scripts/bench/settlement/sweep-market.sh
#   SWEEP_MARKET_BATCHES="100 500" SWEEP_POLLS="100" SWEEP_ROUNDS=2 ./scripts/bench/settlement/sweep-market.sh
#
# A: Market 결과 consumer의 max.poll.records(MARKET_APP_ARGS)별 ROUNDS회. MP는 기본(배치 100, 병렬도 3)
# 선택: 안전선(spend p95 중앙값 ≤ 85ms, spend max 중앙값 ≤ 300ms, 전부 verify PASS) 안에서 ③ Market 완료 중앙값 최소
# B: 고른 배치 크기에서 두 Debezium 커넥터의 poll.interval.ms(기본 500ms)별 ROUNDS회
# 끝(정상·오류 모두): 커넥터 설정을 시작 전 파일로 되돌리고 앱을 기본 설정으로 다시 띄운다.
# 결과: reports/bench/settlement/sweep-market-results.csv, sweep-market-summary.txt, sweep-market.log
set -euo pipefail

S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$S/../../../reports/bench/settlement"
CSV="$OUT/sweep-market-results.csv"
SUMMARY="$OUT/sweep-market-summary.txt"
LOG="$OUT/sweep-market.log"
MK_BATCHES="${SWEEP_MARKET_BATCHES:-100 250 500 1000}"
POLLS="${SWEEP_POLLS:-100 20}"
ROUNDS="${SWEEP_ROUNDS:-3}"
WARMUP_N="${SWEEP_WARMUP_N:-1000}"
CONNECT="localhost:18083"
CONNECTORS="market-outbox memberpoint-outbox"
run=0

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

wait_connectors() {
  for _ in $(seq 1 90); do
    ok=0
    for c in $CONNECTORS; do
      st=$(curl -s "$CONNECT/connectors/$c/status" 2>/dev/null || true)
      echo "$st" | grep -q '"connector":{"state":"RUNNING"' && echo "$st" | grep -q '"tasks":\[{"id":0,"state":"RUNNING"' && ok=$((ok + 1))
    done
    [ "$ok" = 2 ] && return 0; sleep 2
  done
  log "connectors not RUNNING"; return 1
}

put_config() { # put_config <connector> <json>
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -H 'Content-Type: application/json' \
    "$CONNECT/connectors/$1/config" --data-binary "$2")
  [ "$code" = 200 ] || { log "PUT $1 config failed: $code"; return 1; }
}

set_poll() { # set_poll <ms>
  for c in $CONNECTORS; do
    cfg=$(cat "$OUT/connector-$c-original.json" | sed -E 's/"poll\.interval\.ms":"[0-9]+",?//; s/,\}$/}/')
    cfg=$(echo "$cfg" | sed -E "s/^\{/{\"poll.interval.ms\":\"$1\",/")
    put_config "$c" "$cfg"
  done
  sleep 3; wait_connectors
  log "debezium poll.interval.ms=$1 ($(curl -s "$CONNECT/connectors/market-outbox/config" | grep -oE '"poll.interval.ms":"[0-9]+"'))"
}

restore_all() {
  set +e
  log "restore: connectors to original config, apps to default"
  for c in $CONNECTORS; do
    [ -f "$OUT/connector-$c-original.json" ] && put_config "$c" "$(cat "$OUT/connector-$c-original.json")"
  done
  sleep 3; wait_connectors
  "$S/apps.sh" stop > /dev/null
  "$S/apps.sh" start async member_id > /dev/null
  docker exec -e MYSQL_PWD=memberpoint-root-local-password member-point-mysql mysql -uroot -e \
    "UPDATE performance_schema.setup_instruments SET ENABLED='NO', TIMED='NO' WHERE NAME IN ('wait/synch/mutex/innodb/log_files_mutex','wait/synch/mutex/innodb/log_writer_mutex');" 2> /dev/null
  log "=== restored. done"
}

measure() { # measure <market batch> <label>
  local batch="$1" label="$2"
  "$S/apps.sh" stop > /dev/null
  MARKET_APP_ARGS="--spring.kafka.consumer.max-poll-records=$batch" "$S/apps.sh" start async member_id > /dev/null
  run=$((run + 1)); "$S/run.sh" "$run" "$WARMUP_N" 50 "warmup-$label" > /dev/null
  for _ in $(seq 1 "$ROUNDS"); do
    run=$((run + 1))
    line=$(RUN_METRICS_CSV="$CSV" "$S/redo-run.sh" "$run" "$label" | tail -1)
    log "$label run=$run $line"
    case "$line" in *,PASS) ;; *) log "VERIFY FAIL or bad result → stop"; exit 1 ;; esac
  done
}

summarize() {
  awk -F, 'NR > 1 { k=$2; md[k]=md[k]" "$5; mp[k]=mp[k]" "$4; p95[k]=p95[k]" "$6; mx[k]=mx[k]" "$7; gp[k]=gp[k]" "$8; if ($14 != "PASS") bad[k]=1; seen[k]=1 }
    function med(s,   a,n,i,j,t) { n=split(s,a," "); for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if (a[j]+0 < a[i]+0) { t=a[i]; a[i]=a[j]; a[j]=t }; return a[int((n+1)/2)] }
    END { for (k in seen) { ok = (!bad[k] && med(p95[k]) <= 85 && med(mx[k]) <= 300) ? "OK" : "OUT";
            printf "%s market_done=%s mp_done=%s p95=%s spend_max=%s gap=%s guard=%s\n", k, med(md[k]), med(mp[k]), med(p95[k]), med(mx[k]), med(gp[k]), ok } }' "$CSV" | sort
}

log "=== sweep-market start: market batches=[$MK_BATCHES] polls=[$POLLS] rounds=$ROUNDS warmup=$WARMUP_N"
for c in $CONNECTORS; do curl -s "$CONNECT/connectors/$c/config" > "$OUT/connector-$c-original.json"; done
grep -q '"connector.class"' "$OUT/connector-market-outbox-original.json" || { log "cannot read connector config"; exit 1; }
trap restore_all EXIT
"$S/cleanup.sh" runs > /dev/null
rm -f "$CSV"

for b in $MK_BATCHES; do measure "$b" "mk$b-poll500"; done
best=$(summarize | awk '$1 ~ /-poll500$/ && /guard=OK/ { split($2,a,"="); print a[2], $1 }' | sort -n | head -1 | awk '{print $2}' | sed -E 's/^mk([0-9]+)-poll500$/\1/')
best="${best:-100}"
log "chosen market batch size: $best"

for p in $POLLS; do set_poll "$p"; measure "$best" "mk$best-poll$p"; done

summarize > "$SUMMARY"
log "=== summary"; tee -a "$LOG" < "$SUMMARY"
