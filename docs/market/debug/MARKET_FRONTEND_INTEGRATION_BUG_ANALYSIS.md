# Market Frontend Integration Bug Analysis

## 1. 분석 목적

프론트 연동 중 관찰된 예측 기록 소실, 포인트/Market 불일치, 반영 지연, 로그인 지연을 코드 근거로 분해하고 안전한 DB/API 검증 지점을 정의한다. 이 문서는 원인 확정 전 분석이며 서비스 로직 수정은 범위 밖이다.

## 2. 현재 발생한 증상

- 포인트는 차감됐지만 예측 참여가 없거나 사라져 보인다.
- 예측 참여 직후 Market 가격·풀·내 예측이 늦게 보인다.
- 잔액 10P에서 10P 참여 후에도 UI 잔액이 10P로 남아 보인다.
- 로그인이 비정상적으로 오래 걸린다.

## 3. 전체 서비스 구조 요약

- `todongsan-frontend`: Axios + TanStack Query. 기본 API 주소는 `http://localhost:9000`이다.
- `api-gateway`: 9000 포트에서 `/api/v1/markets/**`를 Market(8082), `/api/v1/members/**`와 `/api/v1/points/**`를 Member-Point(8080)로 라우팅한다. 인증 후 `X-Member-Id`를 하위 서비스에 전달한다.
- `market-service`: Market DB의 `market`, `market_option`, `market_prediction`, `market_price_history`를 소유하고 Member-Point 내부 API를 동기 호출한다.
- `member-point-service`: Member-Point DB의 `member.point_balance`, `point_history`를 소유한다.
- MySQL: `market`과 `memberpoint`는 서로 다른 MySQL 인스턴스에 있다. 서비스 간 DB 트랜잭션은 없다.

근거: `api-gateway/src/main/resources/application.yml`, `market-service/src/main/resources/application.yml`, 각 서비스의 `src/main/resources/db/migration/V1__baseline.sql`.

## 4. Market 예측 참여 처리 흐름

1. `MarketController.createPrediction()`이 `POST /api/v1/markets/{marketId}/predictions`, `X-Member-Id`, `Idempotency-Key`와 `{marketOptionId, pointAmount}`를 받는다.
2. `MarketPredictionService.createPrediction()`은 `MarketPredictionTransactionService.createPendingPrediction()`을 호출한다.
3. `createPendingPrediction()`은 별도 `@Transactional` 경계에서 Market/옵션/금액을 검증하고 `(market_id, member_id)`를 `FOR UPDATE` 조회한 뒤 `market_prediction`을 `POINT_PENDING`으로 커밋한다. 키에는 `:attempt:{n}`이 붙는다. 기존 `FAILED` 행은 같은 행을 재시도 상태로 갱신한다.
4. 첫 트랜잭션 커밋 뒤 `MarketPredictionService`가 트랜잭션 밖에서 `MemberPointClient.spend()`를 동기 호출한다. 요청은 `SPEND_MARKET`, `MARKET_PREDICTION`, `referenceId=prediction.id`다.
5. 차감 성공 뒤 `confirmPrediction()`의 새 `@Transactional` 경계에서 Market, 옵션들, Prediction을 잠근다. 선택 옵션의 실제 풀·계약 수·예측 수, 전체 옵션 가격, `market.total_pool`을 갱신하고 옵션별 `market_price_history`를 넣은 뒤 Prediction을 `CONFIRMED`로 바꾼다. 이 갱신들은 한 로컬 트랜잭션이므로 정상 커밋 시 `GET /api/v1/markets/{id}`와 `/predictions/me`가 즉시 새 DB 값을 읽을 수 있다.

핵심 비원자 구간은 Member-Point 차감 성공과 Market confirm 트랜잭션 사이이다. 이 사이에 프로세스 종료, 응답 유실 또는 confirm 충돌이 생기면 포인트는 성공했지만 Market 행은 `POINT_PENDING`으로 남을 수 있다. timeout/외부 오류는 catch 후 `POINT_UNKNOWN`으로 바뀐다. confirm 자체가 실패하면 현재 호출 경로는 이를 `POINT_UNKNOWN`으로 자동 전환하지 않으므로 `POINT_PENDING`이 남고 클라이언트에는 오류가 갈 수 있다.

