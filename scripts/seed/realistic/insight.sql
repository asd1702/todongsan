-- Development seed for insight (insight).
-- Execute this file only against the insight database; do not add USE statements.

START TRANSACTION;

DELETE FROM market_prediction_result WHERE member_id IN (3, 106, 5) AND market_id IN (902007, 902008);
DELETE FROM market_prediction_result WHERE member_id = 1 AND market_id = 902008;

INSERT INTO market_prediction_result (member_id, market_id, prediction_id, is_correct,
                                       processed_at, created_at, updated_at)
VALUES
-- 902007 성남시: member3 맞춤, member106 틀림
(3,   902007, 922017, TRUE,  DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
(106, 902007, 922018, FALSE, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
-- 902008 노원구: member1 틀림, member5 맞춤
(1,   902008, 922019, FALSE, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
(5,   902008, 922020, TRUE,  DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), NOW());

COMMIT;
