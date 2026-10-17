-- Development seed for market (market).
-- Execute this file only against the market database; do not add USE statements.

START TRANSACTION;

DELETE FROM market_reputation_update WHERE id BETWEEN 962001 AND 962010;
DELETE FROM market_settlement_detail  WHERE id BETWEEN 942001 AND 942010;
DELETE FROM market_settlement         WHERE id BETWEEN 932001 AND 932010;
DELETE FROM market_price_history      WHERE id BETWEEN 927001 AND 927010;
DELETE FROM market_comment            WHERE id BETWEEN 980001 AND 980010;
DELETE FROM market_prediction         WHERE id BETWEEN 922001 AND 922030;
DELETE FROM market_option             WHERE id BETWEEN 912001 AND 912030;
DELETE FROM market                    WHERE id BETWEEN 902001 AND 902015;

-- ── Market ───────────────────────────────────────────────────
-- pool math 요약
--   ACTIVE  : fee_amount=0, settlement_pool=0 (아직 정산 전)
--   SETTLED : fee_rate=5%, fee_amount=total_pool*0.05, settlement_pool=total_pool-fee_amount
--   CLOSED  : fee_amount=0 (정산 대기)

INSERT INTO market (
    id, title, description, category, answer_type, metric_unit,
    judge_data_source, judge_criteria, judge_date, status,
    close_at, settle_due_at, settled_at, result_option_id, result_value, result_text,
    total_pool, fee_rate, fee_amount, settlement_pool,
    initial_virtual_liquidity, price_model, created_by, deleted_at, created_at, updated_at
) VALUES
-- 902001: 강남구 YES/NO ACTIVE - 가장 활발한 마켓 (6명 참여)
(902001, '이번 주 서울 강남구 아파트 매매가격지수, 상승할까요?',
 '한국부동산원 주간 데이터 기준. 강남3구 중 강남구 단독 지수 기준입니다.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '서울 강남구 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 6 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
 NULL, NULL, NULL, NULL,
 1000.00, 5.00, 0.00, 0.00, 500.00, 'POOL_SHARE', 2, NULL,
 DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),

-- 902002: 경기도 MULTIPLE_CHOICE ACTIVE
(902002, '이번 달 경기도 아파트 매매가격지수 변동률, 어느 구간일까요?',
 '3개 구간 중 하나를 예측하는 마켓입니다. 한국부동산원 월간 데이터 기준.',
 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'PERCENT',
 '한국부동산원 월간 아파트 가격동향',
 '경기도 아파트 매매가격지수 월간 변동률 구간 판정',
 DATE_ADD(CURDATE(), INTERVAL 14 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 14 DAY), DATE_ADD(NOW(), INTERVAL 15 DAY),
 NULL, NULL, NULL, NULL,
 200.00, 5.00, 0.00, 0.00, 400.00, 'POOL_SHARE', 3, NULL,
 DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),

-- 902003: 서울 전체 YES/NO ACTIVE (3명 참여)
(902003, '이번 주 서울 전체 아파트 매매가격지수, 상승세 이어갈까요?',
 '서울 전체 기준. 강남·강북 구분 없이 서울 평균 매매가격지수 변동률로 판정.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '서울 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, NULL, NULL, NULL,
 400.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 1, NULL,
 DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),

-- 902004: 서울 마포구 YES/NO ACTIVE (member 1 참여)
(902004, '이번 주 서울 마포구 아파트 매매가격지수, 오를까요?',
 '마포구 거주 또는 관심 있는 분들의 예측을 모읍니다.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '서울 마포구 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, NULL, NULL, NULL,
 250.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 1, NULL,
 DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),

-- 902005: 부산 해운대구 YES/NO ACTIVE
(902005, '이번 주 부산 해운대구 아파트 매매가격지수, 상승할까요?',
 '해운대구 아파트 시장 동향. 수영구·남구 인접 영향도 반영됩니다.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '부산 해운대구 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 6 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
 NULL, NULL, NULL, NULL,
 300.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 4, NULL,
 DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),

