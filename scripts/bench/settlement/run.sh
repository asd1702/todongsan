#!/usr/bin/env bash
# 정산 실험 1회 실행 (docs/architecture/ASYNC_POINT_FLOW.md §15.2)
#
#   ./scripts/bench/settlement/run.sh <run> <N> [rate=50] [label]
#
# 전제: apps.sh start <mode> [key]로 앱이 떠 있고, run 번호가 비어 있다.
# 순서: health 확인 → seed → 락 지표(전) → DB 폴러 시작 → k6(load + settle) → 폴러 완료 대기
#       → 락 지표(후) → verify.sh → Analyze.java → results.csv 한 줄 추가
#
# 시각은 모두 Docker VM 시계 기준이다 (k6 컨테이너와 DB 컨테이너 안 폴러가 같은 커널 시계를 쓴다).
set -euo pipefail
export MSYS_NO_PATHCONV=1

run="${1:?run 번호}"; n="${2:?승자 수 N}"; rate="${3:-50}"; label="${4:-}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../../.." && pwd)"
OUT="$ROOT/reports/bench/settlement"
STATE="$OUT/apps.state"
RESULTS="$OUT/results.csv"
PRE="5s"
DURATION="${BENCH_K6_DURATION:-60s}"
POLL_TIMEOUT_SEC="${BENCH_POLL_TIMEOUT_SEC:-300}"
MARKET_PW="${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}"
MP_PW="${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}"

[ -f "$STATE" ] || { echo "앱 상태 없음: apps.sh start 먼저" >&2; exit 1; }
# shellcheck disable=SC1090
. "$STATE"
market_id=$((800000 + run))
pid_lo=$((500000 + run * 10000)); pid_hi=$((pid_lo + 9999))
RUN_DIR="$OUT/runs/$(date +%Y%m%d-%H%M%S)-run$run-$MODE-$KEY-n$n"
mkdir -p "$RUN_DIR"