근거: `MarketController.createPrediction()`, `MarketPredictionService.createPrediction()`, `MarketPredictionTransactionService.createPendingPrediction()/confirmPredictionInternal()`, `MarketMapper.xml`의 `insertPrediction`, `updateMarketTotalPool`, `updateMarketOptionPoolsAndPrice`, `insertPriceHistoryRows`, `updatePredictionConfirmed`.

## 5. Member-Point 차감 처리 흐름

`PointInternalController.spend()` → `PointInternalServiceImpl.spend()`은 하나의 `@Transactional(noRollbackFor = CustomException.class)` 안에서 처리된다.

- `MemberRepository.spendPoint()`의 조건부 갱신으로 잔액을 차감한다. 영향 행이 0이면 `FAILED/POINT_INSUFFICIENT` 이력을 저장하고 예외를 던지되 `noRollbackFor`로 실패 이력을 유지한다.
- 성공하면 갱신된 잔액을 다시 읽어 `point_history`에 양수 `amount`, 차감 후 `balance_snapshot`, `SUCCEEDED`, 참조 정보와 멱등성 키를 저장한다.
- `point_history.idempotency_key`는 UNIQUE다. 같은 키·같은 request hash는 기존 성공 응답(또는 기존 부족 실패)을 재사용하고, 같은 키·다른 내용은 `IDEMPOTENCY_KEY_CONFLICT`다.
- `/internal/api/v1/points/transactions?Idempotency-Key=...` 조회는 이력을 `PROCESSED`, `FAILED`, `NOT_FOUND`로 변환한다.

따라서 Member-Point 트랜잭션이 커밋됐다면 balance와 성공 history는 함께 남는다. 다만 Market 트랜잭션과는 분리되어 있으므로 양 서비스 간 원자성은 대사로 보완한다.

근거: `PointInternalServiceImpl.spend()/getTransaction()`, `PointHistory`, `MemberRepository.spendPoint()`, `member-point-service/src/main/resources/db/migration/V1__baseline.sql`.

## 6. 상태 전이 분석

| 상태 | 생성/전이 조건 | 다음 상태 |
|---|---|---|
| `POINT_PENDING` | Prediction 최초 생성 또는 기존 `FAILED` 재시도 | 차감 성공 후 `CONFIRMED`; 잔액 부족 후 `FAILED`; 외부 호출 불확실 후 `POINT_UNKNOWN`; 3분 stale 후 대사 |
| `POINT_UNKNOWN` | spend timeout/unavailable/external 오류, 또는 대사 상태 조회 오류 | 대사 결과 `PROCESSED`면 `CONFIRMED`, `FAILED/NOT_FOUND`면 `FAILED`, 계속 불명확하면 유지 |
| `CONFIRMED` | 포인트 성공 후 Market 풀·옵션·가격 이력까지 한 트랜잭션으로 반영 성공 | 정산 시 `SETTLED`, 무효 처리 시 환불 계열 |
| `FAILED` | 잔액 부족, 대사 결과 실패/없음 | 동일 Market 재참여 시 attempt 증가 후 `POINT_PENDING` |
| `REFUND_PENDING/REFUND_UNKNOWN/REFUNDED` | Market 무효화 후 환불 처리 상태 | 이번 참여 차감 지연 분석에서는 보조 범위 |
| `SETTLED` | 정산 완료 | 종결 상태 |

`POINT_UNKNOWN`은 대사 대상에 즉시 포함되지만, `POINT_PENDING`은 `updated_at <= now - 3분`일 때만 포함된다.

## 7. Reconciliation/Scheduler 분석

