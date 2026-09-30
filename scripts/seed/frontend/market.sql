-- DEV ONLY
-- Frontend local test seed data for Market scenarios.
-- Do not run in production.
--
-- Purpose:
-- - Market list/detail/quote/prediction frontend testing
-- - My page prediction list and filter testing
-- - displayStatus/canPredict UI testing
--
-- Default dev member:
-- - devMemberId = 1
-- - Direct participation test markets intentionally do not include member_id = 1 predictions.
--
-- Run with scripts/seed/Invoke-TodongsanSeed.ps1 -Scenario frontend.
--
-- ADD-ONLY POLICY:
-- - This seed never deletes or updates existing Market data.
-- - INSERT IGNORE preserves existing rows, including real local experiment Predictions.
-- - This file is for adding test Markets, not cleanup or database initialization.
-- - Do not partially delete Market data. Use the documented full MySQL volume
--   reset procedure when a clean local database is required.

START TRANSACTION;

SET @dev_member_id := 1;

INSERT IGNORE INTO market (
    id, title, description, category, answer_type, metric_unit,
    region_scope, region_sido, region_sigu,
    judge_data_source, judge_criteria, judge_date, status,
    close_at, settle_due_at, settled_at, result_option_id, result_value, result_text,
    total_pool, fee_rate, fee_amount, settlement_pool,
    initial_virtual_liquidity, price_model, created_by, deleted_at, created_at, updated_at
) VALUES
    (900001, '[DEV] 직접 참여 가능 - YES/NO', 'Quote, 예측 참여, 상세 화면 테스트용. devMemberId=1 예측 없음.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'REGIONAL', '서울', '강남구',
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 변동률이 0보다 크면 YES', DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 8 DAY), NULL, NULL, NULL, NULL,
     150.00, 5.00, 0.00, 0.00, 500.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),
    (900002, '[DEV] 고유동성 Market - 가격 변동 작음', '가상 유동성이 커서 100P 참여 시 가격 변동이 작게 보이는 시나리오.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'NATIONAL', '전국', NULL,
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 상승 여부', DATE_ADD(CURDATE(), INTERVAL 10 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 10 DAY), DATE_ADD(NOW(), INTERVAL 11 DAY), NULL, NULL, NULL, NULL,
     1000.00, 5.00, 0.00, 0.00, 10000.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW()),
    (900003, '[DEV] 저유동성 Market - 가격 변동 큼', '유동성이 작아서 같은 100P 참여에도 가격 변동이 크게 보이는 시나리오.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'NON_REGIONAL', NULL, NULL,
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 상승 여부', DATE_ADD(CURDATE(), INTERVAL 9 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 9 DAY), DATE_ADD(NOW(), INTERVAL 10 DAY), NULL, NULL, NULL, NULL,
     0.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),
    (900004, '[DEV] 시간 마감 ACTIVE - CLOSED_BY_TIME', 'DB status는 ACTIVE지만 closeAt이 지나 displayStatus=CLOSED_BY_TIME으로 보여야 한다.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'REGIONAL', '서울', NULL,
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 상승 여부', DATE_SUB(CURDATE(), INTERVAL 1 DAY), 'ACTIVE',
     DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), NULL, NULL, NULL, NULL,
     100.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW()),
    (900005, '[DEV] 결과 확정 완료 - 정산 대기', 'Market.status=CLOSED, Prediction.status=CONFIRMED 정산 대기 표시 테스트.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'REGIONAL', '서울', '강남구',
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 변동률이 0보다 크면 YES', CURDATE(), 'CLOSED',
     DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), NULL, 910009, NULL, 'YES',
     200.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),
    (900006, '[DEV] 정산 완료', 'Market.status=SETTLED, devMemberId 승자 settledAmount 표시 테스트.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'REGIONAL', '서울', '강남구',
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 변동률이 0보다 크면 YES', DATE_SUB(CURDATE(), INTERVAL 3 DAY), 'SETTLED',
     DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 910011, NULL, 'YES',
     200.00, 10.00, 20.00, 180.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),
    (900007, '[DEV] 무효 처리 - 환불 완료', 'VOIDED + REFUNDED + refundAmount 표시 테스트.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'NON_REGIONAL', NULL, NULL,
     '한국부동산원 주간 아파트 가격동향', '데이터 미공개 시 무효 처리', DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'VOIDED',
     DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, NULL, NULL, NULL,
     100.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),
    (900008, '[DEV] 환불 처리 중', 'VOIDED + REFUND_PENDING 표시 테스트.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'NON_REGIONAL', NULL, NULL,
     '한국부동산원 주간 아파트 가격동향', '데이터 미공개 시 무효 처리', DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'VOIDED',
     DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, NULL, NULL, NULL,
     100.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),
    (900009, '[DEV] 환불 확인 중', 'VOIDED + REFUND_UNKNOWN 표시 테스트.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'NON_REGIONAL', NULL, NULL,
     '한국부동산원 주간 아파트 가격동향', '데이터 미공개 시 무효 처리', DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'VOIDED',
     DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, NULL, NULL, NULL,
     100.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),
    (900010, '[DEV] 예측 참여 처리 중', 'Prediction.status=POINT_PENDING 표시 테스트.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'REGIONAL', '서울', NULL,
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 상승 여부', DATE_ADD(CURDATE(), INTERVAL 6 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), NULL, NULL, NULL, NULL,
     0.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
    (900011, '[DEV] 예측 참여 확인 중', 'Prediction.status=POINT_UNKNOWN 표시 테스트.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'REGIONAL', '서울', NULL,
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 상승 여부', DATE_ADD(CURDATE(), INTERVAL 6 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), NULL, NULL, NULL, NULL,
     0.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),
    (900012, '[DEV] 예측 참여 실패', 'Prediction.status=FAILED 표시 테스트. FAILED는 정산 대기가 아니다.', 'PRICE_INDEX', 'YES_NO', 'PERCENT',
     'REGIONAL', '서울', NULL,
     '한국부동산원 주간 아파트 가격동향', '서울 아파트 매매가격지수 상승 여부', DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY), NULL, NULL, NULL, NULL,
     0.00, 5.00, 0.00, 0.00, 100.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
    (900013, '[DEV] 다중 선택지 Market', '2개 초과 option UI 렌더링 테스트. devMemberId=1 예측 없음.', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'PERCENT',
     'REGIONAL', '서울', NULL,
     '한국부동산원 주간 아파트 가격동향', '이번 주 서울 매매가격지수 변동률 구간 예측', DATE_ADD(CURDATE(), INTERVAL 8 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 8 DAY), DATE_ADD(NOW(), INTERVAL 9 DAY), NULL, NULL, NULL, NULL,
     0.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
    (900014, '[DEV] 숫자 범위형 Market', 'NUMERIC_RANGE option rangeMin/rangeMax UI 테스트. devMemberId=1 예측 없음.', 'PRICE_INDEX', 'NUMERIC_RANGE', 'PERCENT',
     'REGIONAL', '서울', NULL,
     '한국부동산원 주간 아파트 가격동향', '이번 주 서울 매매가격지수 변동률 실제 값을 구간으로 판정', DATE_ADD(CURDATE(), INTERVAL 8 DAY), 'ACTIVE',
     DATE_ADD(NOW(), INTERVAL 8 DAY), DATE_ADD(NOW(), INTERVAL 9 DAY), NULL, NULL, NULL, NULL,
     0.00, 5.00, 0.00, 0.00, 300.00, 'POOL_SHARE', 1, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW());

INSERT IGNORE INTO market_option (
    id, market_id, option_code, option_text, display_order,
    range_min, range_max, min_inclusive, max_inclusive,
    virtual_pool_amount, real_pool_amount, total_contract_quantity,
    current_price, prediction_count, is_result, created_at, updated_at
) VALUES
    (910001, 900001, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 500.00, 100.00, 200.00000000, 0.52173913, 1, FALSE, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),
    (910002, 900001, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 500.00, 50.00, 100.00000000, 0.47826087, 1, FALSE, DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),
    (910003, 900002, 'UP', '상승', 1, NULL, NULL, TRUE, FALSE, 10000.00, 500.00, 1000.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW()),
    (910004, 900002, 'DOWN_OR_FLAT', '하락/보합', 2, NULL, NULL, TRUE, FALSE, 10000.00, 500.00, 1000.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW()),
    (910005, 900003, 'UP', '상승', 1, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),
    (910006, 900003, 'DOWN_OR_FLAT', '하락/보합', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),
    (910007, 900004, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.66666667, 1, FALSE, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW()),
    (910008, 900004, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.33333333, 0, FALSE, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW()),
    (910009, 900005, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.50000000, 1, TRUE, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),
    (910010, 900005, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),
    (910011, 900006, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.50000000, 1, TRUE, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),
    (910012, 900006, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.50000000, 1, FALSE, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),
    (910013, 900007, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.66666667, 1, FALSE, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),
    (910014, 900007, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.33333333, 0, FALSE, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),
    (910015, 900008, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.66666667, 1, FALSE, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),
    (910016, 900008, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.33333333, 0, FALSE, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),
    (910017, 900009, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 100.00, 200.00000000, 0.66666667, 1, FALSE, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),
    (910018, 900009, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.33333333, 0, FALSE, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),
    (910019, 900010, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
    (910020, 900010, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
    (910021, 900011, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),
    (910022, 900011, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),
    (910023, 900012, 'YES', 'YES', 1, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
    (910024, 900012, 'NO', 'NO', 2, NULL, NULL, TRUE, FALSE, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
    (910025, 900013, 'NEGATIVE', '0% 미만', 1, NULL, NULL, TRUE, FALSE, 300.00, 0.00, 0.00000000, 0.30000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
    (910026, 900013, 'LOW', '0% 이상 ~ 0.3% 미만', 2, NULL, NULL, TRUE, FALSE, 400.00, 0.00, 0.00000000, 0.40000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
    (910027, 900013, 'HIGH', '0.3% 이상', 3, NULL, NULL, TRUE, FALSE, 300.00, 0.00, 0.00000000, 0.30000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
    (910028, 900014, 'LT_0', '0.0% 미만', 1, NULL, 0.0000, TRUE, FALSE, 300.00, 0.00, 0.00000000, 0.30000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
    (910029, 900014, 'GTE_0_LT_0_3', '0.0% 이상 ~ 0.3% 미만', 2, 0.0000, 0.3000, TRUE, FALSE, 400.00, 0.00, 0.00000000, 0.40000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
    (910030, 900014, 'GTE_0_3', '0.3% 이상', 3, 0.3000, NULL, TRUE, FALSE, 300.00, 0.00, 0.00000000, 0.30000000, 0, FALSE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW());

INSERT IGNORE INTO market_prediction (
    id, market_id, option_id, member_id, point_amount,
    price_snapshot, contract_quantity, expected_payout_per_contract_snapshot, expected_multiplier_snapshot,
    status, point_spend_idempotency_key, attempt_no,
    settled_amount, refund_amount, fail_reason, created_at, updated_at
) VALUES
    (920001, 900001, 910001, 101, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'CONFIRMED', 'DEV_SEED_SPEND:market:900001:member:101:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 13 DAY), DATE_SUB(NOW(), INTERVAL 13 DAY)),
    (920002, 900001, 910002, 102, 50.00, 0.50000000, 100.00000000, NULL, NULL, 'CONFIRMED', 'DEV_SEED_SPEND:market:900001:member:102:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 12 DAY)),
    (920003, 900002, 910003, 103, 500.00, 0.50000000, 1000.00000000, NULL, NULL, 'CONFIRMED', 'DEV_SEED_SPEND:market:900002:member:103:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 12 DAY)),
    (920004, 900002, 910004, 104, 500.00, 0.50000000, 1000.00000000, NULL, NULL, 'CONFIRMED', 'DEV_SEED_SPEND:market:900002:member:104:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 11 DAY)),
    (920005, 900004, 910007, @dev_member_id, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'CONFIRMED', 'DEV_SEED_SPEND:market:900004:member:1:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY)),
    (920006, 900005, 910009, @dev_member_id, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'CONFIRMED', 'DEV_SEED_SPEND:market:900005:member:1:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)),
    (920007, 900005, 910010, 105, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'CONFIRMED', 'DEV_SEED_SPEND:market:900005:member:105:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)),
    (920008, 900006, 910011, @dev_member_id, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'SETTLED', 'DEV_SEED_SPEND:market:900006:member:1:attempt:1', 1, 180.00, NULL, NULL, DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (920009, 900006, 910012, 106, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'SETTLED', 'DEV_SEED_SPEND:market:900006:member:106:attempt:1', 1, 0.00, NULL, NULL, DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (920010, 900007, 910013, @dev_member_id, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'REFUNDED', 'DEV_SEED_SPEND:market:900007:member:1:attempt:1', 1, NULL, 100.00, NULL, DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (920011, 900008, 910015, @dev_member_id, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'REFUND_PENDING', 'DEV_SEED_SPEND:market:900008:member:1:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),
    (920012, 900009, 910017, @dev_member_id, 100.00, 0.50000000, 200.00000000, NULL, NULL, 'REFUND_UNKNOWN', 'DEV_SEED_SPEND:market:900009:member:1:attempt:1', 1, NULL, NULL, 'MEMBER_POINT_TIMEOUT', DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
    (920013, 900010, 910019, @dev_member_id, 100.00, NULL, NULL, NULL, NULL, 'POINT_PENDING', 'DEV_SEED_SPEND:market:900010:member:1:attempt:1', 1, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),
    (920014, 900011, 910021, @dev_member_id, 100.00, NULL, NULL, NULL, NULL, 'POINT_UNKNOWN', 'DEV_SEED_SPEND:market:900011:member:1:attempt:1', 1, NULL, NULL, 'MEMBER_POINT_TIMEOUT', DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
    (920015, 900012, 910023, @dev_member_id, 100.00, NULL, NULL, NULL, NULL, 'FAILED', 'DEV_SEED_SPEND:market:900012:member:1:attempt:1', 1, NULL, NULL, 'POINT_INSUFFICIENT', DATE_SUB(NOW(), INTERVAL 2 DAY), NOW());

INSERT IGNORE INTO market_price_history (
    id, market_id, option_id, prediction_id, event_type,
    price_before, price_after, real_pool_before, real_pool_after,
    contract_quantity_before, contract_quantity_after, created_at, updated_at
) VALUES
    (925001, 900001, 910001, 920002, 'PREDICTION_CONFIRMED', 0.54545455, 0.52173913, 100.00, 100.00, 200.00000000, 200.00000000, DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 12 DAY)),
    (925002, 900001, 910002, 920002, 'PREDICTION_CONFIRMED', 0.45454545, 0.47826087, 0.00, 50.00, 0.00000000, 100.00000000, DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 12 DAY)),
    (925003, 900004, 910007, 920005, 'PREDICTION_CONFIRMED', 0.50000000, 0.66666667, 0.00, 100.00, 0.00000000, 200.00000000, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY)),
    (925004, 900004, 910008, 920005, 'PREDICTION_CONFIRMED', 0.50000000, 0.33333333, 0.00, 0.00, 0.00000000, 0.00000000, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY));

INSERT IGNORE INTO market_settlement (
    id, market_id, result_option_id, total_pool, fee_rate, fee_amount,
    settlement_pool, winning_contract_quantity, payout_per_contract,
    burned_point_amount, status, settled_by, settled_at, created_at, updated_at
) VALUES
    (930001, 900006, 910011, 200.00, 10.00, 20.00, 180.00, 200.00000000, 0.90000000, 0.00, 'COMPLETED', 1, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY));

INSERT IGNORE INTO market_settlement_detail (
    id, settlement_id, prediction_id, member_id,
    original_point_amount, contract_quantity, payout_per_contract,
    settled_amount, profit_amount, status, idempotency_key, fail_reason,
    created_at, updated_at
) VALUES
    (940001, 930001, 920008, @dev_member_id, 100.00, 200.00000000, 0.90000000, 180.00, 80.00, 'SUCCESS', 'MARKET_SETTLEMENT_REWARD:market:900006:prediction:920008:member:1', NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY));

INSERT IGNORE INTO market_void (
    id, market_id, reason_type, reason_detail, refund_status,
    voided_by, voided_at, created_at, updated_at
) VALUES
    (970001, 900007, 'DATA_UNAVAILABLE', 'DEV seed: 데이터 미공개로 무효 처리 완료', 'COMPLETED', 1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (970002, 900008, 'DATA_UNAVAILABLE', 'DEV seed: 환불 처리 중', 'IN_PROGRESS', 1, NOW(), NOW(), NOW()),
    (970003, 900009, 'DATA_UNAVAILABLE', 'DEV seed: 환불 확인 중', 'IN_PROGRESS', 1, NOW(), NOW(), NOW());

INSERT IGNORE INTO market_refund_detail (
    id, market_void_id, prediction_id, member_id,
    refund_amount, status, idempotency_key, fail_reason, created_at, updated_at
) VALUES
    (950001, 970001, 920010, @dev_member_id, 100.00, 'SUCCESS', 'MARKET_REFUND:market:900007:prediction:920010:member:1', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (950002, 970002, 920011, @dev_member_id, 100.00, 'PENDING', 'MARKET_REFUND:market:900008:prediction:920011:member:1', NULL, NOW(), NOW()),
    (950003, 970003, 920012, @dev_member_id, 100.00, 'UNKNOWN', 'MARKET_REFUND:market:900009:prediction:920012:member:1', 'MEMBER_POINT_TIMEOUT', NOW(), NOW());

INSERT IGNORE INTO market_reputation_update (
    id, market_id, prediction_id, member_id, is_correct,
    status, attempt_no, last_error_code, last_error_message, created_at, updated_at
) VALUES
    (960001, 900006, 920008, @dev_member_id, TRUE, 'SUCCESS', 1, NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (960002, 900006, 920009, 106, FALSE, 'SUCCESS', 1, NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY));

COMMIT;

-- Verification examples after running the seed:
--   curl -i "http://localhost:8082/api/v1/markets?page=0&size=20"
--   curl -i "http://localhost:8082/api/v1/markets/900001"
--   curl -i -X POST "http://localhost:8082/api/v1/markets/900001/predictions/quote" -H "Content-Type: application/json" -d "{\"marketOptionId\":910001,\"pointAmount\":\"100.00\"}"
--   curl -i "http://localhost:8082/api/v1/markets/predictions/me?page=0&size=20" -H "X-Member-Id: 1"
--   curl -i "http://localhost:8082/api/v1/markets/predictions/me?page=0&size=20&marketDisplayStatus=CLOSED_BY_TIME" -H "X-Member-Id: 1"
--   curl -i "http://localhost:8082/api/v1/markets/predictions/me?page=0&size=20&predictionStatus=POINT_PENDING,POINT_UNKNOWN" -H "X-Member-Id: 1"
--
-- Note:
-- This seed only creates Market DB data. To test real prediction participation,
-- member-point-service must have enough point balance for devMemberId separately.
