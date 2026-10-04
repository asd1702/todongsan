#!/usr/bin/env bash
# MP 차감 지연이 Market 전체(포인트와 무관한 API까지)로 번지는지 재현 (docs/architecture/SPEND_CIRCUIT_BREAKER.md)
#
#   MARKET_APP_ARGS="--management.endpoints.web.exposure.include=health,metrics --server.tomcat.mbeanregistry.enabled=true" \
#     ./scripts/bench/settlement/apps.sh start async member_id      # 톰캣 스레드 지표를 켜고 띄운다
#   ./scripts/bench/resilience/spend-stall.sh <run> <stall_sec> [label] [rate=50]
#
# 0초부터 120초 동안 인기 마켓 1개에 예측 참여(rate/s)와 마켓 상세 조회(10/s)를 보내고,
# 30초부터 stall_sec 동안 MP DB 쓰기를 막는다(FLUSH TABLES WITH READ LOCK, 응답 없이 멈춘 MP 재현). 0이면 기준 run.
# 결과: reports/bench/resilience/<run>-<label>/ (k6.json, threads.csv, analysis.txt)
set -euo pipefail
run="${1:?run}"; stall="${2:?stall_sec}"; label="${3:-stall$stall}"; rate="${4:-50}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$DIR/../../../reports/bench/resilience/$run-$label"
mkdir -p "$OUT"
. "$DIR/../settlement/ids.sh"
market_id=$((890000 + run)); opt_a=$((891000 + run * 2)); opt_b=$((opt_a + 1))
mq() { MSYS_NO_PATHCONV=1 docker exec -i -e MYSQL_PWD="${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}" market-mysql mysql -uroot -N market "$@" 2>/dev/null; }
mpq() { MSYS_NO_PATHCONV=1 docker exec -i -e MYSQL_PWD="${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" member-point-mysql mysql -uroot -N memberpoint "$@" 2>/dev/null; }

# 회원(잔액 충분)과 ACTIVE 마켓
mpq <<SQL
SET SESSION cte_max_recursion_depth = 20000;
INSERT IGNORE INTO member (id, nickname, oauth_provider, oauth_id, point_balance)
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < $MEMBER_POOL_MAX)
SELECT $MEMBER_BASE + n, CONCAT('bench-', n), 'BENCH', CONCAT('bench-', n), 1000000.00 FROM s;
UPDATE member SET point_balance = 1000000.00 WHERE id BETWEEN $((MEMBER_BASE + 1)) AND $((MEMBER_BASE + MEMBER_POOL_MAX));
SQL
mq <<SQL
DELETE FROM market_price_history WHERE market_id = $market_id;
DELETE FROM market_prediction WHERE market_id = $market_id;
DELETE FROM market_option WHERE market_id = $market_id;
DELETE FROM market WHERE id = $market_id;
INSERT INTO market (id, title, category, answer_type, judge_data_source, judge_criteria, judge_date, status, close_at,
                    total_pool, fee_rate, fee_amount, settlement_pool, initial_virtual_liquidity, created_by, created_at, updated_at)
VALUES ($market_id, '[BENCH] spend stall $run', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'BENCH', 'BENCH', CURDATE() + INTERVAL 2 DAY,
        'ACTIVE', NOW() + INTERVAL 1 DAY, 0.00, 5.00, 0.00, 0.00, 200.00, 1, NOW(), NOW());
INSERT INTO market_option (id, market_id, option_code, option_text, display_order, virtual_pool_amount, real_pool_amount,
                           total_contract_quantity, current_price, prediction_count, is_result, created_at, updated_at)
VALUES ($opt_a, $market_id, 'A', 'A', 1, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, NOW(), NOW()),
       ($opt_b, $market_id, 'B', 'B', 2, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, NOW(), NOW());
SQL

# 톰캣 사용 스레드 (1초마다, 호스트 시계 epoch ms)
# 톰캣 사용 스레드, DB 커넥션 사용 중·대기 (1초마다, 호스트 시계 epoch ms)
metric() { curl -s -m 1 "localhost:8082/actuator/metrics/$1" | grep -oE '"value":[0-9.]+' | head -1 | cut -d: -f2 || true; }
( while true; do
    echo "$(date +%s%3N),$(metric tomcat.threads.busy),$(metric hikaricp.connections.active),$(metric hikaricp.connections.pending)"; sleep 1
  done ) > "$OUT/threads.csv" &
sampler=$!
trap 'kill $sampler 2>/dev/null || true; mpq -e "UNLOCK TABLES;" > /dev/null || true' EXIT

MSYS_NO_PATHCONV=1 docker run --rm --name "spend-stall-$run" -v "$(cygpath -m "$DIR/k6"):/scripts:ro" -v "$(cygpath -m "$OUT"):/out" \
  grafana/k6:latest run --quiet --out json=/out/k6.json -e MARKET_ID="$market_id" -e OPTION_ID="$opt_a" \
  -e MEMBER_BASE="$MEMBER_BASE" -e RATE="$rate" /scripts/spend-stall.js > "$OUT/k6.log" 2>&1 &
k6=$!

sleep 30
stall_start=$(date +%s%3N)
if [ "$stall" -gt 0 ]; then
  mpq -e "FLUSH TABLES WITH READ LOCK; DO SLEEP($stall); UNLOCK TABLES;" > /dev/null
fi
stall_end=$(date +%s%3N)
wait "$k6" || echo "k6 exit $?"

java -Dstdout.encoding=UTF-8 "$(cygpath -m "$DIR/StallAnalyze.java")" "$(cygpath -m "$OUT/k6.json")" "$(cygpath -m "$OUT/threads.csv")" \
  "$stall_start" "$stall_end" | tee "$OUT/analysis.txt"
echo "DB 예측 상태: $(mq -e "SELECT GROUP_CONCAT(CONCAT(status,'=',c)) FROM (SELECT status, COUNT(*) c FROM market_prediction WHERE market_id = $market_id GROUP BY status) t;")" \
  | tee -a "$OUT/analysis.txt"
# 포기한(UNKNOWN) 요청 중 MP가 실제로 차감한 수 (SPEND_CIRCUIT_BREAKER.md §1)
spent=$(mpq -e "SELECT COUNT(*) FROM point_history WHERE idempotency_key LIKE 'MARKET_PREDICTION_SPEND:market:$market_id:%';")
confirmed=$(mq -e "SELECT COUNT(*) FROM market_prediction WHERE market_id = $market_id AND status = 'CONFIRMED';")
echo "MP 차감 이력=$spent, Market CONFIRMED=$confirmed, 차감됐지만 확정 안 됨=$((spent - confirmed))" | tee -a "$OUT/analysis.txt"
