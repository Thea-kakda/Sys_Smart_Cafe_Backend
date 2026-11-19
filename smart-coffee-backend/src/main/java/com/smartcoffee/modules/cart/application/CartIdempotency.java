package com.smartcoffee.modules.cart.application;

import com.smartcoffee.common.exception.InfrastructureException;
import com.smartcoffee.infrastructure.idempotency.ApiIdempotencyRecord;
import com.smartcoffee.infrastructure.idempotency.CanonicalRequestHasher;
import com.smartcoffee.infrastructure.idempotency.IdempotencyService;
import com.smartcoffee.infrastructure.idempotency.IdempotencyStatus;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotency plumbing for cart mutations (Slice 2C §12), reusing the existing
 * {@code integration.api_idempotency_records} infrastructure (V015) exactly as
 * checkout does.
 *
 * <p>The header is <b>optional</b> so existing POS/customer-web calls keep
 * working unchanged. When a client does send {@code Idempotency-Key}, a retry
 * with the same key and the same payload replays the original response instead
 * of reserving twice, and the same key with a different payload is rejected as
 * {@code IDEMPOTENCY_KEY_REUSED} without touching state.
 */
@Component
public class CartIdempotency {

    private static final String ACTOR_TYPE = "USER";
    private static final Duration RETENTION = Duration.ofDays(1);

    private final IdempotencyService idempotency;
    private final CanonicalRequestHasher hasher;
    private final ObjectMapper json;

    public CartIdempotency(IdempotencyService idempotency, CanonicalRequestHasher hasher, ObjectMapper json) {
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.json = json;
    }

    /** Claimed idempotency identity, or null when the caller sent no key. */
    public record Claim(ApiIdempotencyRecord record) {
    }

    public Claim claim(String scope, UUID userId, UUID key, Object payload) {
        if (key == null) {
            return null;
        }
        return new Claim(idempotency.claim(scope, ACTOR_TYPE, userId, key, hasher.hash(payload), RETENTION));
    }

    /** The stored response of a completed claim, or null when this is a first execution. */
    public <T> T replay(Claim claim, Class<T> type) {
        if (claim == null || claim.record().status() != IdempotencyStatus.COMPLETED) {
            return null;
        }
        try {
            return json.readValue(claim.record().responseBody(), type);
        } catch (Exception e) {
            throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot replay response");
        }
    }

    public void complete(Claim claim, Object response) {
        if (claim == null) {
            return;
        }
        try {
            claim.record().complete(200, json.writeValueAsString(response));
        } catch (Exception e) {
            throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot store response");
        }
    }
}
