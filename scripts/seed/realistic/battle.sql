-- Development seed for battle (battle).
-- Execute this file only against the battle database; do not add USE statements.

START TRANSACTION;

DELETE FROM comment     WHERE battle_id BETWEEN 801001 AND 801015;
DELETE FROM battle_vote WHERE battle_id BETWEEN 801001 AND 801015;
DELETE FROM battle      WHERE id        BETWEEN 801001 AND 801015;

INSERT INTO battle (id, title, option_a, option_b, status,
                    option_a_count, option_b_count, vote_count,
                    winning_option, reward_amount, settled_at,
                    sido, sigu, created_by, start_at, end_at,
                    deleted_at, created_at, updated_at)
VALUES
(801001, '한남동 카페 골목 vs 이태원 세계음식 거리, 주말 데이트는?',
 '한남동 카페 골목', '이태원 세계음식 거리', 'ACTIVE',
 4, 3, 7, NULL, 0.00, NULL,
 '서울', '용산구', 1,
 DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),

(801002, '성수동 핫플 vs 왕십리 야시장, 저녁 나들이 핫스팟은?',
 '성수동 핫플', '왕십리 야시장', 'ACTIVE',
 4, 2, 6, NULL, 0.00, NULL,
 '서울', '성동구', 2,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

(801003, '일산 호수공원 산책 vs 행주산성 트레킹, 고양시 주말 코스는?',
 '일산 호수공원', '행주산성', 'ACTIVE',
 3, 1, 4, NULL, 0.00, NULL,
 '경기', '고양시', 3,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

(801004, '광안대교 야경 vs 오륙도 스카이워크, 부산 필수 관광지는?',
 '광안대교 야경', '오륙도 스카이워크', 'ACTIVE',
 2, 2, 4, NULL, 0.00, NULL,
 '부산', '남구', 4,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

(801005, '반포 한강공원 vs 잠원 한강공원, 주말 치맥 성지는?',
 '반포 한강공원', '잠원 한강공원', 'ACTIVE',
 4, 2, 6, NULL, 0.00, NULL,
 '서울', '서초구', 2,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

(801006, '판교 테크노밸리 카페 vs 분당 정자역 로데오, 카공족 성지는?',
 '판교 테크노밸리', '분당 정자역 로데오', 'ACTIVE',
 3, 2, 5, NULL, 0.00, NULL,
 '경기', '성남시', 3,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

(801007, '인천 차이나타운 짜장면 vs 신포국제시장 닭강정, 인천 대표 먹거리는?',
 '차이나타운 짜장면', '신포국제시장 닭강정', 'ACTIVE',
 2, 2, 4, NULL, 0.00, NULL,
 '인천', '중구', 6,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

(801008, '망원시장 vs 공덕시장, 마포 주민 단골 전통시장은?',
 '망원시장', '공덕시장', 'ACTIVE',
 3, 2, 5, NULL, 0.00, NULL,
 '서울', '마포구', 1,
 DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), NOW()),

(801009, '계룡산 국립공원 등산 vs 갑천 시민공원 자전거, 대전 힐링 코스는?',
 '계룡산 국립공원', '갑천 시민공원', 'ACTIVE',
 2, 2, 4, NULL, 0.00, NULL,
 '대전', '유성구', 8,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

(801010, '북한산 둘레길 트레킹 vs 수유계곡 물놀이, 강북 자연 힐링은?',
 '북한산 둘레길', '수유계곡', 'ACTIVE',
 3, 2, 5, NULL, 0.00, NULL,
 '서울', '강북구', 1,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),

-- CLOSED 정산완료 A승리: 인사동 vs 익선동
(801011, '인사동 전통문화 거리 vs 익선동 한옥 카페촌, 종로 핫플은?',
 '인사동', '익선동', 'CLOSED',
 4, 3, 7, 'A', 10.00, DATE_SUB(NOW(), INTERVAL 2 DAY),
 '서울', '종로구', 1,
 DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 11 DAY), NOW()),

-- CLOSED 정산완료 B승리: 수원화성 vs 광교호수공원
(801012, '수원화성 역사투어 vs 광교호수공원 데이트, 수원 나들이 코스는?',
 '수원화성 역사투어', '광교호수공원 데이트', 'CLOSED',
 2, 3, 5, 'B', 10.00, DATE_SUB(NOW(), INTERVAL 1 DAY),
 '경기', '수원시', 3,
 DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 10 DAY), NOW()),

-- CLOSED DRAW: 서면 vs 부전시장
(801013, '서면 쇼핑몰 vs 부전시장 먹자골목, 부산진구 대표 상권은?',
 '서면 쇼핑몰', '부전시장 먹자골목', 'CLOSED',
 2, 2, 4, 'DRAW', 0.00, DATE_SUB(NOW(), INTERVAL 3 DAY),
 '부산', '부산진구', 4,
 DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 12 DAY), NOW()),

-- PENDING 검수 대기: 은평구
(801014, '은평 한옥마을 문화체험 vs 불광천 산책로, 은평구 힐링 명소는?',
 '은평 한옥마을', '불광천 산책로', 'PENDING',
 0, 0, 0, NULL, 0.00, NULL,
 '서울', '은평구', 1,
 DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 8 DAY),
 NULL, NOW(), NOW()),

-- ACTIVE: 제주 동문 vs 서문
(801015, '제주 동문시장 야시장 vs 서문시장 오메기떡, 제주 전통시장 맛집은?',
 '동문시장 야시장', '서문시장 오메기떡', 'ACTIVE',
 2, 1, 3, NULL, 0.00, NULL,
 '제주', '제주시', 4,
 DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW());

-- ── 투표 ─────────────────────────────────────────────────────

-- 801001 한남동(A) 4 : 이태원(B) 3
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801001, 1,   'A', TRUE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(801001, 2,   'A', TRUE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(801001, 7,   'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801001, 101, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801001, 4,   'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801001, 102, 'B', TRUE, NOW(), NOW()),
(801001, 103, 'B', TRUE, NOW(), NOW());

-- 801002 성수동(A) 4 : 왕십리(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801002, 1, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801002, 2, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801002, 6, 'A', TRUE, NOW(), NOW()),
(801002, 8, 'A', TRUE, NOW(), NOW()),
(801002, 3, 'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801002, 5, 'B', TRUE, NOW(), NOW());

-- 801003 일산(A) 3 : 행주(B) 1
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801003, 3,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801003, 7,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801003, 10, 'A', TRUE, NOW(), NOW()),
(801003, 9,  'B', TRUE, NOW(), NOW());

-- 801004 광안대교(A) 2 : 오륙도(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801004, 4, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801004, 2, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801004, 5, 'B', TRUE, NOW(), NOW()),
(801004, 6, 'B', TRUE, NOW(), NOW());

-- 801005 반포(A) 4 : 잠원(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801005, 2,   'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801005, 4,   'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801005, 101, 'A', TRUE, NOW(), NOW()),
(801005, 102, 'A', TRUE, NOW(), NOW()),
(801005, 1,   'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801005, 103, 'B', TRUE, NOW(), NOW());

-- 801006 판교(A) 3 : 분당(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801006, 3,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801006, 6,  'A', TRUE, NOW(), NOW()),
(801006, 10, 'A', TRUE, NOW(), NOW()),
(801006, 8,  'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801006, 9,  'B', TRUE, NOW(), NOW());

-- 801007 차이나타운(A) 2 : 닭강정(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801007, 6, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801007, 4, 'A', TRUE, NOW(), NOW()),
(801007, 1, 'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801007, 2, 'B', TRUE, NOW(), NOW());

-- 801008 망원시장(A) 3 : 공덕시장(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801008, 1, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(801008, 2, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801008, 7, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801008, 3, 'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801008, 8, 'B', TRUE, NOW(), NOW());

-- 801009 계룡산(A) 2 : 갑천(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801009, 8, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801009, 3, 'A', TRUE, NOW(), NOW()),
(801009, 5, 'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801009, 9, 'B', TRUE, NOW(), NOW());

-- 801010 북한산(A) 3 : 수유계곡(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801010, 1,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801010, 2,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801010, 3,  'A', TRUE, NOW(), NOW()),
(801010, 7,  'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801010, 10, 'B', TRUE, NOW(), NOW());

-- 801011 인사동(A) 4 승리 : 익선동(B) 3
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801011, 1, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(801011, 2, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(801011, 5, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(801011, 7, 'A', TRUE,  DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(801011, 3, 'B', FALSE, DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(801011, 4, 'B', FALSE, DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(801011, 6, 'B', FALSE, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY));

-- 801012 수원화성(A) 2 : 광교(B) 3 승리
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801012, 1, 'A', FALSE, DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(801012, 4, 'A', FALSE, DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(801012, 3, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(801012, 8, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(801012, 9, 'B', TRUE,  DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY));

-- 801013 DRAW 서면(A) 2 : 부전(B) 2
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801013, 4, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
(801013, 5, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 9 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY)),
(801013, 6, 'B', TRUE, DATE_SUB(NOW(), INTERVAL 9 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY)),
(801013, 9, 'B', TRUE, DATE_SUB(NOW(), INTERVAL 8 DAY),  DATE_SUB(NOW(), INTERVAL 3 DAY));

-- 801015 동문(A) 2 : 서문(B) 1
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at) VALUES
(801015, 4,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801015, 10, 'A', TRUE, NOW(), NOW()),
(801015, 7,  'B', TRUE, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW());

-- ── 댓글 ─────────────────────────────────────────────────────

INSERT INTO comment (battle_id, member_id, content, deleted_at, created_at, updated_at) VALUES
-- 801001 한남동 vs 이태원
(801001, 1,  '한남동 테라스 카페 뷰가 정말 압도적이에요. 루프탑에서 한강 보이는 카페들이 모여있어서 데이트 코스로는 최고예요.', NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(801001, 2,  '이태원은 세계 각국 요리를 한 자리에서 즐길 수 있어서 매력이 달라요. 미국식 버거부터 중동 요리까지 다 있으니까요.', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801001, 4,  '저는 이태원 골목 숨어있는 로컬 바들이 너무 좋더라고요. 글로벌 분위기는 한남동보다 이태원이 확실해요.', NULL, DATE_SUB(NOW(), INTERVAL 12 HOUR), NOW()),

-- 801002 성수동 vs 왕십리
(801002, 1,  '성수동 수제화 골목에서 카페 한 잔하고 갤러리 구경하는 코스가 서울에서 제일 힙해요. 강추합니다!', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801002, 6,  '왕십리 광장시장 닭발이 야식으로는 진짜 최고예요. 저녁 먹방 투어는 왕십리가 완승 아닌가요?', NULL, DATE_SUB(NOW(), INTERVAL 6 HOUR), NOW()),

-- 801005 반포 vs 잠원
(801005, 2,  '반포 분수대 쇼 보면서 치맥 먹는 게 서울 여름 버킷리스트죠. 야경도 너무 예뻐서 외국 친구들한테도 자주 데려가요.', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801005, 7,  '잠원 쪽은 주말에도 의외로 자리가 남아 있어서 돗자리 치고 여유롭게 피크닉하기 훨씬 편해요. 반포는 너무 사람 많음.', NULL, DATE_SUB(NOW(), INTERVAL 6 HOUR), NOW()),

-- 801006 판교 vs 분당
(801006, 3,  '판교는 IT 기업들이 많아서 평일 점심에 카페 자리 잡기가 경쟁이에요. 그래도 커피 퀄리티는 확실히 높죠.', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801006, 8,  '분당 정자역 로데오는 주말에 걷기 좋고, 분위기 있는 카페들이 즐비해서 여유로운 카공하기 딱 좋아요.', NULL, DATE_SUB(NOW(), INTERVAL 6 HOUR), NOW()),

-- 801008 망원시장 vs 공덕시장 (member 1 거주지)
(801008, 1,  '망원시장은 수제 음식들이 정말 다양하고 신선해요. 마포 살면서 주말마다 거의 들리는데, 갈 때마다 새로운 가게가 생겨있어요.', NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW()),
(801008, 3,  '공덕 쪽도 직장인들 입맛 맞게 다양한 메뉴가 있어요. 가성비 국밥이나 순대국 골목이 특히 훌륭해요.', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),

-- 801011 인사동 vs 익선동 (CLOSED)
(801011, 1,  '인사동은 전통 공예품이나 한국화 갤러리가 많아서 외국 친구들 데려가기 진짜 좋아요. 한국 문화 느끼기엔 최고죠.', NULL, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW()),
(801011, 2,  '익선동은 낡은 한옥에 현대 감성이 섞인 게 정말 매력적이에요. 인스타 사진 찍기는 익선동 완승인데, 전통 문화 체험은 인사동이죠.', NULL, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),
(801011, 4,  '두 곳 다 종로에서 도보권인데 분위기가 완전 달라요. 조용하게 쇼핑하고 싶다면 인사동, 힙하게 카페 투어하고 싶다면 익선동이죠.', NULL, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),

-- 801012 수원화성 vs 광교 (CLOSED)
(801012, 3,  '광교호수공원 저녁 산책로가 정말 잘 조성되어 있어요. 수변 카페들도 많아서 데이트 코스로 가성비 최고예요.', NULL, DATE_SUB(NOW(), INTERVAL 8 DAY), NOW()),
(801012, 1,  '수원화성은 유네스코 세계문화유산이라 역사적 가치가 남다르죠. 외국인한테 한국 역사 알려주기엔 화성이 압도적이에요.', NULL, DATE_SUB(NOW(), INTERVAL 7 DAY), NOW()),

-- 801015 제주 동문 vs 서문
(801015, 4,  '동문시장 야시장은 제주 여행 온 사람이라면 무조건 한 번은 가야 해요. 오메기떡부터 흑돼지 구이까지 다 있어요!', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW()),
(801015, 7,  '서문시장 오메기떡은 제주도 정겨움이 그대로 담겨있어요. 관광객 많은 동문보다 서문이 더 로컬 느낌 나서 좋아요.', NULL, DATE_SUB(NOW(), INTERVAL 6 HOUR), NOW());

COMMIT;

-- ============================================================
-- 2. MARKET DB
-- ============================================================