mysql_q() { # mysql_q <container> <password> <db> <sql>
  docker exec -i -e MYSQL_PWD="$2" "$1" mysql -uroot -N "$3" -e "$4" 2>/dev/null
}
lock_stats() { # lock_stats <container> <password> → "waits time_ms deadlocks commits"
  mysql_q "$1" "$2" information_schema "
    SELECT (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_row_lock_waits'),
           (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_row_lock_time'),
           (SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'),
           (SELECT COUNT_STAR FROM performance_schema.events_statements_summary_global_by_event_name
             WHERE EVENT_NAME = 'statement/sql/commit');" | tr '\t' ' '
}
poll() { # poll <container> <password> <db> <sql: 조건 충족 시 1> → 완료 시각(epoch ms, VM 시계) 또는 timeout
  docker exec -i -e MYSQL_PWD="$2" "$1" sh -s <<EOF
deadline=\$((\$(date +%s) + $POLL_TIMEOUT_SEC))
while [ \$(date +%s) -lt \$deadline ]; do
  if [ "\$(mysql -uroot -N $3 -e "$4" 2>/dev/null)" = "1" ]; then date +%s%3N; exit 0; fi
  sleep 0.05
done
echo timeout
EOF
}

echo "== run=$run mode=$MODE key=$KEY N=$n rate=$rate ${label:+label=$label}"
"$ROOT/scripts/cdc/health.sh" > "$RUN_DIR/health-before.txt" || { cat "$RUN_DIR/health-before.txt"; echo "파이프라인 점검 실패" >&2; exit 1; }
"$DIR/seed.sh" market "$run" "$n" | tee "$RUN_DIR/seed.txt"
losers=$(awk -F'losers=' '{split($2, a, " "); print a[1]}' "$RUN_DIR/seed.txt")

read -r mp_w0 mp_t0 mp_d0 mp_c0 <<< "$(lock_stats member-point-mysql "$MP_PW")"
read -r mk_w0 mk_t0 mk_d0 mk_c0 <<< "$(lock_stats market-mysql "$MARKET_PW")"

poll member-point-mysql "$MP_PW" memberpoint \
  "SELECT COUNT(*) >= $n FROM point_history WHERE type = 'SETTLE_MARKET' AND reference_type = 'MARKET_PREDICTION' AND reference_id BETWEEN $pid_lo AND $pid_hi" \
  > "$RUN_DIR/mp_done" &
mp_poller=$!
poll market-mysql "$MARKET_PW" market "SELECT status = 'SETTLED' FROM market WHERE id = $market_id" > "$RUN_DIR/market_done" &
market_poller=$!

docker run --rm --name "bench-k6-run$run" \
  -v "$(cygpath -m "$DIR/k6"):/scripts:ro" -v "$(cygpath -m "$RUN_DIR"):/out" \
  grafana/k6:latest run --quiet --out json=/out/k6.json \
  -e RUN="$run" -e N="$n" -e RATE="$rate" -e DURATION="$DURATION" -e PRE="$PRE" \
  /scripts/settlement.js > "$RUN_DIR/k6.log" 2>&1 || echo "k6 exit $? (log: $RUN_DIR/k6.log)"

wait "$mp_poller" "$market_poller" || true
mp_done="$(tr -d '\r\n' < "$RUN_DIR/mp_done")"; market_done="$(tr -d '\r\n' < "$RUN_DIR/market_done")"

read -r mp_w1 mp_t1 mp_d1 mp_c1 <<< "$(lock_stats member-point-mysql "$MP_PW")"
read -r mk_w1 mk_t1 mk_d1 mk_c1 <<< "$(lock_stats market-mysql "$MARKET_PW")"

if "$DIR/verify.sh" "$run" > "$RUN_DIR/verify.txt"; then verify=PASS; else verify=FAIL; fi
java "$(cygpath -m "$DIR/Analyze.java")" "$(cygpath -m "$RUN_DIR/k6.json")" "${mp_done:-timeout}" "${market_done:-timeout}" > "$RUN_DIR/analysis.txt"
settle_status="$(grep -oE 'settlementStatus[^A-Z]*[A-Z_]+' "$RUN_DIR/k6.log" | head -1 | grep -oE '[A-Z_]+$' || echo NA)"
cat "$RUN_DIR/analysis.txt"

a() { grep -E "^$1=" "$RUN_DIR/analysis.txt" | cut -d= -f2 || echo NA; }
if [ ! -f "$RESULTS" ]; then
  echo "timestamp,label,mode,key,run,n,losers,rate,settle_status,settle_api_ms,mp_done_ms,market_done_ms,before_count,before_p50,before_p95,before_p99,during_count,during_p50,during_p95,during_p99,during_max,during_failed,load_truncated,mp_lock_waits,mp_lock_time_ms,mp_deadlocks,market_lock_waits,market_lock_time_ms,market_deadlocks,mp_commits,market_commits,verify,run_dir" > "$RESULTS"
fi
echo "$(date '+%Y-%m-%dT%H:%M:%S'),$label,$MODE,$KEY,$run,$n,$losers,$rate,$settle_status,$(a settle_api_ms),$(a mp_done_ms),$(a market_done_ms),$(a before_count),$(a before_p50),$(a before_p95),$(a before_p99),$(a during_count),$(a during_p50),$(a during_p95),$(a during_p99),$(a during_max),$(a during_failed),$(a load_truncated),$((mp_w1 - mp_w0)),$((mp_t1 - mp_t0)),$((mp_d1 - mp_d0)),$((mk_w1 - mk_w0)),$((mk_t1 - mk_t0)),$((mk_d1 - mk_d0)),$((mp_c1 - mp_c0)),$((mk_c1 - mk_c0)),$verify,$(basename "$RUN_DIR")" >> "$RESULTS"
echo "verify=$verify settle_status=$settle_status → $RESULTS"
