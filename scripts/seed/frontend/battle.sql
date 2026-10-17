-- Development seed for battle (battle).
-- Execute this file only against the battle database; do not add USE statements.

START TRANSACTION;

-- ── 재실행 안전 정리 ──────────────────────────────────────────
DELETE FROM point_reward_retry_queue WHERE reference_id BETWEEN 800001 AND 800010;
DELETE FROM comment WHERE battle_id BETWEEN 800001 AND 800010;
DELETE FROM battle_vote WHERE battle_id BETWEEN 800001 AND 800010;
DELETE FROM battle WHERE id BETWEEN 800001 AND 800010;

-- ── Battle ───────────────────────────────────────────────────
INSERT INTO battle (id, title, option_a, option_b, status,
                    option_a_count, option_b_count, vote_count,
                    winning_option, reward_amount, settled_at,
                    sido, sigu, created_by, start_at, end_at,
                    deleted_at, created_at, updated_at)
VALUES
-- 800001: ACTIVE - 홍대 vs 연남 (서울 마포구) 투표 활발, member=1 이미 투표
(800001, '홍대 vs 연남, 주말 나들이 어디가 더 좋아요?', '홍대', '연남', 'ACTIVE',
 12, 9, 21, NULL, 0.00, NULL,
 '서울', '마포구', 1,
 DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),

-- 800002: ACTIVE - 강남역 vs 선릉역 (서울 강남구) 투표 적음, member=1 미투표
(800002, '강남역 vs 선릉역, 점심 맛집은 어디가 더 많을까?', '강남역', '선릉역', 'ACTIVE',
 3, 5, 8, NULL, 0.00, NULL,
 '서울', '강남구', 2,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

-- 800003: ACTIVE - 수원갈비 지역 배틀 (경기 수원시) member=3 생성
(800003, '수원 왕갈비 vs 수원 닭갈비, 수원 명물은?', '왕갈비', '닭갈비', 'ACTIVE',
 7, 4, 11, NULL, 0.00, NULL,
 '경기', '수원시', 3,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

-- 800004: ACTIVE - 광안리 vs 해운대 (부산 해운대구) 투표 0
(800004, '광안리 해수욕장 vs 해운대 해수욕장, 여름 피서지는?', '광안리', '해운대', 'ACTIVE',
 0, 0, 0, NULL, 0.00, NULL,
 '부산', '해운대구', 4,
 DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 8 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),

-- 800005: CLOSED + 정산완료 - A 승리 (서울 종로구)
(800005, '경복궁 vs 창덕궁, 외국 친구 데려가기 좋은 곳은?', '경복궁', '창덕궁', 'CLOSED',
 18, 10, 28, 'A', 10.00, DATE_SUB(NOW(), INTERVAL 1 DAY),
 '서울', '종로구', 1,
 DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),

-- 800006: CLOSED + 정산완료 - B 승리 (서울 강남구)
(800006, '강남 치킨 vs 강남 피자, 야식 최강자는?', '치킨', '피자', 'CLOSED',
 6, 14, 20, 'B', 10.00, DATE_SUB(NOW(), INTERVAL 2 DAY),
 '서울', '강남구', 2,
 DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW()),

-- 800007: CLOSED + 정산완료 - DRAW (대구 중구)
(800007, '동성로 vs 반월당, 대구 중심가는 어디?', '동성로', '반월당', 'CLOSED',
 8, 8, 16, 'DRAW', 0.00, DATE_SUB(NOW(), INTERVAL 3 DAY),
 '대구', '중구', 5,
 DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 13 DAY), NOW()),

-- 800008: ACTIVE - 성수동 카페 배틀 (서울 성동구) start_at 미래 → 투표 불가
(800008, '성수동 카페 vs 뚝섬 카페, 인스타 감성은?', '성수동 카페', '뚝섬 카페', 'ACTIVE',
 0, 0, 0, NULL, 0.00, NULL,
 '서울', '성동구', 1,
 DATE_ADD(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 9 DAY),
 NULL, NOW(), NOW()),

-- 800009: PENDING - 관리자 승인 대기
(800009, '[검수 대기] 한강 공원 vs 북한산, 주말 산책 코스는?', '한강 공원', '북한산', 'PENDING',
 0, 0, 0, NULL, 0.00, NULL,
 '서울', '은평구', 1,
 DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 8 DAY),
 NULL, NOW(), NOW()),

-- 800010: CANCELLED
(800010, '[취소됨] 인천 차이나타운 vs 신포국제시장', '차이나타운', '신포국제시장', 'CANCELLED',
 0, 0, 0, NULL, 0.00, NULL,
 '인천', '미추홀구', 6,
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW());

