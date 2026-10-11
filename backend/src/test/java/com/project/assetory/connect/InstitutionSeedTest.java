package com.project.assetory.connect;

import com.project.assetory.connect.domain.Institution;
import com.project.assetory.connect.domain.InstitutionType;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// institutions 시드(data.sql)가 CODEF 공식 증권사 목록과 일치하는지 고정한다
class InstitutionSeedTest extends IntegrationTestSupport {

    // CODEF 개발자 문서 "기관 목록 > 증권사 목록" (22개)
    static final Map<String, String> CODEF_SECURITIES = Map.ofEntries(
            Map.entry("0209", "유안타증권"),
            Map.entry("0218", "KB증권"),
            Map.entry("0238", "미래에셋증권"),
            Map.entry("0240", "삼성증권"),
            Map.entry("0243", "한국투자증권"),
            Map.entry("0247", "NH투자증권"),
            Map.entry("0261", "교보증권"),
            Map.entry("0264", "키움증권"),
            Map.entry("0265", "LS증권"),
            Map.entry("0266", "SK증권"),
            Map.entry("0267", "대신증권"),
            Map.entry("1267", "크레온"),
            Map.entry("0269", "한화투자증권"),
            Map.entry("0270", "하나금융투자"),
            Map.entry("0278", "신한금융투자"),
            Map.entry("0279", "DB금융투자"),
            Map.entry("0280", "유진투자증권"),
            Map.entry("0287", "메리츠종합금융증권"),
            Map.entry("1247", "나무"),
            Map.entry("0262", "하이투자증권"),
            Map.entry("0225", "IBK투자증권"),
            Map.entry("0227", "다올투자증권"));

    // CODEF 개발자 문서 "기관 목록 > 은행 목록" (20개) — 카카오뱅크·토스뱅크는 미지원
    static final Map<String, String> CODEF_BANKS = Map.ofEntries(
            Map.entry("0002", "산업은행"),
            Map.entry("0003", "기업은행"),
            Map.entry("0004", "국민은행"),
            Map.entry("0007", "수협은행"),
            Map.entry("0011", "농협은행"),
            Map.entry("0020", "우리은행"),
            Map.entry("0023", "SC은행"),
            Map.entry("0027", "씨티은행"),
            Map.entry("0031", "대구은행"),
            Map.entry("0032", "부산은행"),
            Map.entry("0034", "광주은행"),
            Map.entry("0035", "제주은행"),
            Map.entry("0037", "전북은행"),
            Map.entry("0039", "경남은행"),
            Map.entry("0045", "새마을금고"),
            Map.entry("0048", "신협은행"),
            Map.entry("0071", "우체국"),
            Map.entry("0081", "KEB하나은행"),
            Map.entry("0088", "신한은행"),
            Map.entry("0089", "K뱅크"));

    @Autowired InstitutionRepository institutionRepository;

    @Test
    @DisplayName("증권사 시드 = CODEF 공식 목록 22개와 코드가 정확히 일치 (빠짐·추가 없음)")
    void securitiesCodesMatchCodef() {
        List<String> seeded = securities().stream().map(Institution::getCodefOrgCode).toList();

        assertThat(seeded).hasSize(22).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(CODEF_SECURITIES.keySet());
    }

    @Test
    @DisplayName("증권사 이름은 CODEF 공식 이름을 포함한다 (괄호 보조 표기 제외)")
    void securitiesNamesFollowCodef() {
        Map<String, String> seeded = securities().stream()
                .collect(Collectors.toMap(Institution::getCodefOrgCode, Institution::getName));

        CODEF_SECURITIES.forEach((code, codefName) ->
                assertThat(seeded.get(code)).as("기관 코드 %s", code).contains(codefName));
    }

    @Test
    @DisplayName("은행 시드 = CODEF 공식 목록 20개와 코드가 정확히 일치 (CODEF 미지원 은행 없음)")
    void bankCodesMatchCodef() {
        List<String> seeded = banks().stream().map(Institution::getCodefOrgCode).toList();

        assertThat(seeded).hasSize(20).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(CODEF_BANKS.keySet())
                .doesNotContainAnyElementsOf(CODEF_SECURITIES.keySet());
    }

    @Test
    @DisplayName("은행 이름은 CODEF 공식 이름과 같다")
    void bankNamesFollowCodef() {
        Map<String, String> seeded = banks().stream()
                .collect(Collectors.toMap(Institution::getCodefOrgCode, Institution::getName));

        assertThat(seeded).containsExactlyInAnyOrderEntriesOf(CODEF_BANKS);
    }

    private List<Institution> banks() {
        return institutionRepository.findAll().stream()
                .filter(i -> i.getInstitutionType() == InstitutionType.BANK).toList();
    }

    private List<Institution> securities() {
        return institutionRepository.findAll().stream()
                .filter(i -> i.getInstitutionType() == InstitutionType.SECURITIES).toList();
    }
}
