-- Development seed for member-point (memberpoint).
-- Execute this file only against the memberpoint database; do not add USE statements.

START TRANSACTION;

-- ── 재실행 안전 정리 ──────────────────────────────────────────
DELETE FROM point_history WHERE idempotency_key LIKE 'DEV_SEED:%';
DELETE FROM point_history WHERE member_id BETWEEN 2 AND 10;
DELETE FROM member WHERE id BETWEEN 2 AND 10;
DELETE FROM member WHERE id BETWEEN 101 AND 106;

-- ── member 1 생성 (빈 Flyway DB에서도 frontend scenario를 재현하기 위한 기본 계정) ──
INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id, created_at, updated_at)
VALUES (1, '개발기본회원', 5000.00, 'USER', 'KAKAO', 'DEV_SEED_KAKAO_001', NOW(), NOW())
ON DUPLICATE KEY UPDATE id = id;

-- ── member 1 갱신 (실제 로그인 계정) ────────────────────────
UPDATE member
SET point_balance       = 5000.00,
    residence_sido      = '서울',
    residence_sigu      = '마포구',
    residence_changed_at = DATE_SUB(NOW(), INTERVAL 10 DAY),
    age_group           = 'AGE_20S',
    gender              = 'MALE',
    updated_at          = NOW()
WHERE id = 1;

-- ── 테스트 멤버 2~10 ─────────────────────────────────────────
INSERT INTO member (id, nickname, point_balance, role,
                    residence_sido, residence_sigu, residence_changed_at,
                    age_group, gender,
                    oauth_provider, oauth_id,
                    created_at, updated_at)
VALUES
(2,  '테스트유저_강남',   2000.00, 'USER', '서울',  '강남구',   DATE_SUB(NOW(), INTERVAL 8 DAY),  'AGE_30S',      'FEMALE', 'KAKAO', 'TEST_KAKAO_002', DATE_SUB(NOW(), INTERVAL 20 DAY), NOW()),
(3,  '테스트유저_경기',   1500.00, 'USER', '경기',  '수원시',   DATE_SUB(NOW(), INTERVAL 7 DAY),  'AGE_20S',      'MALE',   'KAKAO', 'TEST_KAKAO_003', DATE_SUB(NOW(), INTERVAL 19 DAY), NOW()),
(4,  '테스트유저_부산',   3000.00, 'USER', '부산',  '해운대구', DATE_SUB(NOW(), INTERVAL 6 DAY),  'AGE_40S',      'FEMALE', 'KAKAO', 'TEST_KAKAO_004', DATE_SUB(NOW(), INTERVAL 18 DAY), NOW()),
(5,  '테스트유저_대구',   800.00,  'USER', '대구',  '중구',     DATE_SUB(NOW(), INTERVAL 5 DAY),  'AGE_50S_ABOVE','MALE',   'KAKAO', 'TEST_KAKAO_005', DATE_SUB(NOW(), INTERVAL 17 DAY), NOW()),
(6,  '테스트유저_인천',   500.00,  'USER', '인천',  '연수구',   DATE_SUB(NOW(), INTERVAL 4 DAY),  'AGE_20S',      'FEMALE', 'KAKAO', 'TEST_KAKAO_006', DATE_SUB(NOW(), INTERVAL 16 DAY), NOW()),
(7,  '테스트유저_광주',   1200.00, 'USER', '광주',  '서구',     DATE_SUB(NOW(), INTERVAL 3 DAY),  'AGE_30S',      'MALE',   'KAKAO', 'TEST_KAKAO_007', DATE_SUB(NOW(), INTERVAL 15 DAY), NOW()),
(8,  '테스트유저_대전',   900.00,  'USER', '대전',  '유성구',   DATE_SUB(NOW(), INTERVAL 3 DAY),  'AGE_20S',      'FEMALE', 'KAKAO', 'TEST_KAKAO_008', DATE_SUB(NOW(), INTERVAL 14 DAY), NOW()),
(9,  '테스트유저_울산',   600.00,  'USER', '울산',  '남구',     DATE_SUB(NOW(), INTERVAL 2 DAY),  'AGE_30S',      'MALE',   'KAKAO', 'TEST_KAKAO_009', DATE_SUB(NOW(), INTERVAL 13 DAY), NOW()),
(10, '테스트유저_세종',   400.00,  'USER', '세종',  '세종시',   DATE_SUB(NOW(), INTERVAL 1 DAY),  'AGE_20S',      'FEMALE', 'KAKAO', 'TEST_KAKAO_010', DATE_SUB(NOW(), INTERVAL 12 DAY), NOW());

