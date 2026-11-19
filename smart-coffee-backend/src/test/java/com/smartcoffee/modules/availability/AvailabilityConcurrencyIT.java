package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 3A concurrency + consistency tests (spec §25). The availability engine is a
 * READ-ONLY projector: while another committed writer moves inventory balance stock or
 * the store's manual sold-out flag, every availability read must still return a
 * transactionally coherent verdict — never a negative serving count, never an
 * {@code orderable} product that is not {@code inStock}, and a non-empty reason whenever
 * it is not orderable. Results reflect either the before- or after-committed state,
 * never a torn mixture, and the final quiesced read is exact.
 */
class AvailabilityConcurrencyIT extends AvailabilityITBase {

    private UUID productId;
    private UUID beans;

    @BeforeEach
    void seed() {
        storeId = seedStore();
        productId = seedProduct("CONC_" + suffix());
        offerProduct(storeId, productId);
        beans = seedIngredient("BEANSCONC", GRAM);
        seedBalance(storeId, beans, "180"); // 10 base servings at 18g each
        assignProductRecipe(productId, "conc", List.of(item(beans, "18", 0)));
    }

    @Test
    void concurrentReadsNeverSeeInconsistentAvailabilityWhileStockMoves() throws Exception {
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch done = new CountDownLatch(7);

        // One writer flips committed balance stock (as a reservation / consumption would).
        pool.submit(() -> {
            try {
                for (int i = 0; i < 60 && !stop.get(); i++) {
                    if (i % 2 == 0) {
                        setBalance(storeId, beans, "180", "0");   // 10 servings
                    } else {
                        setBalance(storeId, beans, "180", "72");  // 6 servings (reserved)
                    }
                }
            } catch (RuntimeException e) {
                failure.compareAndSet(null, "writer: " + e);
            } finally {
                done.countDown();
            }
        });

        // Six readers hammer the engine and assert per-result coherence.
        for (int t = 0; t < 6; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 40; i++) {
                        ProductAvailabilityResult r =
                                availability.availabilityForProduct(storeId, productId, 1);
                        checkCoherent(r);
                    }
                } catch (RuntimeException e) {
                    failure.compareAndSet(null, "reader: " + e);
                } finally {
                    done.countDown();
                }
            });
        }

        boolean finished = done.await(60, TimeUnit.SECONDS);
        stop.set(true);
        pool.shutdownNow();
        assertTrue(finished, "the concurrency exercise must finish");
        assertEquals(null, failure.get(), failure.get());
    }

    @Test
    void quiescedReadReflectsCommittedStateExactly() {
        setBalance(storeId, beans, "180", "72"); // 6 servings available
        ProductAvailabilityResult r = availability.availabilityForProduct(storeId, productId, 1);
        assertEquals(6, r.availableBaseServings(), "committed reservation is reflected exactly");
        assertTrue(r.orderable());

        setBalance(storeId, beans, "18", "0"); // 1 serving
        assertEquals(1, availability.availabilityForProduct(storeId, productId, 1).availableBaseServings());

        setBalance(storeId, beans, "0", "0"); // none
        ProductAvailabilityResult empty = availability.availabilityForProduct(storeId, productId, 1);
        assertFalse(empty.orderable());
        assertTrue(empty.reasonCodes().contains(AvailabilityReason.PRODUCT_OUT_OF_STOCK.code()));
    }

    @Test
    void manualSoldOutFlipIsCoherentAcrossConcurrentReads() throws Exception {
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(4);

        pool.submit(() -> {
            try {
                for (int i = 0; i < 50 && !stop.get(); i++) {
                    setStoreAvailable(storeId, productId, i % 2 == 0);
                }
            } catch (RuntimeException e) {
                failure.compareAndSet(null, "writer: " + e);
            } finally {
                done.countDown();
            }
        });
        for (int t = 0; t < 3; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 40; i++) {
                        ProductAvailabilityResult r =
                                availability.availabilityForProduct(storeId, productId, 1);
                        checkCoherent(r);
                    }
                } catch (RuntimeException e) {
                    failure.compareAndSet(null, "reader: " + e);
                } finally {
                    done.countDown();
                }
            });
        }

        boolean finished = done.await(60, TimeUnit.SECONDS);
        stop.set(true);
        pool.shutdownNow();
        assertTrue(finished);
        assertEquals(null, failure.get(), failure.get());
    }

    /** A result must be internally coherent regardless of the concurrent committed state. */
    private static void checkCoherent(ProductAvailabilityResult r) {
        if (r.availableBaseServings() != null) {
            assertTrue(r.availableBaseServings() >= 0, "availability is never negative: " + r.availableBaseServings());
        }
        if (r.orderable()) {
            assertTrue(r.inStock(), "orderable implies in stock");
            assertTrue(r.active() && r.listed(), "orderable implies active and listed");
            assertTrue(r.manualState() != ManualAvailabilityState.MANUAL_SOLD_OUT,
                    "orderable implies not manually sold out");
        } else {
            assertFalse(r.reasonCodes().isEmpty(), "an un-orderable verdict always carries a reason");
        }
    }
}
