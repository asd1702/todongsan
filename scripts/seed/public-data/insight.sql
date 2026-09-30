-- ============================================================
-- insight DB 공공 데이터 테스트 시드
-- 대상: public_data_snapshot
-- 목적: price-history / public-data-reference 엔드포인트 테스트
--
-- 커버 시나리오:
--   1. 전국 마켓     (regionSido="전국")
--   2. 서울 전체     (regionSido="서울", regionSigu=null)
--   3. 서울 강남구   (regionSido="서울", regionSigu="강남구")
--   4. 경기 전체     (regionSido="경기", regionSigu=null)
--
-- 재실행 가능 (ON DUPLICATE KEY UPDATE 적용)
-- ============================================================

-- ============================================================
-- 주간 매매가격지수 (WEEKLY_PRICE_INDEX) — 최근 8주
-- 조회 범위: today.minusWeeks(8) ~ today
-- ============================================================

INSERT INTO public_data_snapshot
    (source, data_type, reference_date, region_sido, source_region_id, region_fullpath,
     itm_id, itm_nm, numeric_value, raw_data, collected_at, created_at, updated_at)
VALUES
-- 전국 주간
('REB','WEEKLY_PRICE_INDEX','2026-06-16','전국','1','전국','A','매매가격지수',98.52,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":98.52,"wrtimeDesc":"2026-06-16","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-09','전국','1','전국','A','매매가격지수',98.31,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":98.31,"wrtimeDesc":"2026-06-09","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-02','전국','1','전국','A','매매가격지수',98.18,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":98.18,"wrtimeDesc":"2026-06-02","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-26','전국','1','전국','A','매매가격지수',98.05,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":98.05,"wrtimeDesc":"2026-05-26","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-19','전국','1','전국','A','매매가격지수',97.93,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":97.93,"wrtimeDesc":"2026-05-19","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-12','전국','1','전국','A','매매가격지수',97.88,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":97.88,"wrtimeDesc":"2026-05-12","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-05','전국','1','전국','A','매매가격지수',97.76,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":97.76,"wrtimeDesc":"2026-05-05","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-04-28','전국','1','전국','A','매매가격지수',97.64,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":97.64,"wrtimeDesc":"2026-04-28","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),

-- 서울 주간
('REB','WEEKLY_PRICE_INDEX','2026-06-16','서울','11','서울','A','매매가격지수',101.24,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":101.24,"wrtimeDesc":"2026-06-16","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-09','서울','11','서울','A','매매가격지수',100.97,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.97,"wrtimeDesc":"2026-06-09","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-02','서울','11','서울','A','매매가격지수',100.78,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.78,"wrtimeDesc":"2026-06-02","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-26','서울','11','서울','A','매매가격지수',100.61,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.61,"wrtimeDesc":"2026-05-26","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-19','서울','11','서울','A','매매가격지수',100.43,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.43,"wrtimeDesc":"2026-05-19","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-12','서울','11','서울','A','매매가격지수',100.28,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.28,"wrtimeDesc":"2026-05-12","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-05','서울','11','서울','A','매매가격지수',100.12,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.12,"wrtimeDesc":"2026-05-05","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-04-28','서울','11','서울','A','매매가격지수',99.95,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":99.95,"wrtimeDesc":"2026-04-28","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),

-- 서울>강남구 주간 (region_fullpath LIKE '%강남구%' 조건 충족)
('REB','WEEKLY_PRICE_INDEX','2026-06-16','서울','11230','서울>강남구','A','매매가격지수',104.71,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":104.71,"wrtimeDesc":"2026-06-16","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-09','서울','11230','서울>강남구','A','매매가격지수',104.45,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":104.45,"wrtimeDesc":"2026-06-09","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-02','서울','11230','서울>강남구','A','매매가격지수',104.21,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":104.21,"wrtimeDesc":"2026-06-02","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-26','서울','11230','서울>강남구','A','매매가격지수',103.98,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":103.98,"wrtimeDesc":"2026-05-26","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-19','서울','11230','서울>강남구','A','매매가격지수',103.74,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":103.74,"wrtimeDesc":"2026-05-19","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-12','서울','11230','서울>강남구','A','매매가격지수',103.51,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":103.51,"wrtimeDesc":"2026-05-12","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-05','서울','11230','서울>강남구','A','매매가격지수',103.29,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":103.29,"wrtimeDesc":"2026-05-05","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-04-28','서울','11230','서울>강남구','A','매매가격지수',103.08,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":103.08,"wrtimeDesc":"2026-04-28","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),

-- 경기 주간
('REB','WEEKLY_PRICE_INDEX','2026-06-16','경기','41','경기','A','매매가격지수',96.83,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.83,"wrtimeDesc":"2026-06-16","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-09','경기','41','경기','A','매매가격지수',96.67,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.67,"wrtimeDesc":"2026-06-09","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-06-02','경기','41','경기','A','매매가격지수',96.54,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.54,"wrtimeDesc":"2026-06-02","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-26','경기','41','경기','A','매매가격지수',96.38,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.38,"wrtimeDesc":"2026-05-26","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-19','경기','41','경기','A','매매가격지수',96.22,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.22,"wrtimeDesc":"2026-05-19","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-12','경기','41','경기','A','매매가격지수',96.09,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.09,"wrtimeDesc":"2026-05-12","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-05-05','경기','41','경기','A','매매가격지수',95.95,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":95.95,"wrtimeDesc":"2026-05-05","dtaCycleCd":"WK"}',NOW(),NOW(),NOW()),
('REB','WEEKLY_PRICE_INDEX','2026-04-28','경기','41','경기','A','매매가격지수',95.82,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":95.82,"wrtimeDesc":"2026-04-28","dtaCycleCd":"WK"}',NOW(),NOW(),NOW())

AS new_vals(source, data_type, reference_date, region_sido, source_region_id, region_fullpath,
            itm_id, itm_nm, numeric_value, raw_data, collected_at, created_at, updated_at)
ON DUPLICATE KEY UPDATE
    region_sido    = new_vals.region_sido,
    region_fullpath= new_vals.region_fullpath,
    itm_nm         = new_vals.itm_nm,
    numeric_value  = new_vals.numeric_value,
    raw_data       = new_vals.raw_data,
    collected_at   = new_vals.collected_at,
    updated_at     = new_vals.updated_at;


-- ============================================================
-- 월간 매매가격지수 (MONTHLY_PRICE_INDEX) — 최근 6개월 (폴백)
-- 조회 범위: today.minusMonths(6) ~ today
-- ============================================================

INSERT INTO public_data_snapshot
    (source, data_type, reference_date, region_sido, source_region_id, region_fullpath,
     itm_id, itm_nm, numeric_value, raw_data, collected_at, created_at, updated_at)
VALUES
-- 전국 월간
('REB','MONTHLY_PRICE_INDEX','2026-06-01','전국','1','전국','A','매매가격지수',98.60,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":98.60,"wrtimeDesc":"2026년 06월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-05-01','전국','1','전국','A','매매가격지수',98.10,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":98.10,"wrtimeDesc":"2026년 05월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-04-01','전국','1','전국','A','매매가격지수',97.74,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":97.74,"wrtimeDesc":"2026년 04월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-03-01','전국','1','전국','A','매매가격지수',97.41,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":97.41,"wrtimeDesc":"2026년 03월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-02-01','전국','1','전국','A','매매가격지수',97.18,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":97.18,"wrtimeDesc":"2026년 02월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-01-01','전국','1','전국','A','매매가격지수',96.95,'{"clsId":1,"clsFullnm":"전국","itmId":"A","itmNm":"매매가격지수","dtaVal":96.95,"wrtimeDesc":"2026년 01월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),

-- 서울 월간
('REB','MONTHLY_PRICE_INDEX','2026-06-01','서울','11','서울','A','매매가격지수',101.30,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":101.30,"wrtimeDesc":"2026년 06월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-05-01','서울','11','서울','A','매매가격지수',100.62,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.62,"wrtimeDesc":"2026년 05월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-04-01','서울','11','서울','A','매매가격지수',100.11,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":100.11,"wrtimeDesc":"2026년 04월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-03-01','서울','11','서울','A','매매가격지수',99.74,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":99.74,"wrtimeDesc":"2026년 03월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-02-01','서울','11','서울','A','매매가격지수',99.38,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":99.38,"wrtimeDesc":"2026년 02월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-01-01','서울','11','서울','A','매매가격지수',99.05,'{"clsId":11,"clsFullnm":"서울","itmId":"A","itmNm":"매매가격지수","dtaVal":99.05,"wrtimeDesc":"2026년 01월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),

-- 서울>강남구 월간
('REB','MONTHLY_PRICE_INDEX','2026-06-01','서울','11230','서울>강남구','A','매매가격지수',104.82,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":104.82,"wrtimeDesc":"2026년 06월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-05-01','서울','11230','서울>강남구','A','매매가격지수',104.21,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":104.21,"wrtimeDesc":"2026년 05월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-04-01','서울','11230','서울>강남구','A','매매가격지수',103.67,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":103.67,"wrtimeDesc":"2026년 04월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-03-01','서울','11230','서울>강남구','A','매매가격지수',103.14,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":103.14,"wrtimeDesc":"2026년 03월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-02-01','서울','11230','서울>강남구','A','매매가격지수',102.83,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":102.83,"wrtimeDesc":"2026년 02월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-01-01','서울','11230','서울>강남구','A','매매가격지수',102.49,'{"clsId":11230,"clsFullnm":"서울>강남구","itmId":"A","itmNm":"매매가격지수","dtaVal":102.49,"wrtimeDesc":"2026년 01월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),

-- 경기 월간
('REB','MONTHLY_PRICE_INDEX','2026-06-01','경기','41','경기','A','매매가격지수',96.90,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.90,"wrtimeDesc":"2026년 06월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-05-01','경기','41','경기','A','매매가격지수',96.41,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.41,"wrtimeDesc":"2026년 05월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-04-01','경기','41','경기','A','매매가격지수',96.02,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":96.02,"wrtimeDesc":"2026년 04월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-03-01','경기','41','경기','A','매매가격지수',95.67,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":95.67,"wrtimeDesc":"2026년 03월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-02-01','경기','41','경기','A','매매가격지수',95.38,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":95.38,"wrtimeDesc":"2026년 02월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW()),
('REB','MONTHLY_PRICE_INDEX','2026-01-01','경기','41','경기','A','매매가격지수',95.11,'{"clsId":41,"clsFullnm":"경기","itmId":"A","itmNm":"매매가격지수","dtaVal":95.11,"wrtimeDesc":"2026년 01월","dtaCycleCd":"MM"}',NOW(),NOW(),NOW())

AS new_vals(source, data_type, reference_date, region_sido, source_region_id, region_fullpath,
            itm_id, itm_nm, numeric_value, raw_data, collected_at, created_at, updated_at)
ON DUPLICATE KEY UPDATE
    region_sido    = new_vals.region_sido,
    region_fullpath= new_vals.region_fullpath,
    itm_nm         = new_vals.itm_nm,
    numeric_value  = new_vals.numeric_value,
    raw_data       = new_vals.raw_data,
    collected_at   = new_vals.collected_at,
    updated_at     = new_vals.updated_at;


-- ============================================================
-- 검증 쿼리
-- ============================================================

SELECT data_type, region_sido, COUNT(*) AS cnt
FROM public_data_snapshot
WHERE source = 'REB'
GROUP BY data_type, region_sido
ORDER BY data_type, region_sido;