-- 902006: 인천 YES/NO ACTIVE
(902006, '이번 주 인천 아파트 매매가격지수, 상승할까요?',
 '인천 전체 기준. 연수구·남동구·부평구 등 전 구군 평균.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '인천 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, NULL, NULL, NULL,
 250.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 6, NULL,
 DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),

-- 902007: 경기 성남시 SETTLED YES 승리
--   total_pool=300, fee=15(5%), settlement_pool=285
--   YES(912014) 승리: member3 150P → 285P 수령 (수익 135P)
--   NO(912015) 패배:  member106 150P → 0P
(902007, '지난 주 경기 성남시 아파트 매매가격지수, 상승했을까요?',
 '이미 종료된 마켓입니다. 판교·분당 수요 견인 여부가 관건이었습니다.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '경기 성남시 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_SUB(CURDATE(), INTERVAL 7 DAY), 'SETTLED',
 DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY),
 DATE_SUB(NOW(), INTERVAL 5 DAY), 912014, NULL, 'YES',
 300.00, 5.00, 15.00, 285.00, 300.00, 'POOL_SHARE', 3, NULL,
 DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),

-- 902008: 서울 노원구 SETTLED NO(하락/보합) 승리
--   total_pool=300, fee=15(5%), settlement_pool=285
--   UP(912016) 패배:    member1 150P → 0P
--   DOWN_FLAT(912017) 승리: member5 150P → 285P 수령
(902008, '지난 주 서울 노원구 아파트 매매가격지수, 상승했을까요?',
 '이미 종료된 마켓입니다. 노원구는 실수요 중심으로 전세가 비율이 높은 지역입니다.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '서울 노원구 아파트 매매가격지수 주간 변동률이 0보다 크면 YES(상승), 아니면 NO(하락/보합)',
 DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'SETTLED',
 DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY),
 DATE_SUB(NOW(), INTERVAL 3 DAY), 912017, NULL, 'NO',
 300.00, 5.00, 15.00, 285.00, 300.00, 'POOL_SHARE', 1, NULL,
 DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),

-- 902009: 서울 서초구 MULTIPLE_CHOICE CLOSED (결과 확정, 정산 대기)
--   LOW(912019) 결과 확정. 아직 정산 미완료.
(902009, '지난 주 서울 서초구 아파트 매매가격지수 변동률, 어느 구간이었을까요?',
 '이미 마감된 마켓입니다. 결과가 확정되어 정산을 기다리고 있습니다.',
 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '서울 서초구 아파트 매매가격지수 주간 변동률 구간 판정',
 DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'CLOSED',
 DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY),
 NULL, 912019, NULL, 'LOW',
 350.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 2, NULL,
 DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),

-- 902010: 전국 YES/NO ACTIVE (2명 참여, 균형)
(902010, '이번 주 전국 아파트 매매가격지수, 상승할까요?',
 '전국 평균 기준. 수도권·지방 포함 종합 지수.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '전국 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, NULL, NULL, NULL,
 200.00, 5.00, 0.00, 0.00, 500.00, 'POOL_SHARE', 1, NULL,
 DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),

-- 902011: 대구 수성구 YES/NO ACTIVE (1명 참여)
(902011, '이번 주 대구 수성구 아파트 매매가격지수, 상승할까요?',
 '대구 대표 부촌 수성구 아파트 시장 예측.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '대구 수성구 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 6 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
 NULL, NULL, NULL, NULL,
 100.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 5, NULL,
 DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),

