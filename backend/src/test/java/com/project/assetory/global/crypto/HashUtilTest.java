package com.project.assetory.global.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashUtilTest {

    @Test
    @DisplayName("SHA-256 표준 테스트 벡터(\"abc\")와 일치")
    void knownVector() {
        assertThat(HashUtil.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(HashUtil.sha256Base64("abc"))
                .isEqualTo("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=");
    }

    @Test
    @DisplayName("같은 입력은 같은 해시, 다른 입력은 다른 해시")
    void deterministic() {
        assertThat(HashUtil.sha256Hex("refresh-token")).isEqualTo(HashUtil.sha256Hex("refresh-token"))
                .isNotEqualTo(HashUtil.sha256Hex("refresh-token2"))
                .hasSize(64);
    }

    @Test
    @DisplayName("null 입력 → NPE")
    void nullInput() {
        assertThatThrownBy(() -> HashUtil.sha256Hex(null)).isInstanceOf(NullPointerException.class);
    }
}
