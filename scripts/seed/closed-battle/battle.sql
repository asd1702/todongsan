-- DEV ONLY — Closed Battle seed for AI report prompt testing
-- Prerequisites: run the frontend and supplement scenarios first.
--
-- 목적: 개선된 ClaudeApiClient 프롬프트 검증을 위한 종료 배틀 + 다양한 투표 데이터
-- 배틀 800013: 잠실 vs 강남 (서울 송파구), CLOSED, A(잠실) 승리 7:3
--
-- 무결성 사전 검사:
--   [OK] battle id=800013 → 기존 시드에 없음 (800001~800012 만 존재)
--   [OK] insight_report (BATTLE, 800013) → 없음 (UniqueConstraint 충돌 없음)
--   [OK] member_id 1~10 → frontend member-point seed에서 INSERT 완료
--   [OK] option_a_count(7) + option_b_count(3) = vote_count(10) 일치
--   [OK] battle_vote (800013, member_id) 중복 없음 — 각 멤버 1행
--
-- 투표자 인구 통계 (MemberPointClient 가 반환할 데이터 기준):
--   member 1  : AGE_20S,      MALE,   서울 마포구   → A
--   member 2  : AGE_30S,      FEMALE, 서울 강남구   → A
--   member 3  : AGE_20S,      MALE,   경기 수원시   → A
--   member 4  : AGE_40S,      FEMALE, 부산 해운대구 → B
--   member 5  : AGE_50S_ABOVE,MALE,   대구 중구     → B
--   member 6  : AGE_20S,      FEMALE, 인천 연수구   → A
--   member 7  : AGE_30S,      MALE,   광주 서구     → A
--   member 8  : AGE_20S,      FEMALE, 대전 유성구   → A
--   member 9  : AGE_30S,      MALE,   울산 남구     → B
--   member 10 : AGE_20S,      FEMALE, 세종 세종시   → A
--
-- 예상 집계 결과:
--   연령별 A 선호율: 20대 5/5=100%, 30대 2/3=66.7%, 40대 0/1=0%, 50대이상 0/1=0%
--   성별 A 선호율:   남성 3/4=75%, 여성 4/6=66.7%
--   지역별 A(상위): 서울 1/2=50%, 경기 1/1=100%, 부산 0/1=0%, 대구 0/1=0%, 인천 1/1=100%
--
-- Run with scripts/seed/Invoke-TodongsanSeed.ps1 -Scenario closed-battle.

-- ============================================================
-- 1. battle DB
-- ============================================================
-- 재실행 안전 정리
DELETE FROM battle_vote WHERE battle_id = 800013;
DELETE FROM battle WHERE id = 800013;

-- 배틀 INSERT
-- 무결성: option_a_count(7) + option_b_count(3) = vote_count(10) ✓
--         status='CLOSED', winning_option='A', settled_at IS NOT NULL ✓
INSERT INTO battle (id, title, option_a, option_b, status,
                    option_a_count, option_b_count, vote_count,
                    winning_option, reward_amount, settled_at,
                    sido, sigu, created_by, start_at, end_at,
                    deleted_at, created_at, updated_at)
VALUES
(800013, '잠실 vs 강남, 서울 대표 상권은?', '잠실', '강남', 'CLOSED',
 7, 3, 10, 'A', 10.00, DATE_SUB(NOW(), INTERVAL 1 DAY),
 '서울', '송파구', 1,
 DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY),
 NULL, DATE_SUB(NOW(), INTERVAL 9 DAY), NOW());

-- 투표 INSERT — (battle_id, member_id) 쌍 중복 없음 ✓
-- A(잠실): member 1,2,3,6,7,8,10 → 7표
-- B(강남): member 4,5,9 → 3표
INSERT INTO battle_vote (battle_id, member_id, selected_option, is_rewarded, created_at, updated_at)
VALUES
(800013, 1,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 2,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 3,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 4,  'B', TRUE, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 5,  'B', TRUE, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 6,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 7,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 8,  'A', TRUE, DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 9,  'B', TRUE, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(800013, 10, 'A', TRUE, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY));

