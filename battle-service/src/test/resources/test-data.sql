-- ============================================================
-- Battle Service 테스트 데이터
-- Swagger (http://localhost:8082/swagger-ui/index.html) 테스트용
--
-- 실행 방법:
--   docker exec -i battle-mysql mysql -ubattle_user -pbattle-local-password battle < test-data.sql
--
-- 테스트 시나리오:
--   battle_id=1 : ACTIVE  → 투표 가능 (POST /api/v1/battles/1/votes)
--   battle_id=2 : CLOSED  → 결과 조회, 관리자 교차분석 가능
--   battle_id=3 : PENDING → 일반 사용자 BATTLE_NOT_FOUND, 관리자는 조회 가능
--   battle_id=4 : CANCELLED → 일반 사용자 BATTLE_NOT_FOUND
--   member_id   : 1(생성자), 2(투표자), 3(댓글작성자) — X-Member-Id 헤더에 사용
-- ============================================================
-- ============================================================
-- 기존 테스트 데이터 초기화 (재실행 안전)
-- ============================================================
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE point_reward_retry_queue;
TRUNCATE TABLE comment;
TRUNCATE TABLE battle_vote;
TRUNCATE TABLE battle;
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- Battle
-- ============================================================

INSERT INTO battle (id, title, option_a, option_b, status,
                    option_a_count, option_b_count, vote_count,
                    winning_option, reward_amount, settled_at,
                    created_by, start_at, end_at, deleted_at, created_at, updated_at)
VALUES
-- 1: ACTIVE — 투표 가능 (start_at 과거, end_at 미래)
(1, '성수 vs 연남, 데이트하기 어디가 더 좋을까?', '성수', '연남', 'ACTIVE',
 3, 2, 5, NULL, 0.00, NULL,
 1, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 6 DAY, NULL, NOW() - INTERVAL 2 DAY, NOW()),

-- 2: CLOSED + 정산 완료 — 결과 조회, 교차분석(관리자) 가능
(2, '강남 vs 홍대, 주말 나들이 어디가 좋을까?', '강남', '홍대', 'CLOSED',
 8, 5, 13, 'A', 2.00, NOW() - INTERVAL 1 HOUR,
 1, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 1 DAY, NULL, NOW() - INTERVAL 9 DAY, NOW()),

-- 3: PENDING — 관리자 approve/reject 가능
(3, '치킨 vs 피자, 야식으로 뭐가 더 좋을까?', '치킨', '피자', 'PENDING',
 0, 0, 0, NULL, 0.00, NULL,
 1, NOW() + INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY, NULL, NOW(), NOW()),

-- 4: CANCELLED — 접근 불가 (BATTLE_NOT_FOUND)
(4, '테스트 취소된 배틀', '옵션A', '옵션B', 'CANCELLED',
 0, 0, 0, NULL, 0.00, NULL,
 1, NOW() - INTERVAL 3 DAY, NOW() + INTERVAL 4 DAY, NULL, NOW() - INTERVAL 3 DAY, NOW()),

-- 5: ACTIVE — start_at 미도달 (투표 시 BATTLE_CLOSED)
(5, '한강 vs 북한산, 주말 나들이 어디?', '한강', '북한산', 'ACTIVE',
 0, 0, 0, NULL, 0.00, NULL,
 1, NOW() + INTERVAL 2 DAY, NOW() + INTERVAL 9 DAY, NULL, NOW(), NOW());

-- ============================================================
-- 투표 (battle_id=1에 member_id=2가 A 선택)
-- ============================================================

INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at)
VALUES
(1, 2, 'A', FALSE, NOW(), NOW()),
(2, 2, 'A', TRUE,  NOW() - INTERVAL 2 DAY, NOW()),
(2, 3, 'A', TRUE,  NOW() - INTERVAL 2 DAY, NOW());

-- ============================================================
-- 댓글 (battle_id=1)
-- ============================================================

INSERT INTO comment (battle_id, member_id, content, deleted_at, created_at, updated_at)
VALUES
(1, 2, '성수는 감성 카페가 진짜 예쁘더라고요!', NULL, NOW() - INTERVAL 1 HOUR, NOW()),
(1, 3, '연남도 독특한 분위기가 있어서 좋아요.', NULL, NOW() - INTERVAL 30 MINUTE, NOW()),
(1, 1, '이 댓글은 삭제된 댓글입니다.', NOW(), NOW() - INTERVAL 20 MINUTE, NOW()),
(2, 2, '강남이 접근성이 훨씬 좋죠.', NULL, NOW() - INTERVAL 3 DAY, NOW());
