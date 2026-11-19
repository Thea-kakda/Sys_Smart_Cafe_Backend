package com.smartcoffee.modules.organization.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.api.ManagementStoreResponse;
import com.smartcoffee.modules.organization.api.StoreStatusRequest;
import com.smartcoffee.modules.organization.api.UpdateStoreRequest;
import com.smartcoffee.modules.organization.domain.Store;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import com.smartcoffee.modules.organization.persistence.StoreRepository.StoreSearchProjection;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected branch (technical term: Store) administration. Reads include
 * inactive branches and expose the optimistic-lock version; writes validate
 * authoritatively, enforce per-branch access, apply optimistic locking, and
 * emit audit records through the existing audit module. This service is the
 * single backend authority for branch management — the frontend never recomputes
 * these rules.
 */
@Service
public class StoreManagementService {

    // Server-supported settlement/tender currencies. Only currencies the money
    // layer actually reasons about are accepted, so no hidden conversion or
    // unsupported code is ever persisted.
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "KHR");
    private static final Pattern STORE_CODE = Pattern.compile("^[A-Za-z0-9_-]{2,30}$");
    private static final Pattern LANGUAGE_TAG = Pattern.compile("^[a-zA-Z]{2,3}(-[A-Za-z0-9]{2,8})*$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9()\\-\\s]{6,30}$");

    private final StoreRepository stores;
    private final AuditWriter audit;

    public StoreManagementService(StoreRepository stores, AuditWriter audit) {
        this.stores = stores;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<ManagementStoreResponse> list(String search, Boolean active, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        // Sentinel style required by the native search query: '' means "no text
        // filter"; the active flag is split into (filter?, value?) primitives.
        String q = (search == null || search.isBlank()) ? "" : search.trim();
        boolean activeFilter = active != null;
        boolean activeValue = Boolean.TRUE.equals(active);
        return stores.search(q, activeFilter, activeValue, PageRequest.of(Math.max(page, 0), safeSize))
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ManagementStoreResponse get(UUID storeId) {
        return stores.findById(storeId).map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Branch not found"));
    }

    @Transactional
    public ManagementStoreResponse create(CurrentActor actor, CreateStoreRequest req) {
        String code = normalizeCode(req.storeCode());
        validateCode(code);
        validateProfile(req.storeName(), req.address(), req.phone(), req.timezone(), req.defaultCurrency(),
                req.defaultLanguage());

        if (stores.existsByCode(code)) {
            throw new ConflictException("STORE_CODE_TAKEN", "Branch code is already in use");
        }

        Store store = Store.create(code, req.storeName(), req.address(), blankToNull(req.phone()), req.timezone(),
                currency(req.defaultCurrency()), req.defaultLanguage(), req.activeOrDefault());
        var saved = stores.save(store);
        ManagementStoreResponse response = toResponse(saved);

        audit.success(actor.userId(), saved.id(), "BRANCH_CREATED", "ORGANIZATION", "STORE", saved.id(), null,
                response, null);
        return response;
    }

    @Transactional
    public ManagementStoreResponse update(CurrentActor actor, UUID storeId, UpdateStoreRequest req) {
        Store store = loadManageable(actor, storeId);
        requireVersion(req.version(), store.version());
        validateProfile(req.storeName(), req.address(), req.phone(), req.timezone(), req.defaultCurrency(),
                req.defaultLanguage());

        ManagementStoreResponse before = toResponse(store);
        store.updateProfile(req.storeName(), req.address(), blankToNull(req.phone()), req.timezone(),
                currency(req.defaultCurrency()), req.defaultLanguage());
        var saved = stores.saveAndFlush(store);
        ManagementStoreResponse after = toResponse(saved);

        audit.success(actor.userId(), saved.id(), "BRANCH_UPDATED", "ORGANIZATION", "STORE", saved.id(), before, after,
                null);
        return after;
    }

    @Transactional
    public ManagementStoreResponse changeStatus(CurrentActor actor, UUID storeId, StoreStatusRequest req) {
        Store store = loadManageable(actor, storeId);
        requireVersion(req.version(), store.version());

        ManagementStoreResponse before = toResponse(store);
        boolean previous = store.active();
        store.changeStatus(req.active());
        var saved = stores.saveAndFlush(store);
        ManagementStoreResponse after = toResponse(saved);

        String action = req.active() ? "BRANCH_ACTIVATED" : "BRANCH_DEACTIVATED";
        String reason = "Status " + (previous ? "active" : "inactive") + " -> "
                + (req.active() ? "active" : "inactive");
        audit.success(actor.userId(), saved.id(), action, "ORGANIZATION", "STORE", saved.id(), before, after, reason);
        return after;
    }

    // ------------------------------------------------------------
    // Access + optimistic-lock guards
    // ------------------------------------------------------------

    private Store loadManageable(CurrentActor actor, UUID storeId) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Branch not found"));
        // Global administrators pass canAccessStore for every branch; store-scoped
        // managers only reach branches they are assigned to.
        if (!actor.canAccessStore(storeId)) {
            throw new ForbiddenException("STORE_ACCESS_DENIED", "No access to this branch");
        }
        return store;
    }

    private void requireVersion(long expected, long current) {
        if (expected != current) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This branch was updated by another user. Refresh before editing again.");
        }
    }

    // ------------------------------------------------------------
    // Authoritative validation
    // ------------------------------------------------------------

    private String normalizeCode(String raw) {
        return raw == null ? null : raw.trim();
    }

    private void validateCode(String code) {
        if (code == null || !STORE_CODE.matcher(code).matches()) {
            throw new ValidationException("INVALID_STORE_CODE",
                    "Branch code must be 2-30 letters, digits, hyphen or underscore");
        }
    }

    private void validateProfile(String name, String address, String phone, String timezone, String currency,
            String language) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("INVALID_STORE_NAME", "Branch name is required");
        }
        if (address == null || address.isBlank()) {
            throw new ValidationException("INVALID_ADDRESS", "Address is required");
        }
        validateTimezone(timezone);
        validateCurrency(currency);
        validateLanguage(language);
        validatePhone(phone);
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException | IllegalArgumentException ex) {
            throw new ValidationException("INVALID_TIMEZONE", "Timezone must be a valid IANA identifier");
        }
    }

    private void validateCurrency(String value) {
        if (!SUPPORTED_CURRENCIES.contains(currency(value))) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", "Currency is not supported");
        }
    }

    private void validateLanguage(String language) {
        if (language == null || !LANGUAGE_TAG.matcher(language).matches()) {
            throw new ValidationException("UNSUPPORTED_LANGUAGE", "Language must be a valid BCP-47 style tag");
        }
    }

    private void validatePhone(String phone) {
        if (phone != null && !phone.isBlank() && !PHONE.matcher(phone.trim()).matches()) {
            throw new ValidationException("INVALID_PHONE", "Phone format is not valid");
        }
    }

    // ------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------

    private String currency(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private ManagementStoreResponse toResponse(Store s) {
        return new ManagementStoreResponse(s.id(), s.code(), s.name(), s.address(), s.phone(), s.timezone(),
                s.defaultCurrency(), s.defaultLanguage(), s.active(), s.createdAt(), s.updatedAt(), s.version());
    }

    private ManagementStoreResponse toResponse(StoreSearchProjection p) {
        return new ManagementStoreResponse(p.getStoreId(), p.getStoreCode(), p.getStoreName(), p.getAddress(),
                p.getPhone(), p.getTimezone(), p.getDefaultCurrency(), p.getDefaultLanguage(), p.isActive(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getVersion());
    }
}
