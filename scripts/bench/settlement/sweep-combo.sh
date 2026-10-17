#!/usr/bin/env bash
# Market 최적 설정 위에서 MP 배치·병렬도 재확인 + 구간별 시간 분해 (docs/architecture/ASYNC_POINT_FLOW.md §17.11)
#
#   ./scripts/bench/settlement/sweep-combo.sh
#
# 1. sweep-market.sh가 끝나기를 기다린다 (sweep-market.log의 "=== restored. done").
# 2. sweep-market-summary.txt에서 안전선을 넘은 것 중 ③이 가장 짧은 Market 배치·Debezium poll 간격을 고른다 (없으면 100 / 500).
# 3. sweep-market의 run들도 구간별 시간을 계산한다 (stage-breakdown.csv).
# 4. 그 Market 설정에서 MP (배치, 병렬도) = (100,3) (200,3) (200,6) (200,12) 각 ROUNDS회 + 구간별 시간
# 끝(정상·오류 모두): 커넥터 설정(sweep-market이 저장한 원본), 요청 토픽 파티션 3, 앱 기본 설정으로 되돌린다.
set -euo pipefail

S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$S/../../../reports/bench/settlement"
CSV="$OUT/sweep-combo-results.csv"
STAGES="$OUT/stage-breakdown.csv"
SUMMARY="$OUT/sweep-combo-summary.txt"
LOG="$OUT/sweep-combo.log"
ROUNDS="${SWEEP_ROUNDS:-3}"
WARMUP_N="${SWEEP_WARMUP_N:-1000}"
COMBOS="${SWEEP_COMBOS:-100:3 200:3 200:6 200:12}"
CONNECT="localhost:18083"
CONNECTORS="market-outbox memberpoint-outbox"
BS="--bootstrap-server localhost:29092"
REQ_TOPICS="market.settlement-payout-requests market.refund-requests"
k() { MSYS_NO_PATHCONV=1 docker exec kafka "/opt/kafka/bin/$@"; }
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

put_config() {
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -H 'Content-Type: application/json' \
    "$CONNECT/connectors/$1/config" --data-binary "$2")
  [ "$code" = 200 ] || { log "PUT $1 config failed: $code"; return 1; }
}

