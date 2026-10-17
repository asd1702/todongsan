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

## 5. 재측정 (4단계, 결과 ⑳)

`scripts/bench/resilience/sweep-cb.sh 11`. 설정마다 앱 재기동 → MP 30초 정지. 같은 시간대에 before(A)도 다시 쟀다. 설정마다 1회.

| run | 설정 | 장애 중 예측 응답 p95 | 장애 중 톰캣 스레드 | 해제 직후 probe p95 최대 → 20ms대 회복 | UNKNOWN (MP 실제 차감) | 503 거절 |
|---|---|---|---|---|---|---|
| 11 | A 서킷 끔, 5초 (25/s) | 5.2초 | 128~131 | 2.5초 → 약 35초 | 744 (744) | 0 |
| 12 | **B 서킷 켬, 2초 (25/s)** | 2.1초 → **4~6ms** (OPEN 후) | 42~53 → **2~6** | **14ms, 저하 없음** | **163 (163)** | 763 |
| 13 | C 서킷 켬, 5초 (25/s) | 5.2초 → 3~4ms | 127 → 6 | 92ms | 194 (194) | 764 |
| 14 | D 서킷 끔, 2초 (25/s) | 2.1~2.3초 | 52~56 | 1.0초 → 약 10초 | 846 (846) | 0 |
| 15 | A 서킷 끔, 5초 (15/s) | 5.1~5.3초 | 71~83 | 1.2초 → 5초 | 416 (416) | 0 |
| 16 | **B 서킷 켬, 2초 (15/s)** | 2.0초 → 4ms | 31 → 1~6 | **15ms, 저하 없음** | **105 (105)** | 약 460 |

- **해제 뒤 저하가 사라졌다 (B).** 서킷이 열린 동안 차감을 보내지 않으므로 MP에 죽은 요청이 쌓이지 않고, 해제 순간 한꺼번에 돌아오는 응답도 없다. HALF_OPEN 시험 호출이 성공하면 바로 CLOSED.
- **MP가 나중에 처리하는 "포기한 차감"이 744 → 163 (-78%).** 남은 163은 서킷이 열리기 전(감지까지 약 4~6초)에 보낸 요청이다. 대사 스케줄러가 확정할 대상도 그만큼 준다.
- **효과는 서킷이 대부분이다.** readTimeout만 2초로 줄이면(D) 장애 중 스레드는 절반이 되지만 포기한 차감은 줄지 않는다(846). 2초는 감지를 앞당긴다(C 대비 B: 장애 중 스레드 127 → 53, UNKNOWN 194 → 163).
- 대가: 장애 동안 사용자는 즉시 503을 받는다(B 25/s 763건). 기존에는 5초 기다린 뒤 "확인 중"(UNKNOWN)이고 포인트는 대부분 실제로 빠졌다. 503은 아무 일도 일어나지 않았음이 확정이라 같은 키로 다시 시도하면 된다.
- 회복 뒤 같은 회원이 재시도하면 성공한다(row가 없다). k6 `http_0`(B 22건)은 k6 VU 생성 지연으로 생긴 클라이언트 쪽 연결 오류로 보인다(서버 지표 정상) — 미확인.

**새로 드러난 문제: 재기동 직후 서킷이 오작동한다.** B·C 모두 시작 0~10초에 장애 없이 OPEN이 됐다(503 134건, 15/s에서 77건). 앱을 막 띄워 MP·Market이 데워지기 전이라 차감이 1초를 넘었고(예측 p95 2.7초), 느린 호출로 셌다.
before(A)도 같은 구간 probe가 1.3초까지 느렸으므로 실제로 느린 것은 맞지만, 배포(롤링 재시작)마다 사용자에게 503이 나가는 것은 원하는 동작이 아니다. → §6에서 다룬다.

## 6. 콜드 스타트 오작동과 MP 워밍업 (결과 ㉑)

**원인 위치 확인**: 한쪽만 재기동하고 25/s, 장애 없음으로 쟀다 (`apps.sh`의 `ONLY_MARKET=1` / `ONLY_MP=1`).

| run | 조건 | 시작 0~10초 503 | 첫 5초 probe p95 | 첫 5초 예측 p95 |
|---|---|---|---|---|
| 12 | 둘 다 재기동 (§5) | 134 | 1.2초 | 2.7초 |
| 21 | Market만 재기동 | **0** | 412ms | 953ms |
| 22 | MP만 재기동 | **128** | 488ms | 1.6초 |

