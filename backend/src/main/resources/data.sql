-- institutions 초기 데이터 (docs/05 §4.1). 기동할 때마다 실행되므로 멱등하게 넣는다.
-- id를 고정해 두어야 재실행 때 AUTO_INCREMENT가 계속 늘지 않는다. 새 기관은 끝 번호 다음으로 추가한다.
-- CODEF 개발자 문서 "기관 목록"과 일치 (증권사 22개, 은행 20개 — 카카오뱅크·토스뱅크는 CODEF 미지원). 이름은 CODEF 공식 표기
-- 테스트 InstitutionSeedTest가 이 목록을 고정한다

INSERT INTO institutions (id, codef_org_code, name, institution_type) VALUES
    (1, '0209', '유안타증권', 'SECURITIES'),
    (2, '0218', 'KB증권', 'SECURITIES'),
    (3, '0225', 'IBK투자증권', 'SECURITIES'),
    (4, '0227', '다올투자증권', 'SECURITIES'),
    (5, '0238', '미래에셋증권', 'SECURITIES'),
    (6, '0240', '삼성증권', 'SECURITIES'),
    (7, '0243', '한국투자증권', 'SECURITIES'),
    (8, '0247', 'NH투자증권', 'SECURITIES'),
    (9, '0261', '교보증권', 'SECURITIES'),
    (10, '0262', '하이투자증권', 'SECURITIES'),
    (11, '0264', '키움증권', 'SECURITIES'),
    (12, '0265', 'LS증권', 'SECURITIES'),
    (13, '0266', 'SK증권', 'SECURITIES'),
    (14, '0267', '대신증권', 'SECURITIES'),
    (15, '0269', '한화투자증권', 'SECURITIES'),
    (16, '0270', '하나금융투자', 'SECURITIES'),
    (17, '0278', '신한금융투자', 'SECURITIES'),
    (18, '0279', 'DB금융투자', 'SECURITIES'),
    (19, '0280', '유진투자증권', 'SECURITIES'),
    (20, '0287', '메리츠종합금융증권', 'SECURITIES'),
    (21, '1247', 'NH투자증권 나무', 'SECURITIES'),
    (22, '1267', '대신증권 크레온', 'SECURITIES'),
    (23, '0002', '산업은행', 'BANK'),
    (24, '0003', '기업은행', 'BANK'),
    (25, '0004', '국민은행', 'BANK'),
    (26, '0007', '수협은행', 'BANK'),
    (27, '0011', '농협은행', 'BANK'),
    (28, '0020', '우리은행', 'BANK'),
    (29, '0023', 'SC은행', 'BANK'),
    (30, '0027', '씨티은행', 'BANK'),
    (31, '0031', '대구은행', 'BANK'),
    (32, '0032', '부산은행', 'BANK'),
    (33, '0034', '광주은행', 'BANK'),
    (34, '0035', '제주은행', 'BANK'),
    (35, '0037', '전북은행', 'BANK'),
    (36, '0039', '경남은행', 'BANK'),
    (37, '0045', '새마을금고', 'BANK'),
    (38, '0048', '신협은행', 'BANK'),
    (39, '0071', '우체국', 'BANK'),
    (40, '0081', 'KEB하나은행', 'BANK'),
    (41, '0088', '신한은행', 'BANK'),
    (42, '0089', 'K뱅크', 'BANK')
AS new
ON DUPLICATE KEY UPDATE name = new.name, institution_type = new.institution_type;

-- detection_rules 초기 데이터 (docs/05 §8.1, FR-DT-01). id 1~6 고정, 기동할 때마다 전 컬럼을 시드 값으로 덮어쓴다
-- name · severity · unit NULL은 05에 없는 제안값이다(문서 반영 필요). user_configurable = 임계치를 바꿀 수 있는지
-- 테스트 DetectionRuleSeedTest가 이 값을 고정한다
-- id 2 TOTAL_ASSET_CHANGE 기본 3.0%: 유지 여부 TODO(01-§14 #6)
-- id 3 DEPOSIT_MATURITY 기본 30일: 05의 "30 / 7 일" 중 D-7을 어떻게 둘지는 FR-DT-05 때 결정 (TODO)

INSERT INTO detection_rules (id, rule_code, name, trigger_type, default_threshold, unit, min_value, max_value,
                             individual_limit_per_day, bundle_overflow, severity, user_configurable, is_active) VALUES
    (1, 'STOCK_PRICE_CHANGE', '종목 급등락', 'STOCK_CHANGE', 5.0, '%', 2, 20, 3, true, 'WARNING', true, true),
    (2, 'TOTAL_ASSET_CHANGE', '총자산 변동', 'ASSET_CHANGE', 3.0, '%', 1, 15, 1, false, 'WARNING', true, true),
    (3, 'DEPOSIT_MATURITY', '예금 만기', 'DEPOSIT_MATURITY', 30, '일', 1, 90, NULL, false, 'INFO', true, true),
    (4, 'DAILY_BRIEFING', '일일 브리핑', 'BRIEFING', NULL, NULL, NULL, NULL, 1, false, 'INFO', false, true),
    (5, 'GOAL_PROGRESS_WEEKLY', '주간 목표 리포트', 'GOAL_PROGRESS', NULL, NULL, NULL, NULL, NULL, false, 'INFO', false, true),
    (6, 'MONTHLY_REPORT', '월간 결산', 'MONTHLY_REPORT', NULL, NULL, NULL, NULL, NULL, false, 'INFO', false, true)
AS new
ON DUPLICATE KEY UPDATE rule_code = new.rule_code, name = new.name, trigger_type = new.trigger_type,
                        default_threshold = new.default_threshold, unit = new.unit,
                        min_value = new.min_value, max_value = new.max_value,
                        individual_limit_per_day = new.individual_limit_per_day,
                        bundle_overflow = new.bundle_overflow, severity = new.severity,
                        user_configurable = new.user_configurable, is_active = new.is_active;