-- ============================================================
-- 2. insight DB — insight_report 없음 (트리거 API 테스트용)
-- ============================================================
-- 아래 3가지 방법으로 리포트 생성 가능:
--   A) API 트리거:  POST /internal/insights/battles/800013/trigger
--   B) 사용자 요청: POST /api/v1/insights/battles/800013/report  (80P 차감)
--   C) 즉시 DONE 상태 삽입 (렌더링 확인용 → 아래 주석 해제)

-- [선택] DONE 상태 샘플 리포트 삽입 (프론트 렌더링 확인용)
-- DELETE FROM insight_report WHERE type = 'BATTLE' AND reference_id = 800013;
-- INSERT INTO insight_report (id, type, reference_id, status, summary, analysis_data,
--                              raw_prompt, failed_reason,
--                              processing_started_at, retry_count,
--                              requested_by, report_content, generated_at,
--                              created_at, updated_at)
-- VALUES
-- (700006, 'BATTLE', 800013, 'DONE',
--  '잠실 70% 우세. 20대 전원 잠실 선택. 지역별로는 경기·인천 거주자도 잠실 선호.',
--  NULL,
--  NULL, NULL,
--  DATE_SUB(NOW(), INTERVAL 10 MINUTE), 0,
--  0,
--  'title: 잠실 vs 강남 서울 대표 상권 배틀 AI 분석 리포트\nsummary: 10명이 참여한 이번 배틀에서 잠실이 70%(7표)로 우세했습니다. 20대 참여자 전원이 잠실을 선택했으며, 30대는 2:1로 잠실을 선호했습니다. 40대·50대 이상은 강남을 지지해 연령별 뚜렷한 역전 현상이 관찰되었습니다.\ncontent: |\n  ## 배틀 개요\n  잠실 vs 강남 대표 상권 배틀에 총 10명이 참여했습니다. 잠실(A) 7표(70%), 강남(B) 3표(30%)으로 잠실이 우위를 점했습니다.\n\n  ## 투표 패턴 분석\n  ### 연령별 분포\n  20대 5명 전원(100%)이 잠실을 선택했으며, 30대는 2:1(66.7%), 40대·50대 이상은 각 1명씩 강남을 선택했습니다.\n\n  ### 성별 분포\n  남성 4명 중 3명(75%), 여성 6명 중 4명(66.7%)이 잠실을 선호해 성별 간 큰 차이는 없었습니다.\n\n  ### 지역별 분포\n  서울 거주자(1/2=50%) 외에 경기·인천 거주자도 잠실을 선택했습니다.\n\n  ## 주요 인사이트\n  1. **20대 압도적 잠실 선호**: 젊은 층은 롯데월드·스타필드 등 대형 복합 쇼핑몰 인프라를 높이 평가.\n  2. **중장년층 강남 지지**: 40대 이상은 강남역 중심 상권의 접근성과 브랜드 신뢰를 선호.\n  3. **비수도권 거주자도 잠실 선호**: 서울 방문 시 랜드마크로서의 잠실 인지도 반영.\n\n  ## 데이터 신뢰도 및 한계\n  표본 10명으로 통계적 유의성이 낮으며, 온라인 자발적 참여에 따른 연령 편향 가능성이 있습니다.\n\n  ## 종합 결론\n  잠실의 우세는 20대 집중 투표에 기인합니다. 운영팀 제안: 연령대별 세분화 배틀 또는 세부 요인(식당, 쇼핑, 교통) 주제 배틀로 확장 시 더 정밀한 인사이트를 얻을 수 있습니다.',
--  DATE_SUB(NOW(), INTERVAL 5 MINUTE),
--  DATE_SUB(NOW(), INTERVAL 15 MINUTE), NOW());

-- ============================================================
-- 검증 쿼리 (실행 후 아래 SELECT로 데이터 확인)
-- ============================================================
-- SELECT id, title, option_a, option_b, status, option_a_count, option_b_count,
--        vote_count, winning_option, settled_at FROM battle WHERE id = 800013;
-- SELECT battle_id, member_id, selected_option FROM battle_vote WHERE battle_id = 800013 ORDER BY member_id;
--
-- SELECT id, type, reference_id, status FROM insight_report WHERE reference_id = 800013;
