#!/usr/bin/env bash
# 정산 비교 실험 데이터 정리 (docs/architecture/ASYNC_POINT_FLOW.md §15.2)
#
#   ./scripts/bench/settlement/cleanup.sh run <run>   # run 1개의 마켓·정산·outbox·이력 삭제 (회원 풀 유지)
#   ./scripts/bench/settlement/cleanup.sh runs        # 모든 run 데이터 + 실험 회원의 포인트 이력 삭제 (회원 풀 유지)
#   ./scripts/bench/settlement/cleanup.sh all         # runs + 실험 회원 풀 삭제 + AUTO_INCREMENT를 현재 최대값+1로 복구
#
# Kafka 토픽에 남은 이벤트는 지우지 않는다 (consumer offset이 이미 지나갔으므로 다시 처리되지 않는다).
set -euo pipefail
export MSYS_NO_PATHCONV=1

MEMBER_LO=500001
MEMBER_HI=503000
MARKET_BASE=800000
PREDICTION_BASE=500000

mq() { docker exec -i market-mysql mysql -uroot -p"${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}" market -N "$@" 2>/dev/null; }
pq() { docker exec -i member-point-mysql mysql -uroot -p"${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" memberpoint -N "$@" 2>/dev/null; }

# market id 구간 [lo, hi]의 Market DB 데이터 삭제 (FK 순서: detail → 정산/무효 → 예측 → 옵션 → 마켓)
delete_markets() {
  local lo="$1" hi="$2"
  mq <<SQL
DELETE FROM market_outbox_event
 WHERE CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '\$.data.marketId')) AS UNSIGNED) BETWEEN $lo AND $hi;
DELETE FROM market_reputation_update WHERE market_id BETWEEN $lo AND $hi;
DELETE d FROM market_settlement_detail d JOIN market_settlement s ON s.id = d.settlement_id
 WHERE s.market_id BETWEEN $lo AND $hi;
DELETE FROM market_settlement WHERE market_id BETWEEN $lo AND $hi;
DELETE rd FROM market_refund_detail rd JOIN market_void v ON v.id = rd.market_void_id
 WHERE v.market_id BETWEEN $lo AND $hi;
DELETE FROM market_void WHERE market_id BETWEEN $lo AND $hi;
DELETE FROM market_price_history WHERE market_id BETWEEN $lo AND $hi;
DELETE FROM market_comment WHERE market_id BETWEEN $lo AND $hi;
DELETE FROM market_prediction WHERE market_id BETWEEN $lo AND $hi;
DELETE FROM market_option WHERE market_id BETWEEN $lo AND $hi;
DELETE FROM market WHERE id BETWEEN $lo AND $hi;
SQL
  pq -e "DELETE FROM point_outbox_event WHERE aggregate_type = 'point-results'
           AND CAST(aggregate_id AS UNSIGNED) BETWEEN $lo AND $hi;"
}

report() {
  echo "market rows (800001~800040): $(mq -e "SELECT COUNT(*) FROM market WHERE id BETWEEN $((MARKET_BASE + 1)) AND $((MARKET_BASE + 40));")," \
       "predictions: $(mq -e "SELECT COUNT(*) FROM market_prediction WHERE market_id BETWEEN $((MARKET_BASE + 1)) AND $((MARKET_BASE + 40));")," \
       "bench members: $(pq -e "SELECT COUNT(*) FROM member WHERE id BETWEEN $MEMBER_LO AND $MEMBER_HI;")," \
       "bench history: $(pq -e "SELECT COUNT(*) FROM point_history WHERE member_id BETWEEN $MEMBER_LO AND $MEMBER_HI;")"
}

case "${1:-}" in
  run)
    run="${2:?run 번호 필요}"
    delete_markets $((MARKET_BASE + run)) $((MARKET_BASE + run))
    pq -e "DELETE FROM point_history WHERE reference_type = 'MARKET_PREDICTION'
             AND reference_id BETWEEN $((PREDICTION_BASE + run * 10000)) AND $((PREDICTION_BASE + run * 10000 + 9999));"
    ;;
  runs)
    delete_markets $((MARKET_BASE + 1)) $((MARKET_BASE + 40))
    pq -e "DELETE FROM point_history WHERE member_id BETWEEN $MEMBER_LO AND $MEMBER_HI;"
    ;;
  all)
    delete_markets $((MARKET_BASE + 1)) $((MARKET_BASE + 40))
    pq <<SQL
DELETE FROM point_history WHERE member_id BETWEEN $MEMBER_LO AND $MEMBER_HI;
DELETE FROM oauth_token WHERE member_id BETWEEN $MEMBER_LO AND $MEMBER_HI;
DELETE FROM member WHERE id BETWEEN $MEMBER_LO AND $MEMBER_HI;
ALTER TABLE member AUTO_INCREMENT = 1;
SQL
    # InnoDB는 현재 최대값보다 작은 값을 지정하면 최대값+1로 맞춘다
    mq -e "ALTER TABLE market AUTO_INCREMENT = 1; ALTER TABLE market_option AUTO_INCREMENT = 1;
           ALTER TABLE market_prediction AUTO_INCREMENT = 1;"
    ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
report
