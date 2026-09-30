# Market Prediction Deadlock Reproduction

이 문서는 deadlock을 고치기 위한 절차가 아니라, 동일 Market 동시 예측에서 발생한 InnoDB deadlock의 lock graph와 애플리케이션 transaction phase를 수집하기 위한 절차다.

## 실행

테스트 MySQL command가 변경됐으므로 기존 컨테이너를 재생성한다.

```powershell
docker compose -f infra/docker-compose.test.yml up -d --force-recreate market-mysql-it
& 'C:\Program Files\Git\bin\bash.exe' scripts/bench/run-deadlock-repro.sh
```

기본 조건은 Market 1개, Fake Member-Point, spend delay 0ms, 요청 100건, C=2/3/5/10, Hikari pool 60이다. `DEADLOCK_REPRO_REQUESTS`, `DEADLOCK_REPRO_POOL`, `DEADLOCK_REPRO_CONCURRENCIES`로 조건을 바꿀 수 있다. `DEADLOCK_REPRO_LABEL=deadlock-after-fix`을 주면 기존 증거와 별도 report directory에 저장한다.

## 산출물

`reports/bench/<timestamp>-deadlock/` 아래에 다음이 생성된다.

- `c*-summary.log`: 성공/실패, 애플리케이션 예외 분류, `Innodb_deadlocks`, row lock delta
- `c*-innodb-status.txt`: 해당 단계 직후의 `LATEST DETECTED DEADLOCK`
- `c*-phase-trace.log`: requestId, Market, Member, Prediction ID, transaction phase

`SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks'`를 먼저 조회한다. MySQL 8.4에서는 이
상태 변수가 노출되지 않으므로, 재현 스크립트가 disposable `market_test_user`에만
`performance_schema.events_errors_summary_global_by_error` 조회 권한을 부여하고
`ER_LOCK_DEADLOCK.SUM_ERROR_RAISED`를 같은 출력 항목의 fallback으로 사용한다.
권한을 부여하지 못하면 값은 `n/a`로 표시하며, 이를 0건으로 해석하지 않는다.
- `mysql-deadlocks.log`: `innodb-print-all-deadlocks=ON`으로 기록된 MySQL 원본 deadlock log

`SHOW ENGINE INNODB STATUS`는 최신 deadlock 하나만 보여준다. cycle 판정에는 MySQL 원본 log와 같은 requestId의 phase trace를 함께 사용한다.

## 주의

- 이 mode는 `-Dbench.deadlockDebug=true`에서만 phase trace와 application-side deadlock 분류를 활성화한다. S/A/B 등 성능 벤치는 기본 OFF다.
- 응답 body의 `INTERNAL_ERROR`만으로 deadlock을 추정하지 않는다. `DeadlockLoserDataAccessException`을 application-side에서 requestId로 기록한 경우만 `DEADLOCK`으로 분류한다.
- retry, transaction boundary, lock 순서, isolation level은 이 관찰 작업에서 변경하지 않는다.
