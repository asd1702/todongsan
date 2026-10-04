# 예측 참여 차감(spend) 서킷 브레이커

Market → Member-Point 동기 차감(`POST /internal/api/v1/points/spend`)이 느려질 때 Market 전체로 번지는 것을 막는다.
전제: 차감에는 자동 retry가 없고(Feign 기본 NEVER_RETRY), 타임아웃이면 예측을 `POINT_UNKNOWN`으로 기록하고 대사 스케줄러가 멱등성 키로 확정한다.

## 1. 문제 재현 (결과 ⑲)

도구: `scripts/bench/resilience/spend-stall.sh <run> <stall_sec> [label] [rate]`, `k6/spend-stall.js`, `StallAnalyze.java`.
인기 마켓 1개에 예측 참여를 고정 도착률로 120초, 같은 시간 포인트와 무관한 마켓 상세 조회(probe) 10/s. 30초부터 MP DB 쓰기를 30초 막는다(`FLUSH TABLES WITH READ LOCK`, 응답 없이 멈춘 MP).
Market은 `server.tomcat.mbeanregistry.enabled=true`로 톰캣 사용 스레드를 1초마다 기록한다(실행 인자, 코드 변경 없음). 대사 스케줄러는 벤치에서 꺼져 있다(`apps.sh`).

| run | 조건 | probe p95 (장애 중 → 직후 60초) | 톰캣 사용 스레드 | 예측 결과 |
|---|---|---|---|---|
| 1 | 50/s, 장애 없음 | 30초 타임아웃까지 증가, 75초부터 전부 실패 | 183 → 지표 응답 없음 | 처리 약 27/s, k6 실패 3008 |
| 2 | 15/s, 장애 없음 | 20ms 유지 | 3~8 | CONFIRMED 1801 |
| 3 | 15/s, 30초 장애 | 20ms → 해제 직후 1.6초, 10초 안에 회복 | 4 → 78 | 장애 중 전부 5초 대기 후 UNKNOWN 409 |
| 4 | 25/s, 30초 장애 | 20ms → **해제 후 60초 내내 1.5~2.9초** | 17 → 132 → 145, 끝까지 100 이상 | UNKNOWN 746 |

- **인기 마켓 용량 초과만으로 Market 전체가 멈춘다 (run 1).** 실제 MP(HTTP)와 함께 쟀을 때 한 마켓의 처리 용량은 약 27건/s(⑰의 약 100 TPS는 fake MP, 같은 JVM). 초과 요청이 쌓이면 마켓 조회까지 타임아웃. 클라이언트가 포기한 요청도 서버는 끝까지 처리했다(k6 확정 1745, DB 확정 4753).
- **MP가 30초 멈추면, 해제 뒤에도 1분 이상 Market 전체가 느리다 (run 4).** 장애 중에는 예측이 5초(readTimeout)씩 스레드를 잡고 실패하므로 조회는 버틴다. 문제는 해제 직후다.
- **포기한 요청을 MP가 나중에 전부 처리한다.** UNKNOWN 중 MP에 차감 이력이 생긴 건: run 3 405/409, run 4 734/746. MP는 회복하자마자 아무도 기다리지 않는 일을 하느라 느리고, Market은 그 사이 들어온 요청까지 밀린다. 사용자 입장에서는 포인트는 빠졌는데 예측은 "확인 중"인 상태가 대사까지 남는다.
- 톰캣 스레드(200)는 다 차지 않았다(최대 145). 장애 뒤 probe가 느린 공유 자원은 스레드가 아니라 DB 커넥션 풀(Hikari 10)이나 마켓 행 잠금 대기일 가능성이 높다 — **가설, 2단계에서 Hikari 지표로 확인한다.**

**서킷 브레이커로 기대하는 것**: MP가 느려지면 차감을 보내지 않고 즉시 실패(확정 FAILED) → 장애 중 스레드 점유와 MP에 쌓이는 죽은 요청을 없애 회복 직후의 1분 저하를 줄인다. run 1(용량 초과)은 서킷 브레이커가 아니라 입장 제한(bulkhead, rate limit)의 문제라 별도로 다룬다.

**공유 병목 확인 (run 5, run 4 재현 + Hikari 지표)**: 장애 해제 뒤 DB 커넥션 10개가 모두 사용 중이고 대기 스레드가 51 → 189까지 늘었다. 톰캣 스레드도 이번에는 200에 닿았다.
마켓 조회(probe)는 커넥션을 기다리느라 느렸다(p95 2.9 → 3.7초). 장애 전에도 25/s에서 이미 대기 10~12, probe 250~320ms로 용량 경계였다.
해석: 해제 순간 대기 중이던 예측이 한꺼번에 확정 트랜잭션(마켓 행 잠금 + 커넥션 보유)으로 몰리고, 도착률(25/s)이 처리 용량(약 27/s)에 붙어 있어 밀린 줄이 줄지 않는다.
**서킷 브레이커가 줄일 수 있는 것은 이 몰림(장애 중 보낸 요청, 해제 직후 한꺼번에 돌아오는 응답)이고, 용량 경계 자체는 입장 제한(별도 작업)의 몫이다.**

