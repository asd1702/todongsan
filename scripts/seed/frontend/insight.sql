-- Development seed for insight (insight).
-- Execute this file only against the insight database; do not add USE statements.

START TRANSACTION;

-- ── 재실행 안전 정리 ──────────────────────────────────────────
DELETE FROM insight_report WHERE id BETWEEN 700001 AND 700010;
DELETE FROM visit_certification WHERE member_id BETWEEN 1 AND 10;
DELETE FROM reputation WHERE member_id BETWEEN 1 AND 10;

-- ── Reputation ───────────────────────────────────────────────
INSERT INTO reputation (member_id, activity_score, prediction_count, prediction_correct,
                        prediction_accuracy, residence_sido, residence_sigu,
                        residence_declared_at, residence_changed_at,
                        activity_count, activity_confirmed_at, created_at, updated_at)
VALUES
(1, 85, 6, 4, 66.67, '서울', '마포구',  DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY), 12, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
(2, 62, 4, 2, 50.00, '서울', '강남구',  DATE_SUB(NOW(), INTERVAL 8 DAY),  DATE_SUB(NOW(), INTERVAL 8 DAY),  8,  DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY), NOW()),
(3, 40, 3, 1, 33.33, '경기', '수원시',  DATE_SUB(NOW(), INTERVAL 7 DAY),  DATE_SUB(NOW(), INTERVAL 7 DAY),  5,  DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 19 DAY), NOW()),
(4, 75, 5, 4, 80.00, '부산', '해운대구',DATE_SUB(NOW(), INTERVAL 6 DAY),  DATE_SUB(NOW(), INTERVAL 6 DAY),  10, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 18 DAY), NOW()),
(5, 30, 2, 0, 0.00,  '대구', '중구',    DATE_SUB(NOW(), INTERVAL 5 DAY),  DATE_SUB(NOW(), INTERVAL 5 DAY),  3,  DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 17 DAY), NOW()),
(6, 15, 1, 0, 0.00,  '인천', '연수구',  DATE_SUB(NOW(), INTERVAL 4 DAY),  DATE_SUB(NOW(), INTERVAL 4 DAY),  2,  DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 16 DAY), NOW()),
(7, 50, 3, 2, 66.67, '광주', '서구',    DATE_SUB(NOW(), INTERVAL 3 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY),  7,  DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY), NOW()),
(8, 20, 1, 1, 100.00,'대전', '유성구',  DATE_SUB(NOW(), INTERVAL 3 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY),  3,  DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),
(9, 10, 0, 0, 0.00,  '울산', '남구',    DATE_SUB(NOW(), INTERVAL 2 DAY),  DATE_SUB(NOW(), INTERVAL 2 DAY),  1,  DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 13 DAY), NOW()),
(10, 5, 0, 0, 0.00,  '세종', '세종시',  DATE_SUB(NOW(), INTERVAL 1 DAY),  DATE_SUB(NOW(), INTERVAL 1 DAY),  0,  NULL,                            DATE_SUB(NOW(), INTERVAL 12 DAY), NOW());

-- ── Visit Certification ──────────────────────────────────────
INSERT INTO visit_certification (member_id, sido, sigu, method,
                                  latitude, longitude, comment_content, battle_id,
                                  last_certified_at, certified_at, created_at, updated_at)
VALUES
-- member 1: GPS 인증 (마포구), 댓글 인증 (종로구)
(1, '서울', '마포구',   'GPS',     37.55695,  126.92426, NULL,                              NULL,   DATE_SUB(NOW(), INTERVAL 5 DAY),  DATE_SUB(NOW(), INTERVAL 5 DAY),  DATE_SUB(NOW(), INTERVAL 5 DAY),  NOW()),
(1, '서울', '종로구',   'COMMENT', NULL,       NULL,      '경복궁 야경 진짜 아름다워요!',    800005, DATE_SUB(NOW(), INTERVAL 7 DAY),  DATE_SUB(NOW(), INTERVAL 7 DAY),  DATE_SUB(NOW(), INTERVAL 7 DAY),  NOW()),
-- member 2: GPS 인증 (강남구)
(2, '서울', '강남구',   'GPS',     37.49794,  127.02759, NULL,                              NULL,   DATE_SUB(NOW(), INTERVAL 4 DAY),  DATE_SUB(NOW(), INTERVAL 4 DAY),  DATE_SUB(NOW(), INTERVAL 4 DAY),  NOW()),
-- member 3: GPS 인증 (수원시)
(3, '경기', '수원시',   'GPS',     37.26337,  127.02856, NULL,                              NULL,   DATE_SUB(NOW(), INTERVAL 3 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY),  NOW()),
-- member 4: 댓글 인증 (해운대구)
(4, '부산', '해운대구', 'GPS',     35.15875,  129.16019, NULL,                              NULL,   DATE_SUB(NOW(), INTERVAL 2 DAY),  DATE_SUB(NOW(), INTERVAL 2 DAY),  DATE_SUB(NOW(), INTERVAL 2 DAY),  NOW()),
-- member 5: GPS 인증 (대구 중구)
(5, '대구', '중구',     'GPS',     35.86884,  128.60648, NULL,                              NULL,   DATE_SUB(NOW(), INTERVAL 1 DAY),  DATE_SUB(NOW(), INTERVAL 1 DAY),  DATE_SUB(NOW(), INTERVAL 1 DAY),  NOW());

-- ── Insight Report ───────────────────────────────────────────
INSERT INTO insight_report (id, type, reference_id, status, summary, analysis_data,
                             raw_prompt, failed_reason,
                             processing_started_at, retry_count,
                             requested_by, report_content, generated_at,
                             created_at, updated_at)
VALUES
-- Battle 800005 리포트 (A 승리 — 경복궁 vs 창덕궁)
(700001, 'BATTLE', 800005, 'COMPLETED',
 '경복궁 승리. 접근성과 랜드마크 인지도가 주요 요인.',
 JSON_OBJECT(
     'totalVotes', 28,
     'optionAVotes', 18, 'optionBVotes', 10,
     'winningOption', 'A',
     'ageDistribution', JSON_OBJECT('AGE_20S', 12, 'AGE_30S', 10, 'AGE_40S', 4, 'AGE_50S_ABOVE', 2),
     'genderDistribution', JSON_OBJECT('MALE', 15, 'FEMALE', 13),
     'regionDistribution', JSON_OBJECT('서울', 16, '경기', 5, '부산', 4, '기타', 3)
 ),
 'battle_id=800005 분석 요청',
 NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), 0,
 1,
 '경복궁 승리. 접근성과 랜드마크 인지도가 주요 요인입니다.',
 DATE_SUB(NOW(), INTERVAL 1 DAY),
 DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

-- Market 900006 리포트 (SETTLED — 서울 아파트 매매가격지수)
(700002, 'MARKET', 900006, 'COMPLETED',
 '서울 아파트 지수 상승. YES 예측자 정산 완료.',
 JSON_OBJECT(
     'resultOption', 'YES',
     'totalPool', 200.00,
     'settlementPool', 180.00,
     'feeRate', 10.0,
     'participantCount', 2,
     'yesCount', 1, 'noCount', 1,
     'winnerSettledAmount', 180.00
 ),
 'market_id=900006 분석 요청',
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), 0,
 1,
 '서울 아파트 지수 상승. YES 예측자 정산이 완료됐습니다.',
 DATE_SUB(NOW(), INTERVAL 2 DAY),
 DATE_SUB(NOW(), INTERVAL 3 DAY), NOW());

COMMIT;

-- ============================================================
-- 확인 쿼리 (실행 후 검증)
-- ============================================================
