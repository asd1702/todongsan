#!/usr/bin/env bash
# MP 적립 배치 크기·병렬도 비교 자동 실행 (docs/architecture/ASYNC_POINT_FLOW.md §17.10)
#
#   ./scripts/bench/settlement/sweep.sh
#   SWEEP_BATCHES="50 100" SWEEP_PARALLELS="3 6" SWEEP_ROUNDS=2 ./scripts/bench/settlement/sweep.sh
#
# 1단계: 병렬도 3에서 배치 크기(max.poll.records)별 ROUNDS회
# 선택:  안전선(spend p95 중앙값 ≤ 85ms, spend max 중앙값 ≤ 300ms, 전부 verify PASS)을 넘은 것 중 ③ Market 완료 중앙값이 가장 짧은 배치 크기
# 2단계: 고른 배치 크기에서 병렬도별 ROUNDS회 (요청 토픽 파티션 수 = listener 동시성, consumer 풀 = 토픽 2 × 동시성)
# 끝:    원래 설정(배치 100, 병렬도 3, 파티션 3)으로 되돌린다. verify FAIL이나 오류가 나면 그 자리에서 멈춘다.
#
# 결과: reports/bench/settlement/sweep-results.csv (run마다), sweep-summary.txt (설정별 중앙값), sweep.log
set -euo pipefail
# MSYS_NO_PATHCONV는 전역으로 켜지 않는다 (apps.sh의 taskkill //F가 깨진다). Kafka 명령에만 붙인다.

S="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$S/../../../reports/bench/settlement"
CSV="$OUT/sweep-results.csv"
SUMMARY="$OUT/sweep-summary.txt"
LOG="$OUT/sweep.log"
BATCHES="${SWEEP_BATCHES:-50 100 200 500}"
PARALLELS="${SWEEP_PARALLELS:-3 6 12}"
ROUNDS="${SWEEP_ROUNDS:-3}"
k() { MSYS_NO_PATHCONV=1 docker exec kafka "/opt/kafka/bin/$@"; }
BS="--bootstrap-server localhost:29092"
REQ_TOPICS="market.settlement-payout-requests market.refund-requests"
run=0

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

partitions_of() { k kafka-topics.sh $BS --describe --topic "$1" 2>/dev/null | grep -oE 'PartitionCount: [0-9]+' | awk '{print $2}'; }

# 앱을 멈춘 상태에서 요청 토픽을 n개 파티션으로 다시 만든다 (파티션은 줄일 수 없으므로 삭제 후 생성). 로컬 토픽은 비어 있다.
set_partitions() {
  local n="$1" changed=0
  for t in $REQ_TOPICS; do
    [ "$(partitions_of "$t")" = "$n" ] && continue
    changed=1
    k kafka-consumer-groups.sh $BS --delete-offsets --group member-point-service --topic "$t" > /dev/null 2>&1 || true
    # 삭제 직후 Connect producer의 메타데이터 조회가 기본 파티션 수로 토픽을 다시 만들 수 있다 → 맞을 때까지 반복
    for attempt in 1 2 3 4 5; do
      k kafka-topics.sh $BS --delete --topic "$t" > /dev/null 2>&1 || true
      for _ in $(seq 1 60); do k kafka-topics.sh $BS --list 2>/dev/null | grep -qx "$t" || break; sleep 1; done
      k kafka-topics.sh $BS --create --topic "$t" --partitions "$n" --replication-factor 1 > /dev/null 2>&1 || true
      [ "$(partitions_of "$t")" = "$n" ] && break
      log "topic $t partitions=$(partitions_of "$t") (want $n), retry $attempt"
    done
    [ "$(partitions_of "$t")" = "$n" ] || { log "cannot set $t to $n partitions"; exit 1; }
    log "topic $t → partitions $n"
  done
  if [ "$changed" = 1 ]; then
    # Debezium producer가 바뀐 파티션 수를 확실히 보도록 Connect를 재시작한다
    docker restart kafka-connect > /dev/null
    for _ in $(seq 1 90); do
      ok=0
      for c in market-outbox memberpoint-outbox; do
        curl -s "localhost:18083/connectors/$c/status" 2>/dev/null | grep -q '"connector":{"state":"RUNNING"' && ok=$((ok + 1))
      done
      [ "$ok" = 2 ] && break; sleep 2
    done
    [ "$ok" = 2 ] || { log "connectors not RUNNING after restart"; exit 1; }
  fi
}