-- ── Battle Vote ──────────────────────────────────────────────
-- 800001 ACTIVE - member=1 A 투표, 여러 멤버 투표
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(800001, 1, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(800001, 2, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(800001, 3, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800001, 4, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800001, 5, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800001, 6, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800001, 7, 'A', TRUE,  NOW(), NOW()),
(800001, 8, 'A', TRUE,  NOW(), NOW()),
(800001, 9, 'A', TRUE,  NOW(), NOW()),
(800001, 10,'A', TRUE,  NOW(), NOW()),
(800001, 101,'A', TRUE, NOW(), NOW()),
(800001, 102,'B', TRUE, NOW(), NOW()),
(800001, 103,'B', TRUE, NOW(), NOW()),
(800001, 104,'B', TRUE, NOW(), NOW()),
(800001, 105,'B', TRUE, NOW(), NOW()),
(800001, 106,'B', TRUE, NOW(), NOW()),
-- 800002 ACTIVE - member=1 미투표
(800002, 2, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800002, 3, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800002, 4, 'B', TRUE,  NOW(), NOW()),
(800002, 5, 'A', TRUE,  NOW(), NOW()),
(800002, 6, 'B', TRUE,  NOW(), NOW()),
(800002, 7, 'B', TRUE,  NOW(), NOW()),
(800002, 8, 'B', TRUE,  NOW(), NOW()),
(800002, 9, 'A', TRUE,  NOW(), NOW()),
-- 800003 ACTIVE
(800003, 1, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800003, 3, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800003, 4, 'A', TRUE,  NOW(), NOW()),
(800003, 6, 'A', TRUE,  NOW(), NOW()),
(800003, 7, 'A', TRUE,  NOW(), NOW()),
(800003, 8, 'A', TRUE,  NOW(), NOW()),
(800003, 9, 'A', TRUE,  NOW(), NOW()),
(800003, 2, 'B', TRUE,  NOW(), NOW()),
(800003, 5, 'B', TRUE,  NOW(), NOW()),
(800003, 10,'B', TRUE,  NOW(), NOW()),
(800003, 101,'B', TRUE, NOW(), NOW()),
-- 800005 CLOSED A 승리 - member=1 A 투표
(800005, 1, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800005, 2, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800005, 3, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800005, 4, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800005, 5, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800005, 6, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
-- 800006 CLOSED B 승리 - member=1 A 투표 (패배)
(800006, 1, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(800006, 2, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(800006, 3, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(800006, 4, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(800006, 5, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
-- 800007 CLOSED DRAW
(800007, 1, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
(800007, 2, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
(800007, 3, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
(800007, 4, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY));

-- ── Comment ──────────────────────────────────────────────────
-- battle 800001
INSERT INTO comment (battle_id, member_id, content, deleted_at, created_at, updated_at) VALUES
(800001, 1, '홍대는 클럽 문화랑 감성 카페가 정말 독특하죠! 연남은 요즘 트렌디한 식당이 많아요.', NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(800001, 2, '연남동은 조용하고 골목 산책하기 딱 좋아요. 홍대보다 덜 북적여서 저는 연남파!', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800001, 3, '홍대 근처 독립서점이랑 빈티지샵이 너무 좋아서 홍대에 한 표요.', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800001, 4, '저는 해산물 좋아해서 연남동 해산물 거리 자주 가는데, 정말 맛있어요!', NULL, DATE_SUB(NOW(), INTERVAL 12 HOUR), NOW()),
(800001, 5, '두 곳 다 좋지만 지하철 접근성은 홍대가 훨씬 낫죠.', NULL, DATE_SUB(NOW(), INTERVAL 6 HOUR), NOW()),
(800001, 1, '이 댓글은 삭제된 댓글 테스트용입니다.', NOW(), DATE_SUB(NOW(), INTERVAL 5 HOUR), NOW()),
-- battle 800002
(800002, 2, '선릉역 주변에 숨은 맛집들이 의외로 많아요. 회사원들이 많아서 점심 퀄리티가 높음!', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800002, 3, '강남역은 프랜차이즈가 너무 많아서 개인 맛집 찾기가 오히려 선릉이 낫더라고요.', NULL, DATE_SUB(NOW(), INTERVAL 12 HOUR), NOW()),
-- battle 800003
(800003, 3, '수원 왕갈비는 진짜 두꺼운 갈비살이 일품! 수원 여행 왔다면 무조건 왕갈비죠.', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(800003, 1, '닭갈비도 맵고 짭조름한 게 맥주랑 진짜 잘 어울려요. 둘 다 맛있어서 고민되네요.', NULL, DATE_SUB(NOW(), INTERVAL 6 HOUR), NOW()),
-- battle 800005 CLOSED
(800005, 1, '경복궁은 규모가 압도적이고 야경도 진짜 아름다워요. 외국 친구들이 다들 감탄했어요!', NULL, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),
(800005, 3, '창덕궁 후원은 사전 예약이 필요하지만 그 고즈넉한 분위기는 정말 특별해요.', NULL, DATE_SUB(NOW(), INTERVAL 6 DAY), NOW());

COMMIT;

-- ============================================================
-- insight DB
-- ============================================================
