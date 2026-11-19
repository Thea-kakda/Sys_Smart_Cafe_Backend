package com.smartcoffee.identity;

import com.smartcoffee.SmartCoffeeApplication;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        classes = SmartCoffeeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.jwt.secret="
                        + "VGhpcy1pcy1hLXRlc3Qtb25seS1qd3Qtc2VjcmV0"
                        + "LXdpdGgtc3VmZmljaWVudC1sZW5ndGgtZm9yLUhTMjU2"
        }
)
@Import(TestcontainersConfiguration.class)
class IdentityServiceApplicationTests {

    @MockitoBean
    private ObjectStorageService objectStorageService;

    @Test
    void contextLoads() {
    }
}