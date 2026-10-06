#!/usr/bin/env bash
# 예측 확정 트랜잭션(TX2) 시간 분해 (docs/architecture/HOT_MARKET_ADMISSION.md §7)
#   ./scripts/bench/resilience/confirm-breakdown.sh <run> <rate>
# 인기 마켓 1개에 rate/s(spend-stall.sh, 장애 없음)를 주고, Market MySQL performance_schema 문장 이력에서
# 확정 트랜잭션마다 "마켓 행 잠금 문장 시작 → 같은 스레드의 다음 commit 끝"을 나눈다.
#   lock_wait : 마켓 행 잠금 대기 (잠금 문장의 LOCK_TIME, 8.0.28+는 InnoDB 행 잠금 대기 포함)
#   hold      : 잠금을 얻은 뒤 commit이 끝날 때까지 = 다른 예측이 이 마켓을 못 쓰는 시간
#   stmts     : hold 안 SQL 실행 시간 합 (commit 제외), gap = hold - stmts - commit (앱 계산·왕복)
#   commit    : commit 문장 시간 (redo·binlog fsync)
# 문장 이력은 최근 10,000개만 남으므로 부하가 끝난 직후의 마지막 구간을 본다.
set -euo pipefail
run="${1:?run}"; rate="${2:?rate}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$DIR/../../../reports/bench/resilience/$run-confirm-r$rate"
mkdir -p "$OUT"
mq() { MSYS_NO_PATHCONV=1 docker exec -i -e MYSQL_PWD="${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}" market-mysql mysql -uroot -N "$@" 2>/dev/null; }

mq -e "UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME IN ('events_statements_history_long');
       TRUNCATE TABLE performance_schema.events_statements_history_long;"
"$DIR/spend-stall.sh" "$run" 0 "confirm-r$rate" "$rate" > "$OUT/load.txt" 2>&1 || true

mq <<'SQL' > "$OUT/tx.tsv"
WITH h AS (
  SELECT THREAD_ID, EVENT_ID, TIMER_START, TIMER_END, TIMER_WAIT, LOCK_TIME, SQL_TEXT
  FROM performance_schema.events_statements_history_long WHERE CURRENT_SCHEMA = 'market'
), l AS (
  SELECT * FROM h WHERE SQL_TEXT REGEXP 'FROM[[:space:]]+market[[:space:]]+WHERE[[:space:]]+id[[:space:]]*=' AND SQL_TEXT LIKE '%FOR UPDATE%'
), c AS (
  SELECT l.THREAD_ID, l.EVENT_ID lock_id,
         (SELECT MIN(x.EVENT_ID) FROM h x WHERE x.THREAD_ID = l.THREAD_ID AND x.EVENT_ID > l.EVENT_ID AND LOWER(x.SQL_TEXT) = 'commit') commit_id
  FROM l
)
SELECT ROUND(l.LOCK_TIME / 1e9, 3) lock_wait_ms,
       ROUND((k.TIMER_END - l.TIMER_END) / 1e9, 3) hold_ms,
       ROUND((SELECT SUM(x.TIMER_WAIT) FROM h x WHERE x.THREAD_ID = l.THREAD_ID AND x.EVENT_ID > l.EVENT_ID AND x.EVENT_ID < k.EVENT_ID) / 1e9, 3) stmts_ms,
       (SELECT COUNT(*) FROM h x WHERE x.THREAD_ID = l.THREAD_ID AND x.EVENT_ID > l.EVENT_ID AND x.EVENT_ID < k.EVENT_ID) stmts,
       ROUND(k.TIMER_WAIT / 1e9, 3) commit_ms
FROM c JOIN l ON l.THREAD_ID = c.THREAD_ID AND l.EVENT_ID = c.lock_id
       JOIN h k ON k.THREAD_ID = c.THREAD_ID AND k.EVENT_ID = c.commit_id;
SQL

awk -F'\t' 'function pct(a,n,q){return a[(n*q>int(n*q))?int(n*q)+1:(int(n*q)<1?1:int(n*q))]}
  {n++; lw[n]=$1; ho[n]=$2; st[n]=$3; cm[n]=$5; gp[n]=$2-$3-$5; sc+=$4; slw+=$1; sho+=$2; sst+=$3; scm+=$5; sgp+=$2-$3-$5}
  END { if (n == 0) { print "no transactions"; exit 1 }
        printf "transactions=%d stmts_per_tx=%.1f\n", n, sc/n
        printf "avg  lock_wait=%.2fms hold=%.2fms (stmts=%.2f commit=%.2f gap=%.2f) -> max serial rate=%.0f/s\n", slw/n, sho/n, sst/n, scm/n, sgp/n, 1000/(sho/n)
        asort(lw); asort(ho); asort(st); asort(cm); asort(gp)
        printf "p50  lock_wait=%.2fms hold=%.2fms stmts=%.2f commit=%.2f gap=%.2f\n", pct(lw,n,.5), pct(ho,n,.5), pct(st,n,.5), pct(cm,n,.5), pct(gp,n,.5)
        printf "p95  lock_wait=%.2fms hold=%.2fms stmts=%.2f commit=%.2f gap=%.2f\n", pct(lw,n,.95), pct(ho,n,.95), pct(st,n,.95), pct(cm,n,.95), pct(gp,n,.95) }' \
  "$OUT/tx.tsv" | tee "$OUT/breakdown.txt"
grep SUMMARY "$OUT/../$run-confirm-r$rate/analysis.txt" | tee -a "$OUT/breakdown.txt" || true
