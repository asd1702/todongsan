# Prediction TX1 deadlock fix

## Problem

동일한 Market에 서로 다른 회원이 처음 예측을 생성할 때 `market_prediction`의
`uq_market_prediction_member (market_id, member_id)` unique index에서 InnoDB
deadlock이 발생했다. 이는 Market/Option/confirmation(TX2) lock 전략이 아니라,
신규 prediction을 만드는 TX1의 잠금 순서에서 발생한 문제였다.

## Evidence before the fix

Deadlock debug 재현은 Market 1개, Fake Member-Point(0 ms), 충분한 Hikari pool,
요청 100건으로 실행했다. MySQL deadlock graph의 두 transaction은 모두
`INSERT INTO market_prediction ...`을 실행 중이었다.

* Transaction 1959 (member 4)는 `uq_market_prediction_member`의 `supremum`
  record X lock을 보유한 채 그 record의 X insert-intention lock을 기다렸다.
* Transaction 1960 (member 3)도 같은 lock을 보유하고 같은 insert-intention
  lock을 기다렸고, InnoDB가 이 transaction을 victim으로 rollback했다.

두 요청의 application phase trace에는 모두
`PREDICTION_EXISTING_LOCK_ACQUIRED` 뒤 `PREDICTION_INSERT_BEFORE`가 남아 있었다.
즉, 존재하지 않는 `(market_id, member_id)` key를 `SELECT ... FOR UPDATE`로
조회한 뒤 INSERT한 것이 index gap/supremum insert-intention cycle을 만들었다.

| C | Before success | Before app deadlocks | Before DB deadlocks |
|--:|---------------:|---------------------:|--------------------:|
| 2 | 98 | 2 | 2 |
| 3 | 49 | 51 | 51 |
| 5 | 26 | 74 | 74 |
| 10 | 13 | 87 | 87 |

## Fix

TX1에서 prediction 존재 여부를 확인하는 query를 plain `SELECT`로 변경했다.
없을 때는 곧바로 INSERT를 시도하며, `(market_id, member_id)` unique constraint가
신규 생성 경쟁의 최종 판정자가 된다. `DuplicateKeyException`은 transactional
`createPendingPrediction` 메서드 밖의 기존 facade에서 처리하므로, constraint
오류 뒤 같은 transaction에서 추가 query를 수행하지 않는다.

이미 존재한 prediction의 정책은 변경하지 않았다.

* `FAILED`만 기존 prediction을 재사용해 retry한다.
* `POINT_PENDING`, `POINT_UNKNOWN`, `CONFIRMED`, `SETTLED` 등 나머지 상태는
  기존과 같이 `MARKET_ALREADY_PREDICTED`로 종료한다.
* INSERT 경쟁에서 진 요청은 point spend 전에 duplicate로 종료하므로 중복 spend가
  발생하지 않는다.

TX2의 Market, Option, Prediction `FOR UPDATE`는 변경하지 않았다.

## Verification after the fix

같은 deadlock debug 환경을 새 report directory에서 다시 실행했다.

| C | After success | After app deadlocks | After DB deadlocks | Row-lock waits | Row-lock time |
|--:|--------------:|--------------------:|-------------------:|---------------:|--------------:|
| 2 | 100 | 0 | 0 | 98 | 1,521 ms |
| 3 | 100 | 0 | 0 | 145 | 3,243 ms |
| 5 | 100 | 0 | 0 | 170 | 8,132 ms |
| 10 | 100 | 0 | 0 | 179 | 17,646 ms |

`row_lock_waits`와 `row_lock_time`은 동일 Market confirmation의 lock contention을
나타낼 수 있지만 deadlock count가 아니다. 이 표의 DB deadlock 값은
`Innodb_deadlocks`를 우선 조회하고, MySQL 8.4에서 해당 status가 노출되지 않는
환경에서는 `performance_schema`의 `ER_LOCK_DEADLOCK` counter를 사용한 delta다.

재현 산출물은
[`20260906-112745-deadlock-after-fix`](../../reports/bench/20260906-112745-deadlock-after-fix)
에 보존했다. 기존 before evidence는 덮어쓰지 않았다.

## Post-fix steady-state baseline (S)

C=1, pool=10, warmup=2,000, requests=2,000, rounds=3으로 다시 측정했다.
세 round 모두 2,000/2,000 성공했고 deadlock과 row-lock wait는 없었다.

| Round | M1 p50 | M1 p95 | M1 p99 | Confirmation TPS |
|------:|-------:|-------:|-------:|-----------------:|
| 1 | 42.3 ms | 51.2 ms | 54.4 ms | 23.5 /s |
| 2 | 37.2 ms | 46.9 ms | 52.0 ms | 26.6 /s |
| 3 | 38.4 ms | 47.7 ms | 51.3 ms | 25.9 /s |