-- 902012: 서울 은평구 YES/NO ACTIVE (1명 참여)
(902012, '이번 주 서울 은평구 아파트 매매가격지수, 상승할까요?',
 '은평구 신축 공급 및 재개발 영향 반영 예정.',
 'PRICE_INDEX', 'YES_NO', 'PERCENT',
 '한국부동산원 주간 아파트 가격동향',
 '서울 은평구 아파트 매매가격지수 주간 변동률이 0보다 크면 YES',
 DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'ACTIVE',
 DATE_ADD(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, NULL, NULL, NULL,
 100.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 1, NULL,
 DATE_SUB(NOW(), INTERVAL 2 DAY), NOW());

-- ── Market Option ─────────────────────────────────────────────
-- 가격 = (virtual + real_해당) / 전체합
-- 계약수 = point_amount / 0.5 (예측 시점 기준 가격 0.5 가정)

INSERT INTO market_option (
    id, market_id, option_code, option_text, display_order,
    range_min, range_max, min_inclusive, max_inclusive,
    virtual_pool_amount, real_pool_amount, total_contract_quantity,
    current_price, prediction_count, is_result, created_at, updated_at
) VALUES
-- 902001 (YES real=750, NO real=250; virtual=500 each; total=2000)
-- price_YES = 1250/2000 = 0.625, price_NO = 750/2000 = 0.375
(912001, 902001, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 500.00, 750.00, 1500.00000000, 0.62500000, 4, FALSE, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),
(912002, 902001, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 500.00, 250.00,  500.00000000, 0.37500000, 2, FALSE, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),

-- 902002 MULTIPLE_CHOICE (경기도, 참여 없음)
(912003, 902002, 'NEGATIVE', '0% 미만',             1, NULL,   0.0000, TRUE,  FALSE, 400.00, 0.00, 0.00, 0.33333333, 0, FALSE, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),
(912004, 902002, 'LOW',      '0% 이상 ~ 0.3% 미만', 2, 0.0000, 0.3000, TRUE,  FALSE, 400.00, 0.00, 0.00, 0.33333333, 0, FALSE, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),
(912005, 902002, 'HIGH',     '0.3% 이상',           3, 0.3000, NULL,   TRUE,  FALSE, 400.00, 0.00, 0.00, 0.33333333, 0, FALSE, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),

-- 902003 (YES real=300, NO real=100; virtual=300 each; total=1000)
-- price_YES = 600/1000 = 0.6, price_NO = 400/1000 = 0.4
(912006, 902003, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 300.00, 300.00, 600.00000000, 0.60000000, 2, FALSE, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),
(912007, 902003, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 300.00, 100.00, 200.00000000, 0.40000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),

-- 902004 (YES real=150, NO real=100; virtual=300 each; total=850)
-- price_YES = 450/850 = 0.52941176, price_NO = 400/850 = 0.47058824
(912008, 902004, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 300.00, 150.00, 300.00000000, 0.52941176, 2, FALSE, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),
(912009, 902004, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 300.00, 100.00, 200.00000000, 0.47058824, 1, FALSE, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),

-- 902005 (YES real=200, NO real=100; virtual=300 each; total=900)
-- price_YES = 500/900 = 0.55555556, price_NO = 400/900 = 0.44444444
(912010, 902005, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 300.00, 200.00, 400.00000000, 0.55555556, 1, FALSE, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),
(912011, 902005, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 300.00, 100.00, 200.00000000, 0.44444444, 1, FALSE, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),

-- 902006 (YES real=100, NO real=150; virtual=300 each; total=850)
-- price_YES = 400/850 = 0.47058824, price_NO = 450/850 = 0.52941176
(912012, 902006, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 300.00, 100.00, 200.00000000, 0.47058824, 1, FALSE, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
(912013, 902006, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 300.00, 150.00, 300.00000000, 0.52941176, 1, FALSE, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),

-- 902007 SETTLED YES 승리 (real=150 each; virtual=300 each; price 0.5 고정)
(912014, 902007, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 300.00, 150.00, 300.00000000, 0.50000000, 1, TRUE,  DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),
(912015, 902007, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 300.00, 150.00, 300.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),

-- 902008 SETTLED DOWN_FLAT 승리 (real=150 each; virtual=300 each; price 0.5 고정)
(912016, 902008, 'UP',        '상승',     1, NULL, NULL, TRUE, FALSE,
 300.00, 150.00, 300.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),
(912017, 902008, 'DOWN_FLAT', '하락/보합', 2, NULL, NULL, TRUE, FALSE,
 300.00, 150.00, 300.00000000, 0.50000000, 1, TRUE,  DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),

-- 902009 CLOSED 서초구 MULTIPLE_CHOICE (LOW 결과 확정)
-- NEGATIVE real=100, LOW real=100, HIGH real=150; virtual=300 each; total=1250
-- price_NEG = 400/1250=0.32, price_LOW = 400/1250=0.32, price_HIGH = 450/1250=0.36
(912018, 902009, 'NEGATIVE', '0% 미만',             1, NULL,   0.0000, TRUE,  FALSE,
 300.00, 100.00, 200.00000000, 0.32000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),
(912019, 902009, 'LOW',      '0% 이상 ~ 0.3% 미만', 2, 0.0000, 0.3000, TRUE,  FALSE,
 300.00, 100.00, 200.00000000, 0.32000000, 1, TRUE,  DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),
(912020, 902009, 'HIGH',     '0.3% 이상',           3, 0.3000, NULL,   TRUE,  FALSE,
 300.00, 150.00, 300.00000000, 0.36000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),

-- 902010 전국 균형 (YES real=100, NO real=100; virtual=500 each; total=1200)
-- price = 600/1200 = 0.5 균형
(912021, 902010, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 500.00, 100.00, 200.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),
(912022, 902010, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 500.00, 100.00, 200.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),

-- 902011 대구수성구 (YES real=100; virtual=300 each; total=700)
-- price_YES = 400/700 = 0.57142857, price_NO = 300/700 = 0.42857143
(912023, 902011, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 300.00, 100.00, 200.00000000, 0.57142857, 1, FALSE, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
(912024, 902011, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 300.00,   0.00,   0.00000000, 0.42857143, 0, FALSE, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),

-- 902012 은평구 (YES real=100; virtual=300 each; total=700)
(912025, 902012, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE,
 300.00, 100.00, 200.00000000, 0.57142857, 1, FALSE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(912026, 902012, 'NO',  'NO',  2, NULL, NULL, TRUE, FALSE,
 300.00,   0.00,   0.00000000, 0.42857143, 0, FALSE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW());

-- ── Market Prediction ─────────────────────────────────────────
-- contract_quantity = point_amount / 0.5 (예측 시점 price=0.5 기준)

INSERT INTO market_prediction (
    id, market_id, option_id, member_id, point_amount,
    price_snapshot, contract_quantity,
    expected_payout_per_contract_snapshot, expected_multiplier_snapshot,
    status, point_spend_idempotency_key, attempt_no,
    settled_amount, refund_amount, fail_reason, created_at, updated_at
) VALUES
-- ── 902001 강남구 (6명) ──────────────────────────────────────
(922001, 902001, 912001, 2,   200.00, 0.50000000, 400.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902001:member:2:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 11 DAY)),
(922002, 902001, 912001, 1,   100.00, 0.52173913, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902001:member:1:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY)),
(922003, 902001, 912002, 4,   150.00, 0.47826087, 300.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902001:member:4:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)),
(922004, 902001, 912001, 101, 300.00, 0.55172414, 600.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902001:member:101:attempt:1', 1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 8 DAY)),
(922005, 902001, 912002, 102, 100.00, 0.44827586, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902001:member:102:attempt:1', 1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY)),
(922006, 902001, 912001, 103, 150.00, 0.62857143, 300.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902001:member:103:attempt:1', 1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)),

-- ── 902003 서울전체 (3명) ────────────────────────────────────
(922007, 902003, 912006, 1,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902003:member:1:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY)),
(922008, 902003, 912006, 2,   200.00, 0.50000000, 400.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902003:member:2:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)),
(922009, 902003, 912007, 3,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902003:member:3:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),

-- ── 902004 마포구 (3명, member 1 포함) ──────────────────────
(922010, 902004, 912008, 1,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902004:member:1:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)),
(922011, 902004, 912009, 7,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902004:member:7:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
(922012, 902004, 912008, 8,    50.00, 0.52941176, 100.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902004:member:8:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY)),

-- ── 902005 부산해운대구 (2명) ────────────────────────────────
(922013, 902005, 912010, 4,   200.00, 0.50000000, 400.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902005:member:4:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
(922014, 902005, 912011, 9,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902005:member:9:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY)),

-- ── 902006 인천 (2명) ────────────────────────────────────────
(922015, 902006, 912012, 6,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902006:member:6:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY)),
(922016, 902006, 912013, 3,   150.00, 0.50000000, 300.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902006:member:3:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),