set_poll() {
  for c in $CONNECTORS; do
    cfg=$(sed -E 's/"poll\.interval\.ms":"[0-9]+",?//; s/,\}$/}/' "$OUT/connector-$c-original.json")
    put_config "$c" "$(echo "$cfg" | sed -E "s/^\{/{\"poll.interval.ms\":\"$1\",/")"
  done
  sleep 3; wait_connectors
  log "debezium poll.interval.ms=$1"
}

partitions_of() { k kafka-topics.sh $BS --describe --topic "$1" 2>/dev/null | grep -oE 'PartitionCount: [0-9]+' | awk '{print $2}'; }

set_partitions() {
  local n="$1" changed=0
  for t in $REQ_TOPICS; do
    [ "$(partitions_of "$t")" = "$n" ] && continue
    changed=1
    k kafka-consumer-groups.sh $BS --delete-offsets --group member-point-service --topic "$t" > /dev/null 2>&1 || true
    for attempt in 1 2 3 4 5; do
      k kafka-topics.sh $BS --delete --topic "$t" > /dev/null 2>&1 || true
      for _ in $(seq 1 60); do k kafka-topics.sh $BS --list 2>/dev/null | grep -qx "$t" || break; sleep 1; done
      k kafka-topics.sh $BS --create --topic "$t" --partitions "$n" --replication-factor 1 > /dev/null 2>&1 || true
      [ "$(partitions_of "$t")" = "$n" ] && break
    done
    [ "$(partitions_of "$t")" = "$n" ] || { log "cannot set $t to $n partitions"; return 1; }
    log "topic $t → partitions $n"
  done
  if [ "$changed" = 1 ]; then docker restart kafka-connect > /dev/null; sleep 5; wait_connectors; fi
}

restore_all() {
  set +e
  log "restore: connectors original, partitions 3, apps default"
  "$S/apps.sh" stop > /dev/null
  set_partitions 3
  for c in $CONNECTORS; do
    [ -f "$OUT/connector-$c-original.json" ] && put_config "$c" "$(cat "$OUT/connector-$c-original.json")"
  done
  sleep 3; wait_connectors
  "$S/apps.sh" start async member_id > /dev/null
  docker exec -e MYSQL_PWD=memberpoint-root-local-password member-point-mysql mysql -uroot -e \
    "UPDATE performance_schema.setup_instruments SET ENABLED='NO', TIMED='NO' WHERE NAME IN ('wait/synch/mutex/innodb/log_files_mutex','wait/synch/mutex/innodb/log_writer_mutex');" 2> /dev/null
  log "=== restored. done"
}

summarize() { # summarize <metrics csv> <stage csv>
  awk -F, 'FNR == 1 { file++; next }
    file == 1 { k=$2; md[k]=md[k]" "$5; mp[k]=mp[k]" "$4; p95[k]=p95[k]" "$6; mx[k]=mx[k]" "$7; if ($14 != "PASS") bad[k]=1; seen[k]=1 }
    file == 2 { k=$2; cdc[k]=cdc[k]" "$8; span[k]=span[k]" "$9; tail[k]=tail[k]" "$10 }
    function med(s,   a,n,i,j,t) { n=split(s,a," "); if (n == 0) return "-"; for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if (a[j]+0 < a[i]+0) { t=a[i]; a[i]=a[j]; a[j]=t }; return a[int((n+1)/2)] }
    END { for (k in seen) { ok = (!bad[k] && med(p95[k]) <= 85 && med(mx[k]) <= 300) ? "OK" : "OUT";
            printf "%s market_done=%s mp_done=%s p95=%s spend_max=%s cdc_first=%s mp_span=%s market_tail=%s guard=%s\n",
                   k, med(md[k]), med(mp[k]), med(p95[k]), med(mx[k]), med(cdc[k]), med(span[k]), med(tail[k]), ok } }' "$1" "$2" | sort
}

# 1. 앞 실험 종료 대기 (최대 3시간)
for _ in $(seq 1 1080); do grep -q "=== restored. done" "$OUT/sweep-market.log" 2>/dev/null && break; sleep 10; done
grep -q "=== restored. done" "$OUT/sweep-market.log" || { log "sweep-market did not finish"; exit 1; }
log "=== sweep-combo start (sweep-market finished)"
for c in $CONNECTORS; do [ -f "$OUT/connector-$c-original.json" ] || { log "missing original connector config"; exit 1; }; done
trap restore_all EXIT

# 2. Market 최적 설정
best="${SWEEP_MARKET_BEST:-$(awk '/guard=OK/ { split($2,a,"="); print a[2], $1 }' "$OUT/sweep-market-summary.txt" 2>/dev/null | sort -n | head -1 | awk '{print $2}')}"
mk_batch=$(echo "${best:-mk100-poll500}" | sed -E 's/^mk([0-9]+)-poll([0-9]+)$/\1/')
poll=$(echo "${best:-mk100-poll500}" | sed -E 's/^mk([0-9]+)-poll([0-9]+)$/\2/')
log "market setting: batch=$mk_batch poll=$poll (from ${best:-default})"

# 3. 앞 실험 run의 구간별 시간
"$S/stage-breakdown.sh" --header > "$STAGES"
for r in $(awk -F, 'NR > 1 {print $1}' "$OUT/sweep-market-results.csv" 2>/dev/null); do
  label=$(awk -F, -v r="$r" '$1 == r {print $2}' "$OUT/sweep-market-results.csv")
  "$S/stage-breakdown.sh" "$r" "$label" >> "$STAGES" || log "stage breakdown failed for run $r"
done
log "stage breakdown for sweep-market runs written"

# run 번호: 이어서 쓰되 40을 넘으면 실험 데이터 정리 후 1부터
run=$(awk -F, 'NR > 1 {r=$5} END {print r+0}' "$OUT/results.csv")
need=$(( $(echo "$COMBOS" | wc -w) * (ROUNDS + 1) ))
if [ $((run + need)) -gt 40 ]; then "$S/cleanup.sh" runs > /dev/null; run=0; log "cleanup runs (run numbers reset)"; fi

# 4. Market 최적 설정 + MP 조합
[ "$poll" = 500 ] || set_poll "$poll"
rm -f "$CSV"
for combo in $COMBOS; do
  mp_batch="${combo%%:*}"; par="${combo##*:}"; label="mk$mk_batch-poll$poll-mp$mp_batch-p$par"
  "$S/apps.sh" stop > /dev/null
  set_partitions "$par"
  MP_APP_ARGS="--spring.kafka.consumer.max-poll-records=$mp_batch --spring.kafka.listener.concurrency=$par --member-point.kafka.market-point-requests.pool-size=$((par * 2))" \
  MARKET_APP_ARGS="--spring.kafka.consumer.max-poll-records=$mk_batch" \
    "$S/apps.sh" start async member_id > /dev/null
  run=$((run + 1)); "$S/run.sh" "$run" "$WARMUP_N" 50 "warmup-$label" > /dev/null
  for _ in $(seq 1 "$ROUNDS"); do
    run=$((run + 1))
    line=$(RUN_METRICS_CSV="$CSV" "$S/redo-run.sh" "$run" "$label" | tail -1)
    "$S/stage-breakdown.sh" "$run" "$label" >> "$STAGES" || log "stage breakdown failed for run $run"
    log "$label run=$run $line | stages $(tail -1 "$STAGES" | cut -d, -f8-10)"
    case "$line" in *,PASS) ;; *) log "VERIFY FAIL or bad result → stop"; exit 1 ;; esac
  done
done

{ echo "# sweep-market"; summarize "$OUT/sweep-market-results.csv" "$STAGES"; echo "# sweep-combo"; summarize "$CSV" "$STAGES"; } > "$SUMMARY"
log "=== summary"; tee -a "$LOG" < "$SUMMARY"
