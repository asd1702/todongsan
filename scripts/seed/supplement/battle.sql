-- Development seed for battle (battle).
-- Execute this file only against the battle database; do not add USE statements.

DELETE FROM battle_vote WHERE battle_id = 800011;
DELETE FROM battle WHERE id = 800011;

-- 800011: CLOSED + winning_option 확정, settled_at IS NULL (정산 대기 중)
INSERT INTO battle (id, title, option_a, option_b, status,
                    option_a_count, option_b_count, vote_count,
                    winning_option, reward_amount, settled_at,
                    sido, sigu, created_by, start_at, end_at,
                    deleted_at, created_at, updated_at)
VALUES
(800011, '한강 뚝섬 vs 반포, 피크닉 성지는?', '뚝섬', '반포', 'CLOSED',
 5, 3, 8, 'A', 0.00, NULL,
 '서울', '영등포구', 1,
 DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR),
 NULL, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW());

INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at)
VALUES
(800011, 1, 'A', FALSE, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),
(800011, 2, 'A', FALSE, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW()),
(800011, 3, 'A', FALSE, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW()),
(800011, 4, 'A', FALSE, DATE_SUB(NOW(), INTERVAL 4 DAY), NOW()),
(800011, 5, 'A', FALSE, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),
(800011, 6, 'B', FALSE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(800011, 7, 'B', FALSE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800011, 8, 'B', FALSE, DATE_SUB(NOW(), INTERVAL 12 HOUR), NOW());

-- 800012: 소프트 삭제된 배틀 (deleted_at IS NOT NULL)
DELETE FROM battle WHERE id = 800012;
INSERT INTO battle (id, title, option_a, option_b, status,
                    option_a_count, option_b_count, vote_count,
                    winning_option, reward_amount, settled_at,
                    sido, sigu, created_by, start_at, end_at,
                    deleted_at, created_at, updated_at)
VALUES
(800012, '[삭제됨] 관리자가 삭제한 배틀', '옵션A', '옵션B', 'CANCELLED',
 0, 0, 0, NULL, 0.00, NULL,
 '서울', '강남구', 999,
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY),
 NOW(), DATE_SUB(NOW(), INTERVAL 5 DAY), NOW());