-- ── 902007 성남시 SETTLED YES 승리 ──────────────────────────
-- member3 YES → 285P 수령, member106 NO → 0P
(922017, 902007, 912014, 3,   150.00, 0.50000000, 300.00000000, NULL, NULL,
 'SETTLED', 'REAL_SPEND:market:902007:member:3:attempt:1',   1, 285.00, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 13 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
(922018, 902007, 912015, 106, 150.00, 0.50000000, 300.00000000, NULL, NULL,
 'SETTLED', 'REAL_SPEND:market:902007:member:106:attempt:1', 1,   0.00, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 13 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),

-- ── 902008 노원구 SETTLED DOWN_FLAT 승리 ────────────────────
-- member1 UP → 0P 패배, member5 DOWN_FLAT → 285P 수령
(922019, 902008, 912016, 1,   150.00, 0.50000000, 300.00000000, NULL, NULL,
 'SETTLED', 'REAL_SPEND:market:902008:member:1:attempt:1',   1,   0.00, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
(922020, 902008, 912017, 5,   150.00, 0.50000000, 300.00000000, NULL, NULL,
 'SETTLED', 'REAL_SPEND:market:902008:member:5:attempt:1',   1, 285.00, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),

-- ── 902009 서초구 CLOSED (정산 대기) ────────────────────────
(922021, 902009, 912019, 2,   100.00, 0.32000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902009:member:2:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 8 DAY)),
(922022, 902009, 912020, 1,   150.00, 0.36000000, 300.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902009:member:1:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY)),
(922023, 902009, 912018, 104, 100.00, 0.32000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902009:member:104:attempt:1', 1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)),

-- ── 902010 전국 (2명) ────────────────────────────────────────
(922024, 902010, 912021, 1,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902010:member:1:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
(922025, 902010, 912022, 5,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902010:member:5:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),

-- ── 902011 대구수성구 (1명) ──────────────────────────────────
(922026, 902011, 912023, 5,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902011:member:5:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),

-- ── 902012 은평구 (1명) ──────────────────────────────────────
(922027, 902012, 912025, 1,   100.00, 0.50000000, 200.00000000, NULL, NULL,
 'CONFIRMED', 'REAL_SPEND:market:902012:member:1:attempt:1',   1, NULL, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY));

-- ── Market Price History (902001 가격 변동 이력) ─────────────

INSERT INTO market_price_history (
    id, market_id, option_id, prediction_id, event_type,
    price_before, price_after, real_pool_before, real_pool_after,
    contract_quantity_before, contract_quantity_after, created_at, updated_at
) VALUES
-- member 101 (300P YES) 예측 직후 가격 변화
(927001, 902001, 912001, 922004, 'PREDICTION_CONFIRMED',
 0.55172414, 0.62857143, 300.00, 600.00, 600.00000000, 1200.00000000,
 DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 8 DAY)),
