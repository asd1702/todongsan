#!/usr/bin/env bash
# 정산 실험 run의 정합성 검증 (docs/architecture/ASYNC_POINT_FLOW.md §15.2)
#
#   ./scripts/bench/settlement/verify.sh <run>
#
# 기대 지급액은 정산과 같은 공식으로 SQL에서 다시 계산해 비교한다.
#   fee = floor2(losingPool × feeRate / 100), rewardPool = losingPool - fee
#   payoutPerContract = floor8(rewardPool / Σ승자 계약 수량)
#   지급액 = floor2(원금 + 계약 수량 × payoutPerContract)
# 모든 항목이 맞으면 종료 코드 0.
set -uo pipefail
export MSYS_NO_PATHCONV=1

run="${1:?run 번호 필요}"
. "$(dirname "${BASH_SOURCE[0]}")/ids.sh"
market_id=$((MARKET_BASE + run))
pid_lo=$(pred_lo "$run")
pid_hi=$(pred_hi "$run")

mq() { docker exec -i market-mysql mysql -uroot -p"${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}" market -N "$@" 2>/dev/null; }
pq() { docker exec -i member-point-mysql mysql -uroot -p"${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" memberpoint -N "$@" 2>/dev/null; }

fail=0
check() { # check <이름> <기대> <실제>
  if [ "$2" = "$3" ]; then printf "  PASS  %-34s %s\n" "$1" "$3"; else printf "  FAIL  %-34s expected=%s actual=%s\n" "$1" "$2" "$3"; fail=1; fi
}

read -r winners losers result_option <<< "$(mq -e "
  SELECT SUM(p.option_id = m.result_option_id), SUM(p.option_id <> m.result_option_id), m.result_option_id
  FROM market m JOIN market_prediction p ON p.market_id = m.id WHERE m.id = $market_id GROUP BY m.result_option_id;")"
echo "run=$run market=$market_id winners=$winners losers=$losers"

check "market.status" "SETTLED" "$(mq -e "SELECT status FROM market WHERE id = $market_id;")"
check "market_settlement.status" "COMPLETED" "$(mq -e "SELECT status FROM market_settlement WHERE market_id = $market_id;")"
check "detail SUCCESS 건수" "$winners" "$(mq -e "
  SELECT COUNT(*) FROM market_settlement_detail d JOIN market_settlement s ON s.id = d.settlement_id
  WHERE s.market_id = $market_id AND d.status = 'SUCCESS';")"
check "지급액 = 기대값 건수" "$winners" "$(mq -e "
  WITH p AS (SELECT id, option_id, point_amount amt, contract_quantity qty FROM market_prediction WHERE market_id = $market_id),
       agg AS (SELECT SUM(amt) total, SUM(CASE WHEN option_id = $result_option THEN amt END) win,
                      SUM(CASE WHEN option_id = $result_option THEN qty END) wq FROM p),
       f AS (SELECT total - win losing, TRUNCATE((total - win) * m.fee_rate / 100, 2) fee, wq
             FROM agg JOIN market m ON m.id = $market_id),
       r AS (SELECT TRUNCATE((losing - fee) / wq, 8) rpc FROM f)
  SELECT COUNT(*) FROM p JOIN r JOIN market_settlement_detail d ON d.prediction_id = p.id
  WHERE p.option_id = $result_option AND d.settled_amount = TRUNCATE(p.amt + p.qty * r.rpc, 2);")"
check "Σ지급 + 소각 = settlement_pool" "1" "$(mq -e "
  SELECT (SELECT SUM(d.settled_amount) FROM market_settlement_detail d WHERE d.settlement_id = s.id) + s.burned_point_amount
         = s.settlement_pool
  FROM market_settlement s WHERE s.market_id = $market_id;")"
check "승자 prediction SETTLED" "$winners" "$(mq -e "
  SELECT COUNT(*) FROM market_prediction WHERE market_id = $market_id AND option_id = $result_option AND status = 'SETTLED';")"
check "패자 prediction SETTLED(0P)" "$losers" "$(mq -e "
  SELECT COUNT(*) FROM market_prediction WHERE market_id = $market_id AND option_id <> $result_option
    AND status = 'SETTLED' AND settled_amount = 0;")"
check "reputation task" "$((winners + losers))" "$(mq -e "SELECT COUNT(*) FROM market_reputation_update WHERE market_id = $market_id;")"

mp_count=$(pq -e "SELECT COUNT(*) FROM point_history WHERE type = 'SETTLE_MARKET' AND reference_type = 'MARKET_PREDICTION'
                    AND reference_id BETWEEN $pid_lo AND $pid_hi;")
mp_sum=$(pq -e "SELECT COALESCE(SUM(amount), 0) FROM point_history WHERE type = 'SETTLE_MARKET' AND reference_type = 'MARKET_PREDICTION'
                  AND reference_id BETWEEN $pid_lo AND $pid_hi;")
check "MP 지급 이력 건수 (중복·유실 없음)" "$winners" "$mp_count"
check "MP Σ지급 = Market Σ지급" "$(mq -e "
  SELECT SUM(d.settled_amount) FROM market_settlement_detail d JOIN market_settlement s ON s.id = d.settlement_id
  WHERE s.market_id = $market_id;")" "$mp_sum"

[ "$fail" -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
exit $fail