- `PredictionSpendReconciliationScheduler.run()`은 `market.scheduler.prediction-reconciliation.enabled=true`일 때 동작한다.
- 기본 fixed delay는 60,000ms, batch limit은 100이다(`application.yml`). fixed delay이므로 이전 실행 종료 후 60초 뒤 다음 실행이다.
- `PredictionSpendReconciliationService.PENDING_STALE_MINUTES=3`이다.
- Mapper는 모든 `POINT_UNKNOWN`과 3분 이상 stale인 `POINT_PENDING`을 오래된 순서로 조회한다.
- Member-Point 결과가 `PROCESSED`면 confirm 트랜잭션으로 Market 집계/가격 이력까지 반영한다. `FAILED` 또는 `NOT_FOUND`면 Prediction을 `FAILED`로 바꾼다. 조회 오류/불명확 결과는 `POINT_UNKNOWN`으로 둔다.
- 수동 내부 API는 `POST /api/v1/internal/markets/predictions/reconcile?limit=100`이다.

따라서 막 생성된 `POINT_PENDING`이 서버 중단으로 남으면 최소 3분 stale + 다음 scheduler 실행(통상 최대 약 1분 추가)까지 체감 지연이 가능하다. `POINT_UNKNOWN`은 다음 scheduler 주기부터 대상이지만 외부 상태가 계속 불명확하면 계속 남는다. 서버가 꺼져 있거나 scheduler 비활성화면 자동 대사는 없다.

## 8. dev seed와 과거 부분 삭제 사고 분석

최초 분석 당시 legacy Market seed 안의 삭제 로직이 원인이었다. 현재 seed는 `scripts/seed/frontend/market.sql`에 있으며 `INSERT IGNORE` 기반 추가 전용이다.

과거 삭제 SQL은 source tree에서 제거했다. 아래는 과거 위험 범위 설명이며 일반 초기화나 테스트 절차가 아니다.

| 테이블 | 삭제 조건 요약 |
|---|---|
| `market_reputation_update` | id `960001~960099`, market `900001~900099`, prediction `920001~920399` |
| `market_refund_detail` | id `950001~950199`, prediction `920001~920399`, void `970001~970099` |
| `market_settlement_detail` | id `940001~940199`, prediction `920001~920399`, settlement `930001~930099` |
| `market_settlement` | id `930001~930099` 또는 market `900001~900099` |
| `market_price_history` | id `925001~925399`, market `900001~900099`, prediction `920001~920399` |
| `market_void` | id `970001~970099` 또는 market `900001~900099` |
| `market_prediction` | id `920001~920399` 또는 market `900001~900099` |
| `market_option` | id `910001~910299` 또는 market `900001~900099` |
| `market` | id `900001~900099` |

이 과거 SQL은 Market만 지우고 Member-Point를 수정하지 않아 “포인트는 차감됐는데 예측 기록은 사라짐”을 만들었다. 현재 정책에서는 이를 실행하지 않고, 깨끗한 로컬 DB가 필요하면 모든 스키마를 함께 지우는 명시적 MySQL volume reset을 사용한다.

AUTO_INCREMENT는 별도로 보정하지 않으므로 Admin API 생성 Market이 9000xx일 수 있고 로컬 실험 데이터로 허용한다. 전체 volume reset 후에는 카카오 회원·포인트·히스토리도 초기화되므로 재로그인하고 새 memberId를 다시 확인해야 한다.

## 9. 프론트 캐시 분석

예측 mutation은 `src/entities/prediction/model/useCreateMarketPredictionMutation.ts`, 실제 성공 처리는 `src/features/market-prediction/create/ui/CreateMarketPredictionPanel.tsx`에 있다.

성공 후 invalidate하는 키:

- 현재 Market의 `predictionKeys.myMarketPrediction`
- 내 예측 목록 root
- Market 상세
- 해당 Market 가격 이력 root
- Market 목록 root

누락된 키:

- `pointKeys.balance()` 및 point history
- `memberKeys.me()`(프로필 응답에도 `pointBalance`가 있음)

따라서 `CONFIRMED` + 실제 잔액 차감 상태에서도 화면 잔액이 이전 캐시를 표시할 가능성이 높다. Market 상세/내 예측은 성공 경로에서 invalidate되므로 정상 응답 뒤 늦는 현상은 네트워크·백엔드 대사·refetch 실패를 추가 확인해야 한다. 불확실 오류 경로는 내 예측만 invalidate하며 Market 상세/가격/잔액은 갱신하지 않는다. `useMyMarketPredictionQuery()`는 반환 데이터가 pending/unknown일 때 3초 polling하지만 최초 조회가 404/null이면 polling하지 않는다.

