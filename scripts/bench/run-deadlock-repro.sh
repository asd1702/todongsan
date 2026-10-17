#!/usr/bin/env bash
# 동일 Market 예측 참여 deadlock 증거 수집용 실행기.
# 성능 benchmark(S/A/B/C/D/E)와 별개이며, debug phase trace를 의도적으로 활성화한다.
#
# 사전 준비(컨테이너 command 변경 후 반드시 재생성):
#   docker compose -f infra/docker-compose.test.yml up -d --force-recreate market-mysql-it
#
# Windows PowerShell:
#   & 'C:\Program Files\Git\bin\bash.exe' scripts/bench/run-deadlock-repro.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/market-service"

STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_LABEL="${DEADLOCK_REPRO_LABEL:-deadlock}"
OUT="$ROOT/reports/bench/${STAMP}-${RUN_LABEL}"
mkdir -p "$OUT"
RUN_STARTED="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

REQUESTS="${DEADLOCK_REPRO_REQUESTS:-100}"
POOL="${DEADLOCK_REPRO_POOL:-60}"
CONCURRENCIES="${DEADLOCK_REPRO_CONCURRENCIES:-2 3 5 10}"
MYSQL_ROOT_PASSWORD="${MARKET_IT_MYSQL_ROOT_PASSWORD:-market-test-root-local-password}"
MYSQL_TEST_USER="${MARKET_IT_DB_USERNAME:-market_test_user}"

# MySQL 8.4 does not expose SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks'.
# The performance_schema fallback is granted only to the disposable IT account.
docker exec market-mysql-it mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "GRANT SELECT ON performance_schema.events_errors_summary_global_by_error TO '$MYSQL_TEST_USER'@'%';"

run() { # run <concurrency>
  local concurrency="$1"
  local label="c${concurrency}"
  local log="$OUT/${label}.log"

  echo "==== deadlock reproduction: C=$concurrency, requests=$REQUESTS ===="
  set +e
  ./gradlew test --tests "*MarketPredictionLatencyBenchmarkTest" \
    -Dbench.enabled=true \
    -Dbench.deadlockDebug=true \
    -Dbench.confirmMode=sync \
    -Dbench.mode=closed \
    -Dbench.concurrency="$concurrency" \
    -Dbench.requests="$REQUESTS" \
    -Dbench.warmup=0 \
    -Dbench.rounds=1 \
    -Dbench.markets=1 \
    -Dbench.pool="$POOL" \
    -Dbench.spendDelayMs=0 \
    --rerun-tasks > "$log" 2>&1
  local gradle_status="$?"
  set -e

  grep -aE "\[round|Deadlock debug:|Failure classification:|DB delta:|deadlocks=|M1-service" "$log" \
    > "$OUT/${label}-summary.log" || true
  grep -a "DEADLOCK_PHASE" "$log" > "$OUT/${label}-phase-trace.log" || true
  docker exec market-mysql-it mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
    -e "SHOW ENGINE INNODB STATUS\\G" > "$OUT/${label}-innodb-status.txt"

  cat "$OUT/${label}-summary.log"

  if [[ "$gradle_status" -ne 0 ]]; then
    echo "Gradle failed for $label; see $log" >&2
    return "$gradle_status"
  fi
}

{
  echo "# Market Prediction Deadlock Reproduction"
  echo "- started epoch: $RUN_STARTED"
  echo "- requests per concurrency: $REQUESTS"
  echo "- hikari pool: $POOL"
  echo "- market count: 1"
  echo "- member-point: fake, spend delay: 0ms"
  echo "- debug tracing: enabled only for this runner"
} > "$OUT/README.md"

for concurrency in $CONCURRENCIES; do
  run "$concurrency"
done

docker logs --since "$RUN_STARTED" market-mysql-it > "$OUT/mysql-deadlocks.log" 2>&1 || true

echo ""
echo "완료. 증거 파일: $OUT"
echo "- c*-summary.log: success/failure, app failure category, InnoDB deltas"
echo "- c*-innodb-status.txt: latest detected deadlock"
echo "- c*-phase-trace.log: requestId-correlated application phases"
echo "- mysql-deadlocks.log: MySQL innodb-print-all-deadlocks output"
