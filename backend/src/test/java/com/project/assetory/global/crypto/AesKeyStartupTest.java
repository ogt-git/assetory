package com.project.assetory.global.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

// 키 설정이 잘못되면 컨텍스트(=애플리케이션)가 뜨지 않아야 한다
class AesKeyStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AesEncryptionUtil.class);

    @Test
    @DisplayName("키 누락 → 기동 실패")
    void missingKey_failsStartup() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ASSETORY_AES_KEY");
        });
    }

    @Test
    @DisplayName("빈 문자열 키 → 기동 실패")
    void blankKey_failsStartup() {
        runner.withPropertyValues("assetory.crypto.aes-key=  ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("32바이트가 아닌 키 → 기동 실패")
    void wrongLengthKey_failsStartup() {
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);

        runner.withPropertyValues("assetory.crypto.aes-key=" + key16)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("32바이트");
                });
    }

    @Test
    @DisplayName("Base64가 아닌 키 → 기동 실패")
    void nonBase64Key_failsStartup() {
        runner.withPropertyValues("assetory.crypto.aes-key=this is not base64!!")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Base64 32바이트 키 → 정상 기동")
    void validKey_starts() {
        runner.withPropertyValues("assetory.crypto.aes-key=" + AesEncryptionUtilTest.testKey(1))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AesEncryptionUtil.class));
    }
}
