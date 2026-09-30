-- Development seed for insight (insight).
-- Execute this file only against the insight database; do not add USE statements.

UPDATE insight_report SET status = 'DONE' WHERE id IN (700001, 700002);

-- PENDING 리포트 (AI 생성 대기 중 — 배틀 800007 DRAW)
INSERT INTO insight_report (id, type, reference_id, status, summary,
                             analysis_data, raw_prompt, failed_reason,
                             processing_started_at, retry_count,
                             requested_by, report_content, generated_at,
                             created_at, updated_at)
VALUES
(700003, 'BATTLE', 800007, 'PENDING',
 NULL, NULL, NULL, NULL,
 NULL, 0, 1, NULL, NULL,
 NOW(), NOW()),

-- FAILED 리포트 (재시도 3회 초과 — 마켓 900005 데이터 없음)
(700004, 'MARKET', 900005, 'FAILED',
 NULL, NULL, 'market_id=900005 분석 요청', 'Claude API timeout after 3 retries',
 DATE_SUB(NOW(), INTERVAL 11 MINUTE), 3,
 1, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 30 MINUTE), NOW()),

-- PROCESSING 리포트 (AI 호출 진행 중 — 배틀 800006)
(700005, 'BATTLE', 800006, 'PROCESSING',
 NULL, NULL, 'battle_id=800006 분석 요청', NULL,
 DATE_SUB(NOW(), INTERVAL 2 MINUTE), 0,
 1, NULL, NULL,
 DATE_SUB(NOW(), INTERVAL 3 MINUTE), NOW());

-- market_prediction_result (Market 정산 완료 후 reputation 반영 기록)
DELETE FROM market_prediction_result WHERE member_id IN (1, 106);
INSERT INTO market_prediction_result (member_id, market_id, prediction_id, is_correct,
                                       processed_at, created_at, updated_at)
VALUES
(1,   900006, 920008, TRUE,  DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(106, 900006, 920009, FALSE, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NOW());

-- ============================================================
-- 2. memberpoint — ADMIN 멤버 + 누락 point_history 타입
-- ============================================================
