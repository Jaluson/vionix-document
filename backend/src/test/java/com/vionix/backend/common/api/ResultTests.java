package com.vionix.backend.common.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTests {

    @Test
    void okUsesBaselineSuccessShape() {
        Result<Map<String, String>> result = Result.ok(Map.of("status", "UP"));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.message()).isEqualTo("success");
        assertThat(result.data()).containsEntry("status", "UP");
        assertThat(result.traceId()).isNotBlank();
    }

    @Test
    void errorUsesDocumentedCode() {
        Result<Void> result = Result.error(ErrorCode.FORBIDDEN);

        assertThat(result.code()).isEqualTo("FORBIDDEN");
        assertThat(result.message()).isEqualTo("permission denied");
        assertThat(result.data()).isNull();
        assertThat(result.traceId()).isNotBlank();
    }
}
