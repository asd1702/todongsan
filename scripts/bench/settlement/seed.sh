#!/usr/bin/env bash
# 정산 sync/async 비교 실험 데이터 생성 (docs/architecture/ASYNC_POINT_FLOW.md §15.2)
#
#   ./scripts/bench/settlement/seed.sh members [M=15000]         # MP 실험 회원 풀 (1회, 재실행 안전)
#   ./scripts/bench/settlement/seed.sh market <run> <N> [ratio]  # run용 CLOSED 정산 마켓 (승자 N, 패자 N×ratio)
#
# ID 대역: ids.sh
# 예측 금액 = 100 + (i % 10) × 10, 계약 수량 = 금액 ÷ 0.5
set -euo pipefail
export MSYS_NO_PATHCONV=1

. "$(dirname "${BASH_SOURCE[0]}")/ids.sh"
INITIAL_BALANCE="1000000.00"
FEE_RATE="5.00"

mq() { docker exec -i market-mysql mysql -uroot -p"${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}" market -N "$@" 2>/dev/null; }
pq() { docker exec -i member-point-mysql mysql -uroot -p"${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" memberpoint -N "$@" 2>/dev/null; }

seed_members() {
  local m="${1:-$MEMBER_POOL_MAX}"
  if [ "$m" -lt 1 ] || [ "$m" -gt "$MEMBER_POOL_MAX" ]; then
    echo "M은 1~$MEMBER_POOL_MAX" >&2; exit 1
  fi
  pq <<SQL
SET SESSION cte_max_recursion_depth = 20000;
INSERT IGNORE INTO member (id, nickname, oauth_provider, oauth_id, point_balance)
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < $m)
SELECT $MEMBER_BASE + n, CONCAT('bench-', n), 'BENCH', CONCAT('bench-', n), $INITIAL_BALANCE FROM s;
SQL
  echo "bench members: $(pq -e "SELECT COUNT(*) FROM member WHERE id BETWEEN $((MEMBER_BASE + 1)) AND $((MEMBER_BASE + MEMBER_POOL_MAX));")"
}

seed_market() {
  local run="${1:?run 번호 필요}" n="${2:?승자 수 N 필요}" ratio="${3:-0.5}"
  local losers total market_id option_a option_b pid_base pool
  losers=$(awk -v n="$n" -v r="$ratio" 'BEGIN {printf "%d", n * r}')
  total=$((n + losers))
  market_id=$((MARKET_BASE + run))
  option_a=$((OPTION_BASE + run * 2))
  option_b=$((option_a + 1))
  pid_base=$(pred_lo "$run")

  if [ "$run" -lt 1 ] || [ "$run" -gt "$RUN_MAX" ]; then echo "run은 1~$RUN_MAX" >&2; exit 1; fi
  if [ "$total" -ge "$PREDICTION_STRIDE" ]; then echo "승자+패자는 $((PREDICTION_STRIDE - 1)) 이하" >&2; exit 1; fi
  pool=$(pq -e "SELECT COUNT(*) FROM member WHERE id BETWEEN $((MEMBER_BASE + 1)) AND $((MEMBER_BASE + total));")
  if [ "$pool" -lt "$total" ]; then echo "회원 풀 부족: 필요 $total, 존재 $pool (seed.sh members 먼저)" >&2; exit 1; fi
  if [ "$(mq -e "SELECT COUNT(*) FROM market WHERE id = $market_id;")" -ne 0 ]; then
    echo "market $market_id 이미 존재 (cleanup.sh run $run 먼저)" >&2; exit 1
  fi

  # 매 run 조건을 같게: 실험 회원 잔액 초기화
  pq -e "UPDATE member SET point_balance = $INITIAL_BALANCE WHERE id BETWEEN $((MEMBER_BASE + 1)) AND $((MEMBER_BASE + MEMBER_POOL_MAX));"

  mq <<SQL
SET SESSION cte_max_recursion_depth = 20000;
INSERT INTO market (id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, result_option_id, total_pool, fee_rate, fee_amount, settlement_pool,
                    initial_virtual_liquidity, created_by, created_at, updated_at)
VALUES ($market_id, '[BENCH] settlement run $run (N=$n)', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'BENCH', 'BENCH', CURDATE(),
        'CLOSED', NOW() - INTERVAL 1 DAY, $option_a, 0.00, $FEE_RATE, 0.00, 0.00, 200.00, 1, NOW(), NOW());
INSERT INTO market_option (id, market_id, option_code, option_text, display_order, virtual_pool_amount, real_pool_amount,
                           total_contract_quantity, current_price, prediction_count, is_result, created_at, updated_at)
VALUES ($option_a, $market_id, 'A', 'A', 1, 100.00, 0.00, 0.00000000, 0.50000000, $n, TRUE, NOW(), NOW()),
       ($option_b, $market_id, 'B', 'B', 2, 100.00, 0.00, 0.00000000, 0.50000000, $losers, FALSE, NOW(), NOW());
INSERT INTO market_prediction (id, market_id, option_id, member_id, point_amount, price_snapshot, contract_quantity,
                               status, point_spend_idempotency_key, attempt_no, created_at, updated_at)
WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < $total)
SELECT $pid_base + i, $market_id, CASE WHEN i <= $n THEN $option_a ELSE $option_b END, $MEMBER_BASE + i,
       100 + (i % 10) * 10, 0.50000000, (100 + (i % 10) * 10) * 2,
       'CONFIRMED', CONCAT('BENCH_SPEND:run:', $run, ':i:', i), 1, NOW(), NOW()
FROM s;
UPDATE market SET total_pool = (SELECT SUM(point_amount) FROM market_prediction WHERE market_id = $market_id)
WHERE id = $market_id;
SQL

  mq -e "SELECT CONCAT('market=', $market_id, ' winners=', SUM(option_id = $option_a), ' losers=', SUM(option_id = $option_b),
                      ' total_pool=', SUM(point_amount))
         FROM market_prediction WHERE market_id = $market_id;"
}

case "${1:-}" in
  members) seed_members "${2:-}" ;;
  market) shift; seed_market "$@" ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
