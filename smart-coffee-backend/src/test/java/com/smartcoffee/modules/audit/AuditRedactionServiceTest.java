package com.smartcoffee.modules.audit;

import com.smartcoffee.modules.audit.application.AuditRedactionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRedactionServiceTest {

    private final AuditRedactionService service =
            new AuditRedactionService(
                    JsonMapper.builder().build()
            );

    @Test
    void shouldRedactSensitiveValues() {
        String result = service.safe(
                new LoginRequest(
                        "Superadmin@example.com",
                        "Superadmin@example123"
                )
        );

        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("Superadmin@example123");
    }

    private record LoginRequest(
            String email,
            String password
    ) {
    }
}
