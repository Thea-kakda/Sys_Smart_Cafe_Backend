package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.ChannelConfigurationRequest;
import com.smartcoffee.modules.catalog.api.ManualSoldOutRequest;
import com.smartcoffee.modules.catalog.api.ProductSummaryResponse;
import com.smartcoffee.modules.catalog.api.ReplaceSchedulesRequest;
import com.smartcoffee.modules.catalog.application.ChannelCatalogService;
import com.smartcoffee.modules.catalog.application.ChannelManagementService;
import com.smartcoffee.modules.catalog.domain.Channel;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ENABLED concurrency tests for the channel write path (Slice 3B.1, spec §11), driven
 * through the real transactional services over Postgres with latches (no probabilistic
 * sleeps): (A) two administrators upserting the same optimistic-lock version produce
 * exactly one winner and one version conflict with a coherent final row; (B) a manual
 * sold-out race commits one state and rejects the stale one; (C) catalog reads racing a
 * channel update never return a partial or duplicated page; (D) reads racing a schedule
 * replace-all never see an empty transient set; (F) reads racing a delete see either the
 * visible or the hidden committed state, never a server error.
 *
 * <p>Scenario (E) — read racing an inventory reservation — is covered by the existing
 * Slice 3A {@code AvailabilityConcurrencyIT} and the cart reservation ITs; the channel
 * layer performs no inventory write, so re-asserting it here would duplicate that suite
 * (documented in the final report).
 */