(927002, 902001, 912002, 922004, 'PREDICTION_CONFIRMED',
 0.44827586, 0.37142857, 150.00, 150.00, 300.00000000,  300.00000000,
 DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 8 DAY)),
-- member 103 (150P YES) 예측 직후 가격 변화
(927003, 902001, 912001, 922006, 'PREDICTION_CONFIRMED',
 0.60000000, 0.62500000, 600.00, 750.00, 1200.00000000, 1500.00000000,
 DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)),
(927004, 902001, 912002, 922006, 'PREDICTION_CONFIRMED',
 0.40000000, 0.37500000, 250.00, 250.00,  500.00000000,  500.00000000,
 DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY));

-- ── Market Settlement ─────────────────────────────────────────

INSERT INTO market_settlement (
    id, market_id, result_option_id, total_pool, fee_rate, fee_amount,
    settlement_pool, winning_contract_quantity, payout_per_contract,
    burned_point_amount, status, settled_by, settled_at, created_at, updated_at
) VALUES
-- 902007 YES 승리: 300P pool, 5% fee, payout = 285/300 = 0.95
(932001, 902007, 912014, 300.00, 5.00, 15.00, 285.00, 300.00000000, 0.95000000,
 0.00, 'COMPLETED', 999, DATE_SUB(NOW(), INTERVAL 5 DAY),
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
-- 902008 DOWN_FLAT 승리
(932002, 902008, 912017, 300.00, 5.00, 15.00, 285.00, 300.00000000, 0.95000000,
 0.00, 'COMPLETED', 999, DATE_SUB(NOW(), INTERVAL 3 DAY),
 DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY));