Round median은 p50 38.4 ms, p99 52.0 ms, Confirmation TPS 25.9/s다.
이 값은 기존 기준선과 절대값이 다르므로 코드 regression으로 단정하지 않는다.
동일 머신의 현 시점 상태에서 수집한 post-fix 기준선이며, round 간 안정성이 먼저
확인됐다. 원본은
[`20260906-113237-expS-after-fix`](../../reports/bench/20260906-113237-expS-after-fix)
에 보존했다.

## Benchmark interpretation boundary

Throughput plateau는 충분히 높은 성공률에서만 해석하며, `1 / plateau TPS`는
lock hold time이 아닌 lock·commit·DB 처리·application serial section을 포함한
service-demand proxy로만 표현한다.

## Large-pool sweep (A)

Market 1개, Hikari pool=220, warmup=2,000, requests=1,000, round=1으로 실행했다.
모든 C에서 1,000/1,000이 성공했고 deadlock은 0이었다.

| C | M1 p50 | M1 p99 | Confirmation TPS | Hikari awaiting max | Row-lock waits | Row-lock time |
|--:|-------:|-------:|-----------------:|--------------------:|---------------:|--------------:|
| 1 | 42.6 ms | 54.9 ms | 23.5 /s | 0 | 0 | 0 ms |
| 10 | 205.9 ms | 357.4 ms | 49.1 /s | 0 | 1,879 | 160,297 ms |
| 50 | 858.9 ms | 1,654.4 ms | 56.2 /s | 0 | 1,929 | 815,303 ms |
| 100 | 1,639.1 ms | 3,170.0 ms | 56.9 /s | 0 | 1,890 | 1,592,137 ms |
| 200 | 2,363.3 ms | 3,714.4 ms | 82.4 /s | 0 | 1,800 | 2,108,780 ms |

C=50/100에서 약 56/s 구간이 보였지만 C=200에서 82.4/s로 다시 상승했다. 따라서
이 single-round sweep만으로 안정적인 saturation plateau나 `1 / TPS` proxy를
확정하지 않는다. 분명한 사실은 충분한 pool에서는 wait queue가 0인 채로
same-market row-lock contention과 tail latency가 증가했다는 점이다. 원본은
[`20260906-114219-expA-after-fix`](../../reports/bench/20260906-114219-expA-after-fix)
에 보존했다.

## Default-pool sweep (B)

동일 조건에서 Hikari pool=10으로 실행했다. 모든 단계가 1,000/1,000 성공했고
deadlock은 0이었다.

| C | M1 p50 | M1 p95 | M1 p99 | Confirmation TPS | Hikari active/awaiting | Row-lock waits | Row-lock time |
|--:|-------:|-------:|-------:|-----------------:|-----------------------:|---------------:|--------------:|
| 1 | 23.0 ms | 30.0 ms | 35.1 ms | 41.8 /s | 1 / 0 | 0 | 0 ms |
| 10 | 130.5 ms | 213.7 ms | 244.6 ms | 76.3 /s | 10 / 0 | 1,879 | 103,289 ms |
| 50 | 620.3 ms | 1,246.7 ms | 1,487.5 ms | 73.3 /s | 10 / 40 | 1,965 | 108,844 ms |
| 100 | 1,247.5 ms | 2,339.6 ms | 2,996.5 ms | 77.2 /s | 10 / 90 | 1,976 | 103,243 ms |
| 200 | 2,067.3 ms | 4,870.7 ms | 6,491.3 ms | 82.6 /s | 10 / 190 | 1,945 | 96,346 ms |

이 결과에서는 C가 pool 크기(10)를 넘을 때 pool queue와 긴 응답 시간이 같이
발생했다. 동시에 InnoDB row-lock wait/time도 높게 유지됐으므로 same-market DB
contention이 connection holding과 queueing에 기여하는 강한 관찰 근거가 있다.
다만 이 실험만으로 모든 대기를 row lock 하나로 환원하지는 않는다. Tomcat busy
계측은 이 환경에서 `-1`(사용 불가)이므로 그 지표는 해석하지 않았다. 원본은
[`20260906-115145-expB-after-fix`](../../reports/bench/20260906-115145-expB-after-fix)
에 보존했다.

## Migration policy

이번 변경은 schema/index 변경이나 deadlock retry가 아니다. retry, isolation level
변경, TX2 lock 전략 변경은 별도 원인 분석과 정책 검토가 필요한 후속 작업이다.