`auth.store.ts`의 `logout()`과 401 handler는 Zustand 인증 값만 지우고 전역 QueryClient를 clear/remove하지 않는다. 명시적 로그아웃 호출부에서도 Query cache clear가 검색되지 않았다. 같은 브라우저에서 다른 계정으로 재로그인하면 회원별 데이터가 회원 ID 없는 동일 query key로 재사용될 위험이 있다.

## 10. 로그인 지연 후보

로그인은 Member-Point의 `MemberAuthController` → `MemberAuthServiceImpl.kakaoLogin()`에 있다. 이 메서드는 `@Transactional` 상태에서 첫 줄에 `KakaoOAuthService.getUserInfo()`를 호출한다. 외부 카카오 `/v2/user/me` 호출은 WebClient를 사용하지만 마지막에 `.block()`하므로 요청 스레드 관점에서 동기 대기다. `WebClientConfig`에는 base URL만 있고 connect/read/response timeout 설정이 없다.

따라서 외부 네트워크 지연이 DB 트랜잭션 범위 안에서 로그인 전체를 지연시킬 수 있다는 것은 코드 근거가 있는 후보다. 실제 지연 원인 확정에는 구간별 계측이 필요하다. 이 코드는 `member-point-service/**`로 Market 담당자의 직접 수정 범위가 아니다.

## 11. 원인 후보별 판단표

| 증상 | 원인 후보 | 근거 파일/메서드 | 검증 방법 | 우선순위 |
|---|---|---|---|---|
| 포인트 차감, 예측 없음 | 과거 Market-only 부분 삭제 또는 전체 초기화 시점 혼동 | Member-Point와 Market 생명주기 불일치 | 초기화 기록과 history/Prediction 비교 | 최상 |
| 포인트 차감, pending 유지 | 차감 후 confirm 전 중단/충돌 | `MarketPredictionService.createPrediction()`의 분리 트랜잭션 | TC-02/05, idempotency key 교차 조회 | 최상 |
| 반영까지 3~4분 | stale 대사 대기 | `PENDING_STALE_MINUTES=3`, fixed delay 60초 | TC-03/05 | 상 |
| UI 잔액만 그대로 | point/member query invalidate 누락 | `CreateMarketPredictionPanel.handlePredict()` | TC-06, Network/Query Devtools | 최상 |
| 재로그인 뒤 이전 표시 | Query cache 미정리 | `auth.store.logout()`, `providers.tsx` | TC-04/06 | 상 |
| Market 화면만 늦음 | POST 오류 경로 invalidate 범위 또는 refetch 실패 | `onError`, query polling 조건 | TC-03/06 | 중 |
| 로그인 지연 | transaction 안의 timeout 없는 blocking OAuth | `MemberAuthServiceImpl.kakaoLogin()`, `KakaoOAuthService.getUserInfo()` | TC-07 | 상(타 서비스) |

## 12. 현재 단계 결론

확정 가능한 것:

- 최초 원인은 destructive seed 재실행으로 확인됐다. 현재는 add-only seed만 사용하고 부분 cleanup은 비활성화했으며, 완전 초기화는 전체 MySQL volume reset으로 수행한다.
- 참여 성공 UI 경로에 포인트 잔액·회원 프로필 invalidate가 없다.
- 로그아웃은 Query cache를 지우지 않는다.
- 포인트 차감과 Market confirm은 분산 원자 트랜잭션이 아니며 대사로 보완한다.
- 로그인 외부 호출은 DB transaction 안에서 timeout 설정 없이 `.block()`한다.

DB/API 테스트가 필요한 것:

- 향후 개별 사례가 초기화 시점 혼동, confirm 중단, 대사 지연 중 무엇인지.
- `point_history`와 balance가 실제로 일치하는지, scheduler가 실행 중인지.
- POST 직후 각 API/DB 반영 시간과 오류 응답.

브라우저에서 직접 확인할 것:

- 성공 직후 point balance/profile 요청이 실제로 발생하지 않는지.
- invalidate된 Market/Prediction 요청의 응답과 렌더 상태.
- 로그아웃/다른 계정 로그인 전후 Query cache 잔존 여부.