-- ── Market 시드 호환 멤버 101~106 ────────────────────────────
INSERT INTO member (id, nickname, point_balance, role,
                    oauth_provider, oauth_id, created_at, updated_at)
VALUES
(101, '마켓테스터_1', 1000.00, 'USER', 'KAKAO', 'TEST_KAKAO_101', DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
(102, '마켓테스터_2', 1000.00, 'USER', 'KAKAO', 'TEST_KAKAO_102', DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
(103, '마켓테스터_3', 1000.00, 'USER', 'KAKAO', 'TEST_KAKAO_103', DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
(104, '마켓테스터_4', 1000.00, 'USER', 'KAKAO', 'TEST_KAKAO_104', DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
(105, '마켓테스터_5', 1000.00, 'USER', 'KAKAO', 'TEST_KAKAO_105', DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
(106, '마켓테스터_6', 1000.00, 'USER', 'KAKAO', 'TEST_KAKAO_106', DATE_SUB(NOW(), INTERVAL 30 DAY), NOW());

-- ── 포인트 이력 (member 1) ───────────────────────────────────
INSERT INTO point_history (member_id, type, amount, balance_snapshot, reason,
                           reference_type, reference_id, idempotency_key, status,
                           created_at, updated_at)
VALUES
(1, 'EARN_SIGNUP',         1000.00, 1000.00, '신규 가입 보상',                    NULL, NULL, 'DEV_SEED:signup:member:1',           'SUCCEEDED', DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY)),
(1, 'EARN_VOTE',             10.00, 1010.00, 'Battle 투표 참여 보상',             'BATTLE', 800001, 'DEV_SEED:vote:battle:800001:member:1', 'SUCCEEDED', DATE_SUB(NOW(), INTERVAL 5 DAY),  DATE_SUB(NOW(), INTERVAL 5 DAY)),
(1, 'EARN_COMMENT',           2.00, 1012.00, 'Battle 댓글 작성 보상',             'BATTLE', 800001, 'DEV_SEED:comment:1:member:1',          'SUCCEEDED', DATE_SUB(NOW(), INTERVAL 4 DAY),  DATE_SUB(NOW(), INTERVAL 4 DAY)),
(1, 'EARN_VOTE_WIN',          10.00, 1022.00, 'Battle 승리 진영 보상',            'BATTLE', 800005, 'DEV_SEED:settle:battle:800005:member:1','SUCCEEDED', DATE_SUB(NOW(), INTERVAL 3 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY)),
(1, 'SPEND_MARKET',         100.00, 922.00,  'Market 예측 참여 차감',             'MARKET', 900001, 'DEV_SEED:spend:market:900001:member:1', 'SUCCEEDED', DATE_SUB(NOW(), INTERVAL 13 DAY), DATE_SUB(NOW(), INTERVAL 13 DAY)),
(1, 'SETTLE_MARKET',        180.00, 1102.00, 'Market 정산 보상 (900006 승리)',    'MARKET', 900006, 'DEV_SEED:settle:market:900006:member:1','SUCCEEDED', DATE_SUB(NOW(), INTERVAL 2 DAY),  DATE_SUB(NOW(), INTERVAL 2 DAY)),
(1, 'EARN_BATTLE_APPROVED',  20.00, 1122.00, 'Battle 주제 등록 승인 보상',        'BATTLE', 800001, 'DEV_SEED:approved:battle:800001:member:1','SUCCEEDED',DATE_SUB(NOW(), INTERVAL 6 DAY),  DATE_SUB(NOW(), INTERVAL 6 DAY)),
(1, 'SPEND_INSIGHT',         50.00, 1072.00, 'Insight 리포트 열람 차감',         NULL, NULL, 'DEV_SEED:insight:1:member:1',          'SUCCEEDED', DATE_SUB(NOW(), INTERVAL 1 DAY),  DATE_SUB(NOW(), INTERVAL 1 DAY));

COMMIT;

-- ============================================================
-- battle DB
-- ============================================================