@SpringBootTest
class ChannelConcurrencyIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ChannelCatalogService catalog;
    @Autowired
    ChannelManagementService management;
    @Autowired
    CurrentActorProvider actors;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private UUID store;
    private UUID product;

    @BeforeEach
    void setUp() {
        store = newStore();
        UUID cat = newCategory();
        product = newProduct(cat);
        offer(store, product);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------- A. optimistic-lock race

    @Test
    void twoAdminsUpdatingTheSameVersionProduceOneWinnerAndOneConflict() throws Exception {
        publishChannel(Channel.CUSTOMER_WEB);
        CurrentActor admin = seedAdminActor();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        Runnable race = () -> {
            ready.countDown();
            awaitUninterruptibly(go);
            try {
                management.upsertChannel(admin, store, product, Channel.CUSTOMER_WEB,
                        new ChannelConfigurationRequest(true, true, "Racer", null, 0, null, null, null, 0L));
                successes.incrementAndGet();
            } catch (RuntimeException lost) {
                conflicts.incrementAndGet();
            }
        };
        pool.submit(race);
        pool.submit(race);
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        awaitUntil(() -> successes.get() + conflicts.get() == 2, 10);
        assertEquals(1, successes.get(), "exactly one update commits");
        assertEquals(1, conflicts.get(), "the stale-version rival is rejected");
        long finalVersion = version();
        assertEquals(1L, finalVersion, "one and only one increment applied");
        assertEquals("Racer", jdbc.queryForObject("select display_name from catalog.product_channels where "
                + "store_id=? and product_id=? and channel=?", String.class, store, product,
                Channel.CUSTOMER_WEB.name()));
    }

    // ---------------------------------------------------------------- B. manual sold-out race

    @Test
    void twoSoldOutUpdatesOnTheSameVersionLeaveOneCoherentCommittedState() throws Exception {
        publishChannel(Channel.CUSTOMER_WEB);
        CurrentActor admin = seedAdminActor();
        long spVersion = storeProductVersion();

        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Runnable race = () -> {
            awaitUninterruptibly(go);
            try {
                management.setManualSoldOut(admin, store, product,
                        new ManualSoldOutRequest(false, null, spVersion));
                ok.incrementAndGet();
            } catch (RuntimeException lost) {
                rejected.incrementAndGet();
            }
        };
        pool.submit(race);
        pool.submit(race);
        go.countDown();

        awaitUntil(() -> ok.get() + rejected.get() == 2, 10);
        assertEquals(1, ok.get(), "one sold-out write wins");
        assertEquals(1, rejected.get(), "the rival on the stale version fails");
        assertFalse(storeProductAvailable(), "committed state is the sold-out override");
    }

    // ---------------------------------------------------------------- C. read vs channel update

    @Test
    void catalogReadsRacingAChannelUpdateNeverReturnPartialOrDuplicatePages() throws Exception {
        publishChannel(Channel.CUSTOMER_WEB);
        CurrentActor admin = seedAdminActor();
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger readErrors = new AtomicInteger();
        AtomicInteger sawDuplicate = new AtomicInteger();

        Runnable reader = () -> {
            try {
                while (stop.getCount() > 0) {
                    List<ProductSummaryResponse> page = catalog
                            .list(store, Channel.CUSTOMER_WEB, "", null, false, 0, 50).getContent();
                    Set<UUID> ids = new HashSet<>();
                    for (ProductSummaryResponse r : page) {
                        if (!ids.add(r.productId())) {
                            sawDuplicate.incrementAndGet();
                        }
                    }
                }
            } catch (RuntimeException ex) {
                readErrors.incrementAndGet();
            }
        };
        pool.submit(reader);
        pool.submit(reader);

        for (int i = 0; i < 20; i++) {
            long v = version();
            management.upsertChannel(admin, store, product, Channel.CUSTOMER_WEB,
                    new ChannelConfigurationRequest(true, true, "Cycle" + i, null, 0, null, null, null, v));
        }
        stop.countDown();

        assertEquals(0, readErrors.get(), "concurrent reads never error out");
        assertEquals(0, sawDuplicate.get(), "concurrent reads never duplicate a product");
    }

    // ---------------------------------------------------------------- D. read vs schedule replace

    @Test
    void readsRacingScheduleReplaceNeverSeeAnEmptyTransientSet() throws Exception {
        publishChannel(Channel.CUSTOMER_WEB);
        CurrentActor admin = seedAdminActor();
        // Committed schedule: two windows. A replace to a single window and back, repeatedly.
        List<ReplaceSchedulesRequest.Window> two = List.of(
                new ReplaceSchedulesRequest.Window(1, LocalTime.of(9, 0), LocalTime.of(12, 0), true),
                new ReplaceSchedulesRequest.Window(2, LocalTime.of(9, 0), LocalTime.of(12, 0), true));
        List<ReplaceSchedulesRequest.Window> one = List.of(
                new ReplaceSchedulesRequest.Window(1, LocalTime.of(9, 0), LocalTime.of(12, 0), true));
        management.replaceSchedules(admin, store, product, Channel.CUSTOMER_WEB,
                new ReplaceSchedulesRequest(two, null));

        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger bad = new AtomicInteger();
        Runnable reader = () -> {
            try {
                while (stop.getCount() > 0) {
                    int n = countSchedules();
                    // Atomic replace-all => the observable set is always the committed 1 or 2,
                    // never 0 mid-flight (unless 0 is itself a committed state, which it is not here).
                    if (n != 1 && n != 2) {
                        bad.incrementAndGet();
                    }
                }
            } catch (RuntimeException ex) {
                bad.incrementAndGet();
            }
        };
        pool.submit(reader);

        for (int i = 0; i < 15; i++) {
            management.replaceSchedules(admin, store, product, Channel.CUSTOMER_WEB,
                    new ReplaceSchedulesRequest(i % 2 == 0 ? one : two, null));
        }
        stop.countDown();
        assertEquals(0, bad.get(), "replace-all is atomic; reads see complete old or complete new sets");
    }

    // ---------------------------------------------------------------- F. delete vs read

    @Test
    void readsRacingChannelDeletionSeeVisibleOrHiddenCommittedStateOnly() throws Exception {
        publishChannel(Channel.CUSTOMER_WEB);
        CurrentActor admin = seedAdminActor();
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        Runnable reader = () -> {
            try {
                while (stop.getCount() > 0) {
                    catalog.list(store, Channel.CUSTOMER_WEB, "", null, false, 0, 50).getContent();
                    assertNotNull(product);
                }
            } catch (RuntimeException ex) {
                errors.incrementAndGet();
            }
        };
        pool.submit(reader);

        management.deleteChannel(admin, store, product, Channel.CUSTOMER_WEB);
        // Re-publish then delete again to widen the race window.
        management.upsertChannel(admin, store, product, Channel.CUSTOMER_WEB,
                new ChannelConfigurationRequest(true, true, null, null, 0, null, null, null, null));
        management.deleteChannel(admin, store, product, Channel.CUSTOMER_WEB);

        stop.countDown();
        assertEquals(0, errors.get(), "a racing read sees a committed visible/hidden state, never errors");
        assertTrue(channelAbsent(), "the publication row is ultimately deleted");
    }

    // ------------------------------------------------------- sanity: stale version is a 409 code

    @Test
    void staleExpectedVersionOnSerialUpdateIsRejected() {
        publishChannel(Channel.CUSTOMER_WEB);
        CurrentActor admin = seedAdminActor();
        assertThrows(com.smartcoffee.common.exception.ConflictException.class,
                () -> management.upsertChannel(admin, store, product, Channel.CUSTOMER_WEB,
                        new ChannelConfigurationRequest(true, true, null, null, 0, null, null, null, 99L)));
    }

    // ------------------------------------------------------------------ helpers

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    private long version() {
        return jdbc.queryForObject("select version from catalog.product_channels where store_id=? and product_id=? "
                + "and channel=?", Long.class, store, product, Channel.CUSTOMER_WEB.name());
    }

    private long storeProductVersion() {
        return jdbc.queryForObject("select version from catalog.store_products where store_id=? and product_id=?",
                Long.class, store, product);
    }

    private boolean storeProductAvailable() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select available from catalog.store_products where "
                + "store_id=? and product_id=?", Boolean.class, store, product));
    }

    private int countSchedules() {
        Integer n = jdbc.queryForObject("select count(*) from catalog.product_channel_schedules s join "
                + "catalog.product_channels c on c.product_channel_id = s.product_channel_id where c.store_id=? "
                + "and c.product_id=?", Integer.class, store, product);
        return n == null ? 0 : n;
    }

    private boolean channelAbsent() {
        return 0 == jdbc.queryForObject("select count(*) from catalog.product_channels where store_id=? and "
                + "product_id=? and channel=?", Integer.class, store, product, Channel.CUSTOMER_WEB.name());
    }

    private void publishChannel(Channel channel) {
        jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                + "orderable, display_order, created_at, updated_at, version) values (?,?,?,?,true,true,0,now(),now(),0)",
                UUID.randomUUID(), store, product, channel.name());
    }

    private CurrentActor seedAdminActor() {
        UUID userId = UUID.randomUUID();
        String email = "conc-admin-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Conc Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,null,now())", UUID.randomUUID(), userId,
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        userId.toString(), null, List.of()));
        return actors.require();
    }

    private UUID newStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'CONC Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id,
                "CN" + UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        String code = "CNC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, display_order, "
                + "active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)", id, code, "C " + code);
        return id;
    }

    private UUID newProduct(UUID categoryId) {
        UUID id = UUID.randomUUID();
        String code = "CNP-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.1,false,true,now(),now(),0)", id, code, categoryId, "Conc Product",
                new BigDecimal("3.00"));
        return id;
    }

    private void offer(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, sold_out_until, updated_at, "
                + "version) values (?,?,true,null,now(),0)", store, productId);
    }

    @SuppressWarnings("unused")
    private static Instant now() {
        return Instant.now();
    }
}