## 2. 설계

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| B1 | 적용 범위 | 예측 참여의 **차감(spend) 호출 하나**. 펜싱·거래 조회(대사)·sync 정산 배치 호출은 제외 | 사용자 요청 경로에서 스레드·커넥션을 잡는 호출이 이것뿐이다. 대사는 1분 주기라 부하가 작다 |
| B2 | 판정 위치 | **TX1(PENDING 예측 생성) 전에 허가를 받는다** (`tryAcquirePermission`). 거절이면 DB를 건드리지 않는다. 허가 뒤 TX1이 실패하면 허가를 돌려준다(`releasePermission`) | 차감 호출만 감싸면 거절된 요청도 TX1·실패 기록 TX 2개를 쓴다. run 5에서 고갈된 것이 바로 DB 커넥션이다 |
| B3 | 거절 응답 | 예측 row를 만들지 않고 **503 `EXTERNAL_SERVICE_UNAVAILABLE`** (공통 코드, ERROR_POLICY에 이미 있음. Market `CommonErrorCode`에 추가) | MP에 아무것도 보내지 않았으므로 상태가 확정이다(UNKNOWN 아님). row가 없어 같은 멱등성 키로 바로 다시 시도할 수 있다 |
| B4 | 실패로 세는 것 | `MemberPointTimeoutException`, `MemberPointUnavailableException`, `MemberPointExternalException` + **느린 호출** | 업무 실패(`PointInsufficientException`, 4xx)는 MP가 정상 응답한 것이므로 성공으로 센다 (잔액 부족이 많다고 차단하면 안 된다) |
| B5 | 진행 중 타임아웃 | 지금과 같다: `POINT_UNKNOWN` + 대사 | 보냈는지 모르는 건 서킷과 무관하게 UNKNOWN이 맞다 (§1 run 3·4: 대부분 실제로 차감됨) |
| B6 | 설정 (초안, 3단계 측정으로 확정) | 시간 기반 창 10초, 최소 호출 20, 실패율 50%, 느린 호출 1초·비율 50%, OPEN 유지 5초, HALF_OPEN 허가 5건 | 정상 예측 응답 p95 약 250ms(run 2). 서킷은 호출이 끝나야 결과를 세므로 감지 시간 ≈ readTimeout |
| B7 | readTimeout | 차감 호출만 5초 → **2초** 검토 (측정 후 확정) | 장애 감지와 스레드 점유가 readTimeout에 비례한다. 다른 MP 호출(정산 배치 등)은 그대로 둔다 |
| B8 | 상태 범위 | 인스턴스마다 따로 | Resilience4j 기본. 인스턴스 사이 공유는 필요 없다 |
| B9 | 관측 | 상태 전이 로그(WARN), `resilience4j-micrometer` 지표 | Insight → Market 서킷과 같은 라이브러리·버전(2.3.0) |

**정합성**: 거절은 MP 호출도 DB 기록도 없으므로 정합성 영향이 없다. 허가 뒤 경로는 지금과 같다(멱등성 키, UNKNOWN 대사, 펜싱).

## 3. 검증 계획
1. 단위: 거절 시 TX1·MP 호출 없음 + 503, 잔액 부족은 실패로 세지 않음, 타임아웃·5xx·느린 호출은 실패로 셈, TX1 실패 시 허가 반환, HALF_OPEN 성공 → CLOSED. 변형 검사.
2. E2E: §1 run 3(15/s)·run 5(25/s) 조건 재측정 — 장애 중 스레드·커넥션, UNKNOWN 수와 그중 실제 차감 수, 해제 뒤 probe 회복 시간.

## 4. 구현 기록 (3단계)

- `MemberPointSpendCircuitBreaker`: 허가(`acquire`) → 차감 직전 `callStarted` → `responded`(응답, 잔액 부족 포함) / `failed`(타임아웃·5xx·결과 불명) → `close`(기록하지 않았으면 허가 반환). `MarketPredictionService.createPrediction`이 TX1 전에 허가를 받는다(try-with-resources).
- 거절: `CommonErrorCode.EXTERNAL_SERVICE_UNAVAILABLE`(503) 추가 (루트 `ERROR_POLICY.md`에 이미 있던 공통 코드, `MARKET_ERROR_CODE.md` 설명 보강).
- 차감만 readTimeout 2초: Feign 메서드 파라미터 `Request.Options`(`client.member-point.spend-read-timeout-millis`). 다른 MP 호출은 5초 그대로.
- 스위치 `market.member-point.spend-circuit-breaker-enabled`(기본 true): before/after 측정과 롤백용.
- 의존성: `resilience4j-spring-boot3`, `resilience4j-micrometer`. **버전은 Spring Cloud BOM(2.2.0)에 맡긴다.** 2.3.0을 지정하면 BOM이 내린 `resilience4j-spring6` 2.2.0과 섞여 컨텍스트가 뜨지 않는다(Insight는 Spring Cloud가 없어 2.3.0).
- 검증: `MarketPredictionSpendCircuitBreakerTest` 7개(OPEN 거절 시 row·MP 호출 없음 + 503, 타임아웃·5xx로 OPEN, 잔액 부족은 세지 않음, 느린 성공도 OPEN, TX1 실패 시 허가 반환, HALF_OPEN 성공 → CLOSED), 차감 readTimeout 2초 적용 테스트.
  변형 검사 5개(잔액 부족을 실패로, 타임아웃을 성공으로, 허가 미반환, 시간 미측정, OPEN이어도 통과) 모두 잡힘. Market 전체 테스트 통과.
