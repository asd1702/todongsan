package com.todongsan.memberpointservice.global.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 차감 경로 워밍업은 데이터를 남기지 않는다 (SPEND_CIRCUIT_BREAKER.md §6).
 * 실제 포트로 띄워 HTTP 단계(금액 0 차감)와 롤백 전용 차감 단계를 모두 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "member-point.warmup.enabled=true",
        "member-point.warmup.http-requests=5",
        "member-point.warmup.spend-calls=5",
        // 테스트 application.yml이 main 설정을 가리므로 프로브 설정을 직접 준다 (main 설정은 E2E에서 확인)
        "management.endpoint.health.probes.enabled=true"
})
@ExtendWith(OutputCaptureExtension.class)
class SpendPathWarmupTest {

    @Autowired SpendPathWarmup warmup;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 워밍업은_차감_경로를_실행하지만_잔액과_이력을_남기지_않는다(CapturedOutput output) {
        Member member = memberRepository.save(Member.builder()
                .nickname("warmup-test").email("warmup-test@kakao.com").oauthProvider("KAKAO").oauthId("warmup-test")
                .build());
        jdbcTemplate.update("UPDATE member SET point_balance = ? WHERE id = ?", new BigDecimal("500.00"), member.getId());
        long historiesBefore = count("SELECT COUNT(*) FROM point_history");

        warmup.run(null);

        assertThat(output).contains("Spend path warmup done. http=5, spend=5");
        assertThat(jdbcTemplate.queryForObject("SELECT point_balance FROM member WHERE id = ?", BigDecimal.class, member.getId()))
                .isEqualByComparingTo("500.00");
        assertThat(count("SELECT COUNT(*) FROM point_history")).isEqualTo(historiesBefore);
        assertThat(count("SELECT COUNT(*) FROM point_history WHERE idempotency_key LIKE 'WARMUP:%'")).isZero();
    }

    /** 프로브는 인증 없이 읽을 수 있어야 한다. 막히면(403) 오케스트레이터가 인스턴스를 영영 준비되지 않은 것으로 본다 */
    @Test
    void readiness_프로브는_인증_없이_UP을_돌려준다(@Autowired TestRestTemplate rest) {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"UP\"");
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
