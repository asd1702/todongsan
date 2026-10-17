#!/usr/bin/env bash
# 예측 참여 경로 부하 실험 러너 (AS-IS: RDB + 동기 REST)
#
# 사전 준비
#   1) Docker 실행
#   2) 테스트 DB만 재생성/기동
#      docker compose -f infra/docker-compose.test.yml up -d --force-recreate market-mysql-it
#      (docker-compose.test.yml 은 standalone 실행 가능. tmpfs 라 재생성 시 데이터 손실은 의도된 동작)
#
# 실험 순서 (이 순서를 지킬 것)
#   S → A → (판정) → B → E → ... → EDA 구현 후 재측정
#
#   S  단건 정상상태 baseline. C=1, warmup 2000. 3라운드 반복해서 "안정성"부터 확인.
#      → 절대값보다 3라운드 p50/p95가 서로 붙는지 확인한다.
#   A  락 격리 sweep. pool > C 로 두고 확정 처리량이 어디서 정체하는지만 본다.
#   B  시스템 현실. 운영 pool(10) 그대로. 락 대기가 커넥션 고갈로 전파되는지 본다.
#   E  외부 지연 민감도(0/50/200ms). 트랜잭션 경계 설계가 실제로 먹히는지.
#   C  burst / open-loop. EDA 도입 후 비교용 (지금은 AS-IS 기준선만 확보).
#   D  다중 마켓. marketId 파티셔닝 확장성 대조군.
#
# 사용: scripts/bench/run-prediction-bench.sh S
set -euo pipefail

EXP="${1:-S}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/market-service"

STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_SUFFIX="${BENCH_RUN_SUFFIX:-}"
OUT="$ROOT/reports/bench/$STAMP-exp$EXP${RUN_SUFFIX:+-$RUN_SUFFIX}"
mkdir -p "$OUT"

# AS-IS 는 응답이 곧 확정이므로 sync. EDA 전환 후에만 async 로 바꾼다.
CONFIRM_MODE="${BENCH_CONFIRM_MODE:-sync}"
STEADY_WARMUP="${BENCH_WARMUP:-2000}"
SWEEP_REQUESTS="${BENCH_REQUESTS:-1000}"

run() { # run <label> <extra -D args...>
  local label="$1"; shift
  echo ""
  echo "==== [$EXP] $label ============================================"
  ./gradlew test --tests "*MarketPredictionLatencyBenchmarkTest" \
    -Dbench.enabled=true \
    -Dbench.confirmMode="$CONFIRM_MODE" \
    "$@" --rerun-tasks 2>&1 | tee "$OUT/$label.log" \
    | grep -aE "^\s+(M1|M2|Admission|Confirmation|자원|InnoDB|실패)|^ \[round|^\| (closed|open)" || true
}

{
  echo "# 예측 참여 경로 부하 실험 — 실험 $EXP"
  echo
  echo "- 실행일시: $(date -Iseconds)"
  echo "- 커밋: $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo 'n/a')"
  echo "- 하드웨어: $(uname -srm) / cores=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo '?')"
  echo "- 확정 측정 모드: $CONFIRM_MODE"
  echo "- steady warmup: $STEADY_WARMUP"
  echo "- 부하 생성기 위치: 서버와 동일 JVM (헤드라인 수치로 쓰려면 분리 후 재측정 필요)"
  echo "- DB: market-mysql-it (mysql:8.4, tmpfs — 디스크 fsync 없음)"
  echo
} > "$OUT/README.md"

case "$EXP" in
  S) # 단건 정상상태. 절대값보다 "3라운드가 서로 붙는가"를 먼저 본다.
     run "S-c1" -Dbench.mode=closed -Dbench.concurrency=1 \
                -Dbench.warmup="$STEADY_WARMUP" -Dbench.requests=2000 -Dbench.rounds=3 -Dbench.pool=10 ;;

  A) # 경합 격리. pool 을 최대 C(200)보다 크게 고정 → Hikari 대기를 제거하고 DB/공유상태 경합을 본다.
     for C in 1 10 50 100 200; do
       run "A-c$C" -Dbench.mode=closed -Dbench.concurrency="$C" \
                   -Dbench.warmup="$STEADY_WARMUP" -Dbench.requests="$SWEEP_REQUESTS" -Dbench.rounds=1 -Dbench.pool=220
     done ;;

  B) # 시스템 현실. pool=10(Spring Boot 기본, 이 서비스의 실제 설정).
     # Hikari awaiting / Tomcat busy 가 어디서 튀는지가 관전 포인트.
     for C in 1 10 50 100 200; do
       run "B-c$C" -Dbench.mode=closed -Dbench.concurrency="$C" \
                   -Dbench.warmup="$STEADY_WARMUP" -Dbench.requests="$SWEEP_REQUESTS" -Dbench.rounds=1 -Dbench.pool=10
     done ;;

  E) # 외부 지연 민감도. 락 밖 외부 호출 설계가 맞다면 지연이 늘어도 확정 처리량은 덜 무너져야 한다.
     for D_MS in 0 50 200; do
       run "E-d$D_MS" -Dbench.mode=closed -Dbench.concurrency=100 -Dbench.spendDelayMs="$D_MS" \
                      -Dbench.warmup="$STEADY_WARMUP" -Dbench.requests="$SWEEP_REQUESTS" -Dbench.rounds=1 -Dbench.pool=220
     done ;;

  C) # burst / open-loop. 고정 도착률.
     for R in 50 100 200 300 500; do
       run "C-r$R" -Dbench.mode=open -Dbench.rate="$R" -Dbench.durationSec="${BENCH_DURATION:-60}" \
                   -Dbench.warmup="$STEADY_WARMUP" -Dbench.rounds=1 -Dbench.pool=10
     done ;;

  D) # 다중 마켓. 한 마켓만 때리면 파티셔닝 확장성은 절대 안 보인다.
     for M in 1 4 16; do
       run "D-m$M" -Dbench.mode=closed -Dbench.concurrency=100 -Dbench.markets="$M" \
                   -Dbench.warmup="$STEADY_WARMUP" -Dbench.requests="$SWEEP_REQUESTS" -Dbench.rounds=1 -Dbench.pool=220
     done ;;

  *) echo "알 수 없는 실험: $EXP  (S|A|B|C|D|E)"; exit 1 ;;
esac

echo ""
echo "완료. 로그: $OUT"
echo "각 로그의 '결과 요약' 표 행을 $OUT/README.md 에 모아 붙일 것."
