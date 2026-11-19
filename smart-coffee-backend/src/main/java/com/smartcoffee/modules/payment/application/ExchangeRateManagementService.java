package com.smartcoffee.modules.payment.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.payment.api.CreateExchangeRateRequest;
import com.smartcoffee.modules.payment.api.ExchangeRateResponse;
import com.smartcoffee.modules.payment.domain.Currency;
import com.smartcoffee.modules.payment.domain.CurrencyExchangeRate;
import com.smartcoffee.modules.payment.persistence.CurrencyExchangeRateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchange-rate administration (Checkpoint 10) — the only sanctioned way for an
 * operator to place a rate into the system. V019 deliberately seeds no rate, so
 * without this service the conversion engine (5B) could never resolve a cross
 * -currency pair from operational data.
 *
 * <p>Design constraints enforced here, never in the client:
 * <ul>
 *   <li>History is append-only — a "change" supersedes, it does not edit or
 *       delete. Creating a rate closes the currently-open predecessor of the
 *       same directed pair/scope at the new window's start, so the effective
 *       timeline stays continuous and non-overlapping (V019 model note).</li>
 *   <li>Rate invariants (pair differs, positive, precision(20,10), window order)
 *       are re-validated by {@link CurrencyExchangeRate#create}; the request DTO
 *       is only a first client-side guard.</li>
 *   <li>A GLOBAL rate (null store) is an org-wide decision and requires
 *       global administrators only; branch rates require access to that branch.</li>
 *   <li>Deactivation uses the optimistic-lock version, mirroring branch
 *       management, so a stale UI cannot silently retire a rate.</li>
 * </ul>
 * Listing uses sentinel parameters (see repository) because a null-typed filter
 * value breaks Postgres prepared-statement planning on this stack.
 */
@Service
public class ExchangeRateManagementService {

    private static final Set<String> SCOPES = Set.of("ALL", "GLOBAL", "BRANCH");
    private static final String DEFAULT_SOURCE = "MANUAL";

    private final CurrencyExchangeRateRepository rates;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;
    private final AuditWriter audit;

    public ExchangeRateManagementService(CurrencyExchangeRateRepository rates, PermissionEvaluator permissions,
            StoreAuthorizationService stores, AuditWriter audit) {
        this.rates = rates;
        this.permissions = permissions;
        this.stores = stores;
        this.audit = audit;
    }

    // ------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public Page<ExchangeRateResponse> list(CurrentActor actor, String base, String quote, String scope,
            UUID storeId, Boolean active, int page, int size) {
        permissions.require(actor, PermissionCodes.CURRENCY_RATE_READ);

        String baseCode = currencyFilter(base);
        String quoteCode = currencyFilter(quote);
        String scopeFilter = scopeOrDefault(scope);
        // A branch-scoped operator may only list rates for branches they access.
        if (storeId != null) {
            stores.requireStore(actor, storeId);
        }

        boolean storeAny = storeId == null;
        String storeIdText = (storeId != null) ? storeId.toString() : "";
        boolean activeFilter = active != null;
        boolean activeValue = Boolean.TRUE.equals(active);
        int safeSize = Math.min(Math.max(size, 1), 100);

        return rates.search(baseCode, quoteCode, scopeFilter, storeAny, storeIdText, activeFilter, activeValue,
                PageRequest.of(Math.max(page, 0), safeSize)).map(ExchangeRateResponse::from);
    }

    @Transactional(readOnly = true)
    public ExchangeRateResponse get(CurrentActor actor, UUID rateId) {
        permissions.require(actor, PermissionCodes.CURRENCY_RATE_READ);
        CurrencyExchangeRate rate = load(rateId);
        requireScopeAccess(actor, rate.storeId());
        return ExchangeRateResponse.from(rate);
    }

    // ------------------------------------------------------------ write

    @Transactional
    public ExchangeRateResponse create(CurrentActor actor, CreateExchangeRateRequest req) {
        permissions.require(actor, PermissionCodes.CURRENCY_RATE_MANAGE);
        requireScopeAccess(actor, req.storeId());

        Currency base = Currency.parse(req.baseCurrency());
        Currency quote = Currency.parse(req.quoteCurrency());
        Instant from = req.effectiveFrom() == null ? Instant.now() : req.effectiveFrom();
        String source = (req.source() == null || req.source().isBlank()) ? DEFAULT_SOURCE : req.source().trim();

        // Domain factory re-validates everything authoritative: distinct pair,
        // positive rate within numeric(20,10), window ordering, source present.
        CurrencyExchangeRate rate = CurrencyExchangeRate.create(req.storeId(), base, quote, req.rate(), source,
                from, req.effectiveTo(), actor.userId());

        // Supersede, never overlap: predecessors of the SAME directed pair and
        // scope that are still active when the new window opens are closed at its
        // start. A predecessor that starts at/after the new rate is a genuine
        // overlap the timeline cannot express, so it is rejected.
        List<CurrencyExchangeRate> predecessors = rates.findDirectedActive(req.storeId(), base, quote, from);
        for (CurrencyExchangeRate predecessor : predecessors) {
            if (!predecessor.effectiveFrom().isBefore(from)) {
                throw new ConflictException("RATE_PERIOD_OVERLAP",
                        "An active rate for this pair and scope already starts at or after the requested time");
            }
            predecessor.closeAt(from);
            audit.success(actor.userId(), predecessor.storeId(), "RATE_SUPERSEDED", "PAYMENT", "EXCHANGE_RATE",
                    predecessor.id(), null, ExchangeRateResponse.from(predecessor),
                    "Closed by a new " + base + "->" + quote + " rate");
        }

        CurrencyExchangeRate saved = rates.saveAndFlush(rate);
        ExchangeRateResponse response = ExchangeRateResponse.from(saved);
        audit.success(actor.userId(), saved.storeId(), "RATE_CREATED", "PAYMENT", "EXCHANGE_RATE", saved.id(), null,
                response, null);
        return response;
    }

    @Transactional
    public ExchangeRateResponse deactivate(CurrentActor actor, UUID rateId, Long expectedVersion) {
        permissions.require(actor, PermissionCodes.CURRENCY_RATE_MANAGE);
        CurrencyExchangeRate rate = load(rateId);
        requireScopeAccess(actor, rate.storeId());
        if (expectedVersion != null && expectedVersion != rate.version()) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This rate was updated by another user. Refresh before deactivating again.");
        }

        ExchangeRateResponse before = ExchangeRateResponse.from(rate);
        rate.deactivate();
        CurrencyExchangeRate saved = rates.saveAndFlush(rate);
        ExchangeRateResponse after = ExchangeRateResponse.from(saved);

        audit.success(actor.userId(), saved.storeId(), "RATE_DEACTIVATED", "PAYMENT", "EXCHANGE_RATE", saved.id(),
                before, after, null);
        return after;
    }

    // ------------------------------------------------------------ guards

    private CurrencyExchangeRate load(UUID rateId) {
        return rates.findById(rateId)
                .orElseThrow(() -> new ResourceNotFoundException("RATE_NOT_FOUND", "Exchange rate not found"));
    }

    /**
     * GLOBAL (null store) decisions belong to global administrators only; a
     * branch rate belongs to operators of that branch.
     */
    private void requireScopeAccess(CurrentActor actor, UUID storeId) {
        if (storeId == null) {
            if (!actor.globalAccess()) {
                throw new ForbiddenException("GLOBAL_RATE_ADMIN_ONLY",
                        "Global exchange rates require organization-wide access");
            }
        } else {
            stores.requireStore(actor, storeId);
        }
    }

    private String currencyFilter(String value) {
        return (value == null || value.isBlank()) ? "" : Currency.parse(value).name();
    }

    private String scopeOrDefault(String scope) {
        if (scope == null || scope.isBlank()) {
            return "ALL";
        }
        String upper = scope.trim().toUpperCase();
        if (!SCOPES.contains(upper)) {
            throw new ValidationException("INVALID_SCOPE", "scope must be ALL, GLOBAL or BRANCH");
        }
        return upper;
    }
}
