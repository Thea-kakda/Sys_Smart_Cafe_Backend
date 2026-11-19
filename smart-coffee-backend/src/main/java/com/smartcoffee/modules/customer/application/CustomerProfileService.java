package com.smartcoffee.modules.customer.application;

import com.smartcoffee.common.exception.InfrastructureException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.customer.api.*;
import com.smartcoffee.modules.customer.domain.CustomerProfile;
import com.smartcoffee.modules.customer.persistence.CustomerProfileRepository;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@SuppressWarnings("null")
@Service
public class CustomerProfileService {
    private final CustomerProfileRepository profiles;
    private final StoreRepository stores;
    private final JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager em;
    public CustomerProfileService(CustomerProfileRepository profiles, StoreRepository stores, JdbcTemplate jdbc) {
        this.profiles = profiles; this.stores = stores; this.jdbc = jdbc;
    }

    /**
     * Guarantees the acting principal owns a customer profile so the customer-centric
     * cart/checkout/order/payment machinery has a stable ownership anchor. Invoked
     * whenever an authenticated session is minted or rotated (login/register/refresh),
     * which covers staff-operated POS walk-in sales as well as customer-web signups
     * without forcing any role onto the user. The insert is idempotent and race-free
     * via ON CONFLICT (user_id) DO NOTHING; the row is never re-read as absent.
     */
    @Transactional
    public UUID ensureProfile(UUID userId) {
        // Flush pending JPA writes (a just-registered user row) so the raw JDBC
        // insert below can satisfy the customer_profiles.user_id foreign key.
        em.flush();
        jdbc.update("insert into customer.customer_profiles "
                + "(customer_id, user_id, marketing_opt_in, created_at, updated_at, version) "
                + "values (?::uuid, ?::uuid, false, now(), now(), 0) on conflict (user_id) do nothing",
                UUID.randomUUID(), userId);
        return profiles.findByUserId(userId).map(CustomerProfile::id).orElseThrow(
                () -> new InfrastructureException("CUSTOMER_PROFILE_PROVISION_FAILED", "Could not provision customer profile"));
    }

    @Transactional(readOnly = true)
    public CustomerProfileResponse getByUser(UUID userId) {
        return profiles.findByUserId(userId).map(this::response)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile not found"));
    }

    @Transactional
    public CustomerProfileResponse update(UUID userId, UpdateCustomerProfileRequest request) {
        CustomerProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile not found"));
        if (request.preferredStoreId() != null && stores.findByIdAndActiveTrue(request.preferredStoreId()).isEmpty())
            throw new ResourceNotFoundException("Preferred store not found");
        profile.update(request.preferredStoreId(), request.birthDate(), request.marketingOptIn());
        return response(profile);
    }

    private CustomerProfileResponse response(CustomerProfile p) {
        return new CustomerProfileResponse(p.id(), p.userId(), p.preferredStoreId(), p.loyaltyNumber(), p.birthDate(), p.marketingOptIn());
    }
}