-- ── Market Settlement Detail ──────────────────────────────────

INSERT INTO market_settlement_detail (
    id, settlement_id, prediction_id, member_id,
    original_point_amount, contract_quantity, payout_per_contract,
    settled_amount, profit_amount, status, idempotency_key, fail_reason,
    created_at, updated_at
) VALUES
-- 902007 YES 승리: member3 (150P → 285P, 수익 135P)
(942001, 932001, 922017, 3,
 150.00, 300.00000000, 0.95000000, 285.00, 135.00,
 'SUCCESS', 'MARKET_SETTLEMENT_REWARD:market:902007:prediction:922017:member:3', NULL,
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
-- 902007 NO 패배: member106 (150P → 0P, 손실 150P)
(942002, 932001, 922018, 106,
 150.00, 300.00000000, 0.95000000, 0.00, -150.00,
 'SUCCESS', 'MARKET_SETTLEMENT_REWARD:market:902007:prediction:922018:member:106', NULL,
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
-- 902008 UP 패배: member1 (150P → 0P, 손실 150P)
(942003, 932002, 922019, 1,
 150.00, 300.00000000, 0.95000000, 0.00, -150.00,
 'SUCCESS', 'MARKET_SETTLEMENT_REWARD:market:902008:prediction:922019:member:1', NULL,
 DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
-- 902008 DOWN_FLAT 승리: member5 (150P → 285P, 수익 135P)
(942004, 932002, 922020, 5,
 150.00, 300.00000000, 0.95000000, 285.00, 135.00,
 'SUCCESS', 'MARKET_SETTLEMENT_REWARD:market:902008:prediction:922020:member:5', NULL,
 DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY));

-- ── Market Reputation Update ──────────────────────────────────

INSERT INTO market_reputation_update (
    id, market_id, prediction_id, member_id, is_correct,
    status, attempt_no, last_error_code, last_error_message, created_at, updated_at
) VALUES
(962001, 902007, 922017, 3,   TRUE,  'SUCCESS', 1, NULL, NULL, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
(962002, 902007, 922018, 106, FALSE, 'SUCCESS', 1, NULL, NULL, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
(962003, 902008, 922019, 1,   FALSE, 'SUCCESS', 1, NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
(962004, 902008, 922020, 5,   TRUE,  'SUCCESS', 1, NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW());

-- ── Market Comment ────────────────────────────────────────────

INSERT INTO market_comment (id, market_id, member_id, content, deleted_at, created_at, updated_at) VALUES
(980001, 902001, 2,   '강남구 최근 거래량도 늘고 있어서 이번 주도 소폭 상승할 것 같아요. YES로 참여했습니다.', NULL, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),
(980002, 902001, 4,   '금리 동결 기조가 유지되는 한 강남 아파트가 내리긴 어렵죠. 저도 YES 입장이에요.', NULL, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),
(980003, 902001, 103, '국내외 경기 불확실성이 있어서 저는 NO로 갔어요. 하락 전환 가능성도 열어둬야 한다고 봐요.', NULL, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),
(980004, 902004, 1,   '마포구 공덕 쪽 신축 분양 수요가 꾸준한 것 같아서 YES로 참여했어요. 같이 응원해봐요!', NULL, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
(980005, 902007, 3,   '성남시는 판교 IT 수요가 버팀목이 돼서 YES가 맞을 거라고 봤는데 결국 맞았네요. 다음에도 도전!', NULL, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW());

COMMIT;

-- ============================================================
-- 3. INSIGHT DB — market_prediction_result
-- ============================================================