→ 오작동은 **MP 콜드 스타트** 때문이다. Market 워밍업으로는 풀리지 않는다.

**원인 (카카오페이 JVM 웜업 사례와 같은 방식으로 확인)**: MP를 `-Xlog:class+load`로 띄우고 같은 부하를 넣었다(run 23).
첫 트래픽이 들어온 3초 동안 클래스 **2,462개**를 그 자리에서 로딩했다: Spring Data 463, Hibernate(query·sql·engine·persister·event 등) 약 930, Spring Web·HTTP·Tomcat 약 270, Security·트랜잭션·Jackson 약 110, MP 코드 24.
요청을 처리하면서 클래스 로딩·JIT가 겹쳐 평소 수 ms인 차감이 1초를 넘었고, Market 서킷이 느린 호출로 셌다.

**조치 (MP)**
- `SpendPathWarmup`(ApplicationRunner): ① 자기 자신에게 금액 0 차감 HTTP 200회 (쓰기 전에 `POINT_INVALID_AMOUNT`로 끝남 → Tomcat·Security·MVC·Jackson·멱등성 키 조회) ② 롤백 전용 트랜잭션 안에서 실제 차감 서비스 100회 (회원 조회·잔액 UPDATE·이력 INSERT → 전부 롤백). 약 6초. 실패해도 기동은 막지 않는다.
- readiness 프로브: `spring-boot-starter-actuator`, `management.endpoint.health.probes.enabled=true`(노출은 health만). Spring Boot는 ApplicationRunner가 끝난 뒤 ACCEPTING_TRAFFIC으로 바꾼다.
  **Security가 `/actuator/health/**`를 막아 처음에 403이었다** — 이대로면 k8s에서 인스턴스가 영영 준비되지 않는다. GET만 허용하고 테스트로 고정했다.
- `apps.sh`: MP readiness UP까지 기다린다(로드밸런서·k8s 역할). UP이 안 되면 실패.
- 기동 중 readiness 기록: 연결 불가 → **503 OUT_OF_SERVICE 약 4.5초(워밍업)** → 200 UP.

| run | 조건 | 시작 0~10초 503 | 첫 5초 probe p95 | 첫 5초 예측 p95 |
|---|---|---|---|---|
| 22 | MP만 재기동, 워밍업 없음 | 128 | 488ms | 1.6초 |
| 25 | **MP만 재기동, 워밍업 + readiness** | **0** | **57ms** | **564ms** |

검증: `SpendPathWarmupTest`(워밍업이 실제 차감을 실행하지만 잔액·이력이 남지 않음, readiness가 인증 없이 UP). 변형 검사 3개(롤백 제거, HTTP 금액 0 → 1, 프로브 허용 제거) 모두 잡힘.
운영에서는 로드밸런서 slow start(AWS ALB `slow_start`, Envoy)를 함께 쓰면 워밍업이 못 데운 경로(JIT 최적화 등)까지 완화된다.

## 7. B4 정정: MP의 4xx 업무 오류는 실패로 세지 않는다

입장 제한 실험 준비 중(HOT_MARKET_ADMISSION.md §3) 같은 run 번호를 다시 써서 MP에 남은 멱등성 키와 충돌했다(`IDEMPOTENCY_KEY_CONFLICT`).
Market은 잔액 부족을 뺀 MP 업무 오류(4xx)를 모두 `MemberPointExternalException`(결과 불명)으로 바꾸고, 서킷은 이를 실패로 셌다 →
**데이터 문제 하나로 서킷이 열려 모든 마켓의 예측이 503**이 됐다(시작 0~60초 내내 OPEN, 다른 마켓도 거절).
4xx는 MP가 정상 응답한 것이다. 운영에서도 클라이언트 버그·데이터 문제로 4xx가 몰리면 같은 일이 생긴다.

- `MemberPointRejectedException extends MemberPointExternalException`: HTTP 4xx, 또는 성공 아님 + errorCode 응답. 예측 처리는 그대로(UNKNOWN → 대사).
- 서킷은 이 타입을 `responded`(성공)로 센다. 실패로 세는 것은 타임아웃·연결 실패·503·5xx·디코딩 실패·errorCode 없는 응답.
- 검증: `businessRejectionsDoNotOpenCircuit`, `spendMaps4xxToRejectedAnd5xxToExternal`. 변형 검사 2개(4xx도 실패로 셈, 4xx를 External로) 모두 잡힘.
