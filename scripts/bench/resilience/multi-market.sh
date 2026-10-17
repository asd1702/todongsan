#!/usr/bin/env bash
# 시스템 전체 예측 처리량: 총 rate/s를 마켓 M개에 나눠 60초 (docs/architecture/HOT_MARKET_ADMISSION.md §5)
#   ./scripts/bench/resilience/multi-market.sh <run> <markets> <rate>
# 마켓 ID = 900000 + run*100 + j (j < 100), 선택지 = 910000 + (run*100 + j)*2. 매번 새 run 번호를 쓴다(MP 멱등성 키 충돌 방지).
set -euo pipefail
run="${1:?run}"; markets="${2:?markets}"; rate="${3:?rate}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$DIR/../../../reports/bench/resilience/$run-m$markets-r$rate"
mkdir -p "$OUT"
. "$DIR/../settlement/ids.sh"
base=$((900000 + run * 100)); obase=$((910000 + run * 200))
mq() { MSYS_NO_PATHCONV=1 docker exec -i -e MYSQL_PWD="${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}" market-mysql mysql -uroot -N market "$@" 2>/dev/null; }
mpq() { MSYS_NO_PATHCONV=1 docker exec -i -e MYSQL_PWD="${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" member-point-mysql mysql -uroot -N memberpoint "$@" 2>/dev/null; }
mpq -e "UPDATE member SET point_balance = 1000000.00 WHERE id BETWEEN $((MEMBER_BASE + 1)) AND $((MEMBER_BASE + MEMBER_POOL_MAX));"
{
  for j in $(seq 0 $((markets - 1))); do
    m=$((base + j)); o=$((obase + j * 2))
    echo "INSERT INTO market (id, title, category, answer_type, judge_data_source, judge_criteria, judge_date, status, close_at,
            total_pool, fee_rate, fee_amount, settlement_pool, initial_virtual_liquidity, created_by, created_at, updated_at)
          VALUES ($m, '[BENCH] multi $run-$j', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'BENCH', 'BENCH', CURDATE() + INTERVAL 2 DAY,
            'ACTIVE', NOW() + INTERVAL 1 DAY, 0.00, 5.00, 0.00, 0.00, 200.00, 1, NOW(), NOW());"
    echo "INSERT INTO market_option (id, market_id, option_code, option_text, display_order, virtual_pool_amount, real_pool_amount,
            total_contract_quantity, current_price, prediction_count, is_result, created_at, updated_at)
          VALUES ($o, $m, 'A', 'A', 1, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, NOW(), NOW()),
                 ($((o + 1)), $m, 'B', 'B', 2, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, NOW(), NOW());"
  done
} | mq

metric() { curl -s -m 1 "localhost:8082/actuator/metrics/$1" | grep -oE '"value":[0-9.]+' | head -1 | cut -d: -f2 || true; }
( while true; do
    echo "$(date +%s%3N),$(metric tomcat.threads.busy),$(metric hikaricp.connections.active),$(metric hikaricp.connections.pending)"; sleep 1
  done ) > "$OUT/threads.csv" &
sampler=$!
trap 'kill $sampler 2>/dev/null || true' EXIT

MSYS_NO_PATHCONV=1 docker run --rm --name "multi-market-$run" -v "$(cygpath -m "$DIR/k6"):/scripts:ro" -v "$(cygpath -m "$OUT"):/out" \
  grafana/k6:latest run --quiet --out json=/out/k6.json -e MARKET_BASE="$base" -e OPTION_BASE="$obase" \
  -e MARKET_COUNT="$markets" -e MEMBER_BASE="$MEMBER_BASE" -e RATE="$rate" /scripts/multi-market.js > "$OUT/k6.log" 2>&1 || echo "k6 exit $?"

java -Dstdout.encoding=UTF-8 "$(cygpath -m "$DIR/StallAnalyze.java")" "$(cygpath -m "$OUT/k6.json")" "$(cygpath -m "$OUT/threads.csv")" 0 0 \
  > "$OUT/analysis.txt"
grep SUMMARY "$OUT/analysis.txt"
