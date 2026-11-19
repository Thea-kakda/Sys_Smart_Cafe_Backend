package com.smartcoffee.modules.payment.persistence;

import com.smartcoffee.modules.payment.domain.Currency;
import com.smartcoffee.modules.payment.domain.CurrencyExchangeRate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CurrencyExchangeRateRepository extends JpaRepository<CurrencyExchangeRate, UUID> {

    /**
     * Deterministic candidate list for a currency pair at an instant (Checkpoint
     * 5B selection). Returns branch-specific rows before global ones
     * (store-scoped precedence), then the latest effective window, then id for a
     * stable tie-break. Matches either direction so an inverse-only quote can
     * still be used and reported as inverted.
     */
    @Query("""
            select r from CurrencyExchangeRate r
            where r.active = true
              and ( (r.baseCurrency = :from and r.quoteCurrency = :to)
                 or (r.baseCurrency = :to   and r.quoteCurrency = :from) )
              and r.effectiveFrom <= :at
              and (r.effectiveTo is null or r.effectiveTo > :at)
              and (r.storeId = :storeId or r.storeId is null)
            order by case when r.storeId is null then 1 else 0 end asc,
                     r.effectiveFrom desc, r.id asc
            """)
    List<CurrencyExchangeRate> findCandidates(@Param("storeId") UUID storeId, @Param("from") Currency from,
            @Param("to") Currency to, @Param("at") Instant at);

    /**
     * Active rates currently in force for an exact directed pair and store
     * scope, used to reject overlapping new windows and to close a predecessor
     * when a branch or global rate is superseded.
     */
    @Query("""
            select r from CurrencyExchangeRate r
            where r.active = true
              and r.baseCurrency = :base and r.quoteCurrency = :quote
              and ((:storeId is null and r.storeId is null) or r.storeId = :storeId)
              and (r.effectiveTo is null or r.effectiveTo > :at)
            order by r.effectiveFrom desc
            """)
    List<CurrencyExchangeRate> findDirectedActive(@Param("storeId") UUID storeId, @Param("base") Currency base,
            @Param("quote") Currency quote, @Param("at") Instant at);

    /**
     * Administrative rate listing (Checkpoint 10). Native + sentinel-style on
     * purpose: this is Hibernate 7 over pgjdbc, where an untyped {@code null}
     * JPQL parameter is planned by Postgres as {@code bytea} and breaks
     * predicates such as {@code :x is null}. Every optional filter therefore
     * arrives as a concrete primitive (boolean flag) or an empty-string
     * sentinel, never a bare null. Filters: text currency pair ('' = any),
     * branch scope ('ALL' | 'GLOBAL' | 'BRANCH'), a specific store (NULLIF
     * keeps an empty id from casting to uuid), and an active flag split into
     * (filter?, value?). Deterministic order: newest effective window first.
     */
    @Query(value = """
            select * from payment.currency_exchange_rates r
            where (:base = '' or r.base_currency = :base)
              and (:quote = '' or r.quote_currency = :quote)
              and (:scope = 'ALL'
                   or (:scope = 'GLOBAL' and r.store_id is null)
                   or (:scope = 'BRANCH' and r.store_id is not null))
              and (:storeAny = true or r.store_id = cast(nullif(:storeId, '') as uuid))
              and (:activeFilter = false or r.active = :activeValue)
            order by r.effective_from desc, r.created_at desc, r.exchange_rate_id asc
            """, countQuery = """
            select count(*) from payment.currency_exchange_rates r
            where (:base = '' or r.base_currency = :base)
              and (:quote = '' or r.quote_currency = :quote)
              and (:scope = 'ALL'
                   or (:scope = 'GLOBAL' and r.store_id is null)
                   or (:scope = 'BRANCH' and r.store_id is not null))
              and (:storeAny = true or r.store_id = cast(nullif(:storeId, '') as uuid))
              and (:activeFilter = false or r.active = :activeValue)
            """, nativeQuery = true)
    Page<CurrencyExchangeRate> search(@Param("base") String base, @Param("quote") String quote,
            @Param("scope") String scope, @Param("storeAny") boolean storeAny, @Param("storeId") String storeId,
            @Param("activeFilter") boolean activeFilter, @Param("activeValue") boolean activeValue,
            Pageable pageable);
}
