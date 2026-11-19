package com.smartcoffee.modules.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.modules.auth.api.LoginRequest;
import com.smartcoffee.modules.auth.api.RegisterRequest;
import com.smartcoffee.modules.auth.application.AuthenticationService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Identity/RBAC Phase 1 verification against a real migrated database and the
 * live HTTP stack (RANDOM_PORT, real Spring Security filter chain). Uses the
 * project's plain-RestTemplate convention (neither MockMvc autoconfigure nor
 * TestRestTemplate is on the test classpath). Covers: locale normalization
 * (en -> en-US persisted), typed EMAIL_ALREADY_REGISTERED conflict (Master §2.4),
 * single global CUSTOMER assignment path, PUT /users/me phone (+855) and locale
 * validation, and security_version enforcement after logout-all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerRegistrationAndSecurityIT extends IntegrationTestBase {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuthenticationService auth;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private record Resp(int status, String body) {
    }

    private Resp send(HttpMethod method, String path, String json, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        try {
            ResponseEntity<String> r = rest.exchange("http://localhost:" + port + path, method,
                    new HttpEntity<>(json, h), String.class);
            return new Resp(r.getStatusCode().value(), r.getBody());
        } catch (RestClientResponseException ex) {
            return new Resp(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        }
    }

    private String json(Object body) {
        try {
            return body == null ? null : mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Registers through HTTP and returns {accessToken, userId}; asserts 201. */
    private String[] httpRegister(String email, String language) {
        Resp r = send(HttpMethod.POST, "/api/v1/auth/register",
                json(new RegisterRequest("New Customer", email, "Str0ng!Pass", language)), null);
        assertEquals(201, r.status(), "register should return 201: " + r.body());
        try {
            var node = mapper.readTree(r.body());
            return new String[] { node.path("data").path("accessToken").asText(),
                    node.path("data").path("userId").asText() };
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int countGlobalCustomerAssignments(UUID userId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from identity.user_role_assignments ura "
                        + "join identity.roles r on r.role_id = ura.role_id "
                        + "where ura.user_id = ?::uuid and r.role_code = 'CUSTOMER' "
                        + "and ura.store_id is null and ura.revoked_at is null",
                Integer.class, userId.toString());
        return n == null ? 0 : n;
    }

    private int countProfiles(UUID userId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from customer.customer_profiles where user_id = ?::uuid", Integer.class,
                userId.toString());
        return n == null ? 0 : n;
    }

    @Test
    void registerCreatesExactlyOneGlobalCustomerAssignmentAndMeReflectsIt() {
        String email = "cust-" + UUID.randomUUID() + "@smart.coffee";
        String[] reg = httpRegister(email, "en");
        UUID userId = UUID.fromString(reg[1]);

        assertEquals(1, countGlobalCustomerAssignments(userId),
                "registration must create exactly one active global CUSTOMER assignment");
        assertEquals(1, countProfiles(userId), "registration must create exactly one customer profile");

        Resp ctx = send(HttpMethod.GET, "/api/v1/users/me/access-context", null, reg[0]);
        assertEquals(200, ctx.status());
        assertTrue(ctx.body().contains("CUSTOMER"), "access-context must list the CUSTOMER role");

        // Master §2.1/§5.2: the 'en' alias must be normalized to the canonical 'en-US'.
        String storedLang = jdbc.queryForObject(
                "select preferred_language from identity.users where user_id = ?::uuid", String.class,
                userId.toString());
        assertEquals("en-US", storedLang, "locale alias 'en' must persist as canonical 'en-US'");
    }

    @Test
    void duplicateRegisterReturnsTypedEmailAlreadyRegisteredConflict() {
        String email = "dup-" + UUID.randomUUID() + "@smart.coffee";
        httpRegister(email, "en");

        Resp r = send(HttpMethod.POST, "/api/v1/auth/register",
                json(new RegisterRequest("Second Try", email, "Str0ng!Pass", "en")), null);
        // Master §2.4 keeps a typed 409 with a stable code, without exposing
        // credential state, roles or status.
        assertEquals(409, r.status());
        assertTrue(r.body().contains("EMAIL_ALREADY_REGISTERED"), "expected typed conflict code");
        assertFalse(r.body().toLowerCase().contains("role"), "must not leak role information");
    }

    @Test
    void registerWithUnsupportedLanguageIsRejected() {
        Resp r = send(HttpMethod.POST, "/api/v1/auth/register",
                json(new RegisterRequest("French Guest", "fr-" + UUID.randomUUID() + "@smart.coffee", "Str0ng!Pass",
                        "fr")),
                null);
        assertEquals(400, r.status());
        assertTrue(r.body().contains("preferredLanguage"), "field error should name preferredLanguage");
    }

    @Test
    void putUsersMeValidatesPhoneAndPersistsValidValue() {
        String email = "me-" + UUID.randomUUID() + "@smart.coffee";
        String[] reg = httpRegister(email, "en");
        UUID userId = UUID.fromString(reg[1]);

        Resp bad = send(HttpMethod.PUT, "/api/v1/users/me", "{\"phone\":\"+1234\"}", reg[0]);
        assertEquals(400, bad.status());
        assertTrue(bad.body().contains("phone"), "field error should name phone");

        Resp ok = send(HttpMethod.PUT, "/api/v1/users/me", "{\"phone\":\"+85512345678\"}", reg[0]);
        assertEquals(200, ok.status());
        String stored = jdbc.queryForObject("select phone from identity.users where user_id = ?::uuid", String.class,
                userId.toString());
        assertEquals("+85512345678", stored);
    }

    @Test
    void logoutAllInvalidatesPreviouslyIssuedAccessToken() {
        String email = "rev-" + UUID.randomUUID() + "@smart.coffee";
        String[] reg = httpRegister(email, "en");
        String oldToken = reg[0];

        assertEquals(200, send(HttpMethod.GET, "/api/v1/users/me", null, oldToken).status());
        assertEquals(204, send(HttpMethod.POST, "/api/v1/auth/logout-all", null, oldToken).status());

        // Old token's sec_ver now lags the incremented security_version -> 401.
        assertEquals(401, send(HttpMethod.GET, "/api/v1/users/me", null, oldToken).status());

        // A freshly-minted token (post-logout-all) still works.
        var fresh = auth.login(new LoginRequest(email, "Str0ng!Pass"));
        assertEquals(200, send(HttpMethod.GET, "/api/v1/users/me", null, fresh.response().accessToken()).status());
    }
}
