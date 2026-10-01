#!/usr/bin/env bash
# 결과 반영 배치화의 장애 주입 (docs/architecture/ASYNC_POINT_FLOW.md §16.1 Q6)
#
#   ./scripts/bench/settlement/fault.sh poison <run> <i>   # run의 i번째 승자 정산 detail을 갱신하면 SQL 오류 (poison pill)
#   ./scripts/bench/settlement/fault.sh outage <run> <sec> # run의 정산 결과 반영이 시작되면 Market DB를 sec초 동안 끊음 (일시 장애)
#   ./scripts/bench/settlement/fault.sh outage-mp <run> <sec> # run의 MP 지급이 시작되면 Member-Point DB를 sec초 동안 끊음
#   ./scripts/bench/settlement/fault.sh stall-mp <run> <ms> [n=3000] # MP 지급이 n건을 넘으면 MP 쓰기를 ms 동안 막음 (커밋 지연 재현)
#   ./scripts/bench/settlement/fault.sh clear              # 주입 해제
#   ./scripts/bench/settlement/fault.sh status
#
# 앱 코드를 바꾸지 않고 DB 트리거로 "알 수 없는 예외"를 만든다. 앱 입장에서는 실제 poison pill과 같다.
# 실험 DB에서만 쓰고, 끝나면 반드시 clear 한다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
. "$(dirname "${BASH_SOURCE[0]}")/ids.sh"

mq() { docker exec -i -e MYSQL_PWD="${MARKET_MYSQL_ROOT_PASSWORD:-market-root-local-password}" market-mysql mysql -uroot -N market "$@" 2>/dev/null; }
mpq() { docker exec -i -e MYSQL_PWD="${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" member-point-mysql mysql -uroot -N memberpoint "$@" 2>/dev/null; }

case "${1:-}" in
  poison)
    run="${2:?run}"; i="${3:?i}"
    key="MARKET_SETTLEMENT_REWARD:market:$((MARKET_BASE + run)):prediction:$(($(pred_lo "$run") + i)):member:$((MEMBER_BASE + i))"
    mq <<SQL
DROP TRIGGER IF EXISTS bench_poison;
DELIMITER //
CREATE TRIGGER bench_poison BEFORE UPDATE ON market_settlement_detail FOR EACH ROW
  BEGIN
    IF NEW.idempotency_key = '$key' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'bench poison';
    END IF;
  END//
DELIMITER ;
SQL
    echo "poison installed: $key"
    ;;
  outage)
    # market_user 계정을 잠그고 기존 연결을 끊는다. 새 연결은 거부되므로 앱 입장에서는 DB 장애와 같다.
    # Debezium은 별도 계정이라 CDC는 영향이 없다.
    run="${2:?run}"; sec="${3:?sec}"
    until [ "$(mq -e "SELECT COUNT(*) > 0 FROM market_settlement_detail d JOIN market_settlement s ON s.id = d.settlement_id
                     WHERE s.market_id = $((MARKET_BASE + run)) AND d.status = 'SUCCESS';")" = "1" ]; do sleep 0.2; done
    mq -e "ALTER USER 'market_user'@'%' ACCOUNT LOCK;"
    echo "outage start $(date +%T) (${sec}s)"
    end=$(($(date +%s) + sec))
    while [ "$(date +%s)" -lt "$end" ]; do
      mq -e "SELECT CONCAT('KILL ', ID, ';') FROM information_schema.PROCESSLIST WHERE USER = 'market_user';" | mq || true
      sleep 0.5
    done
    mq -e "ALTER USER 'market_user'@'%' ACCOUNT UNLOCK;"
    echo "outage end $(date +%T)"
    ;;
  outage-mp)
    # Member-Point 쪽 같은 장애. run의 지급 이력이 쌓이기 시작하면 memberpoint_user를 잠근다.
    run="${2:?run}"; sec="${3:?sec}"
    pid_lo=$(pred_lo "$run"); pid_hi=$(pred_hi "$run")
    until [ "$(mpq -e "SELECT COUNT(*) > 0 FROM point_history WHERE type = 'SETTLE_MARKET'
                      AND reference_type = 'MARKET_PREDICTION' AND reference_id BETWEEN $pid_lo AND $pid_hi;")" = "1" ]; do sleep 0.2; done
    mpq -e "ALTER USER 'memberpoint_user'@'%' ACCOUNT LOCK;"
    echo "mp outage start $(date +%T) (${sec}s)"
    end=$(($(date +%s) + sec))
    while [ "$(date +%s)" -lt "$end" ]; do
      mpq -e "SELECT CONCAT('KILL ', ID, ';') FROM information_schema.PROCESSLIST WHERE USER = 'memberpoint_user';" | mpq || true
      sleep 0.5
    done
    mpq -e "ALTER USER 'memberpoint_user'@'%' ACCOUNT UNLOCK;"
    echo "mp outage end $(date +%T)"
    ;;
  stall-mp)
    # MP 쓰기를 ms 동안 막는다 (짧은 커밋 지연 재현). run의 MP 지급이 n건을 넘으면 시작한다. spend p95 원인 분석용 (§17.7)
    run="${2:?run}"; ms="${3:?ms}"; after="${4:-3000}"
    pid_lo=$(pred_lo "$run"); pid_hi=$(pred_hi "$run")
    until [ "$(mpq -e "SELECT COUNT(*) >= $after FROM point_history WHERE type = 'SETTLE_MARKET'
                      AND reference_type = 'MARKET_PREDICTION' AND reference_id BETWEEN $pid_lo AND $pid_hi;")" = "1" ]; do sleep 0.05; done
    echo "stall start $(date +%T.%3N)"
    mpq -e "FLUSH TABLES WITH READ LOCK; DO SLEEP($ms / 1000); UNLOCK TABLES;"
    echo "stall end $(date +%T.%3N)"
    ;;
  clear)
    mq -e "DROP TRIGGER IF EXISTS bench_poison; ALTER USER 'market_user'@'%' ACCOUNT UNLOCK;"
    mpq -e "ALTER USER 'memberpoint_user'@'%' ACCOUNT UNLOCK;"
    echo "fault cleared" ;;
  status) mq -e "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = 'market';" ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
