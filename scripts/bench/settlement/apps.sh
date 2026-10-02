#!/usr/bin/env bash
# 실험용 앱 기동/중지 (docs/architecture/ASYNC_POINT_FLOW.md §15.2)
#
#   ./scripts/bench/settlement/apps.sh build                     # bootJar 빌드 (코드 변경 시)
#   ./scripts/bench/settlement/apps.sh start <sync|async> [member_id|market_id]
#   MP_APP_ARGS="--spring.kafka.consumer.max-poll-records=200" ./scripts/bench/settlement/apps.sh start async   # MP에만 추가 인자
#   MARKET_APP_ARGS="--spring.kafka.consumer.max-poll-records=500" ./scripts/bench/settlement/apps.sh start async # Market에만 추가 인자
#   ./scripts/bench/settlement/apps.sh stop
#   ./scripts/bench/settlement/apps.sh status
#
# 실험 조건: 두 모드 모두 Market의 MP 클라이언트는 http, 실험과 무관한 스케줄러(대사, 신뢰도, outbox 삭제)는 off.
# 정산 재시도 스케줄러는 기본값 그대로 둔다 (sync timeout 시 실제 복구 경로).
# 현재 설정은 reports/bench/settlement/apps.state에 기록하고 run.sh가 읽는다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="$ROOT/reports/bench/settlement"
STATE="$OUT/apps.state"
mkdir -p "$OUT"

jar_of() { ls "$ROOT/$1/build/libs/"*.jar 2>/dev/null | grep -v -- '-plain' | head -1; }

pid_on_port() { netstat -ano 2>/dev/null | awk -v p=":$1" '$2 ~ p"$" && $4 == "LISTENING" {print $5; exit}'; }

stop_apps() {
  for port in 8080 8082; do
    local pid
    pid="$(pid_on_port "$port")"
    if [ -n "$pid" ]; then
      taskkill //F //PID "$pid" > /dev/null && echo "stopped port $port (pid $pid)"
    fi
  done
  rm -f "$STATE"
}

wait_ready() { # wait_ready <log> <started-pattern> <label>
  for _ in $(seq 1 120); do
    if grep -q "$2" "$1" 2>/dev/null && [ "$(grep -c 'partitions assigned' "$1")" -ge 3 ]; then
      echo "$3 ready"; return 0
    fi
    if grep -q "APPLICATION FAILED TO START" "$1" 2>/dev/null; then break; fi
    sleep 1
  done
  echo "$3 failed to start: $1" >&2; tail -20 "$1" >&2; exit 1
}

case "${1:-}" in
  build)
    (cd "$ROOT/market-service" && ./gradlew bootJar -q --no-daemon)
    (cd "$ROOT/member-point-service" && ./gradlew bootJar -q --no-daemon)
    echo "market: $(jar_of market-service)"; echo "mp:     $(jar_of member-point-service)"
    ;;
  start)
    mode="${2:?sync|async}"; key="${3:-member_id}"
    [ "$mode" = "sync" ] && key="na"   # sync는 Kafka key를 쓰지 않는다
    market_jar="$(jar_of market-service)"; mp_jar="$(jar_of member-point-service)"
    if [ -z "$market_jar" ] || [ -z "$mp_jar" ]; then echo "jar 없음: apps.sh build 먼저" >&2; exit 1; fi
    stop_apps
    mp_log="$OUT/mp.log"; market_log="$OUT/market.log"

    DB_URL='jdbc:mysql://localhost:3307/memberpoint?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true' \
    DB_USERNAME=memberpoint_user DB_PASSWORD=memberpoint-local-password \
    JWT_SECRET=bench-jwt-secret-must-be-32-chars-long CRYPTO_AES_KEY='bench-aes-key-must-be-32-chars!!' \
    MARKET_POINT_REQUESTS_CONSUMER_ENABLED=true POINT_OUTBOX_CLEANUP_SCHEDULER_ENABLED=false \
      nohup java -jar "$mp_jar" --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN \
        --logging.level.com.todongsan.memberpointservice=INFO ${MP_APP_ARGS:-} > "$mp_log" 2>&1 &
    wait_ready "$mp_log" "Started MemberPointServiceApplication" "member-point"

    MARKET_POINT_TRANSPORT_MODE="$mode" MARKET_POINT_REQUEST_KEY="${key/na/member_id}" MARKET_POINT_RESULTS_CONSUMER_ENABLED=true \
    MEMBER_POINT_CLIENT_MODE=http MEMBER_POINT_SERVICE_URL=http://localhost:8080 \
    MARKET_OUTBOX_CLEANUP_SCHEDULER_ENABLED=false MARKET_REPUTATION_UPDATE_SCHEDULER_ENABLED=false \
      nohup java -jar "$market_jar" --market.scheduler.prediction-reconciliation.enabled=false ${MARKET_APP_ARGS:-} > "$market_log" 2>&1 &
    wait_ready "$market_log" "Started MarketServiceApplication" "market"

    printf 'MODE=%s\nKEY=%s\nSTARTED=%s\n' "$mode" "$key" "$(date '+%Y-%m-%dT%H:%M:%S')" > "$STATE"
    cat "$STATE"
    ;;
  stop) stop_apps ;;
  status)
    [ -f "$STATE" ] && cat "$STATE" || echo "no state"
    echo "port 8080 pid=$(pid_on_port 8080) / port 8082 pid=$(pid_on_port 8082)"
    ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