# 설정 1개: 앱 재기동 → warmup 1회 → ROUNDS회 측정
measure() {
  local batch="$1" par="$2" label="b$1-p$2"
  "$S/apps.sh" stop > /dev/null
  set_partitions "$par"
  MP_APP_ARGS="--spring.kafka.consumer.max-poll-records=$batch --spring.kafka.listener.concurrency=$par --member-point.kafka.market-point-requests.pool-size=$((par * 2))" \
    "$S/apps.sh" start async member_id > /dev/null
  run=$((run + 1)); "$S/run.sh" "$run" 10 50 "warmup-$label" > /dev/null
  for _ in $(seq 1 "$ROUNDS"); do
    run=$((run + 1))
    line=$(RUN_METRICS_CSV="$CSV" "$S/redo-run.sh" "$run" "$label" | tail -1)
    log "$label run=$run $line"
    case "$line" in *,PASS) ;; *) log "VERIFY FAIL or bad result → stop"; exit 1 ;; esac
  done
}

# label별 중앙값: market_done(5), mp_done(4), p95(6), spend_max(7), gap(8). 안전선 통과 여부 표시
summarize() {
  awk -F, 'NR > 1 { k=$2; md[k]=md[k]" "$5; mp[k]=mp[k]" "$4; p95[k]=p95[k]" "$6; mx[k]=mx[k]" "$7; gp[k]=gp[k]" "$8; if ($14 != "PASS") bad[k]=1; seen[k]=1 }
    function med(s,   a,n,i,j,t) { n=split(s,a," "); for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if (a[j]+0 < a[i]+0) { t=a[i]; a[i]=a[j]; a[j]=t }; return a[int((n+1)/2)] }
    END { for (k in seen) { ok = (!bad[k] && med(p95[k]) <= 85 && med(mx[k]) <= 300) ? "OK" : "OUT";
            printf "%s market_done=%s mp_done=%s p95=%s spend_max=%s gap=%s guard=%s\n", k, med(md[k]), med(mp[k]), med(p95[k]), med(mx[k]), med(gp[k]), ok } }' "$CSV" | sort
}

log "=== sweep start: batches=[$BATCHES] parallels=[$PARALLELS] rounds=$ROUNDS"
"$S/apps.sh" stop > /dev/null || true
"$S/apps.sh" build > /dev/null
"$S/cleanup.sh" runs > /dev/null
rm -f "$CSV"

for b in $BATCHES; do measure "$b" 3; done
best=$(summarize | awk '$1 ~ /-p3$/ && /guard=OK/ { split($2,a,"="); print a[2], $1 }' | sort -n | head -1 | awk '{print $2}' | sed -E 's/^b([0-9]+)-p3$/\1/')
best="${best:-100}"
log "chosen batch size: $best"

for p in $PARALLELS; do [ "$p" = 3 ] && continue; measure "$best" "$p"; done

summarize > "$SUMMARY"
log "=== summary"; tee -a "$LOG" < "$SUMMARY"

# 원래 설정으로 복구
"$S/apps.sh" stop > /dev/null
set_partitions 3
"$S/apps.sh" start async member_id > /dev/null
docker exec -e MYSQL_PWD=memberpoint-root-local-password member-point-mysql mysql -uroot -e \
  "UPDATE performance_schema.setup_instruments SET ENABLED='NO', TIMED='NO' WHERE NAME IN ('wait/synch/mutex/innodb/log_files_mutex','wait/synch/mutex/innodb/log_writer_mutex');" 2> /dev/null
log "=== restored default (batch 100, parallel 3, partitions 3). done"
