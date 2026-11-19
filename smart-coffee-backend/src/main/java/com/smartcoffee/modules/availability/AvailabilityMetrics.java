package com.smartcoffee.modules.availability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for the availability engine (Slice 3A, spec §27). Uses the
 * existing Micrometer conventions. Deliberately LOW CARDINALITY: no product id, variant
 * id, store id or ingredient id is ever used as a tag — only fixed meter names and a
 * couple of bounded outcome/source tags.
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code availability.calculation} — timer, one per product availability call
 *     (tag {@code source}: single|batch).</li>
 *   <li>{@code availability.calculation.failed} — counter, safe-evaluation failures.</li>
 *   <li>{@code availability.product.orderable} / {@code availability.product.unavailable}
 *     — orderability outcome counters.</li>
 *   <li>{@code availability.complexity_limit} — counter, interacting-option searches that
 *     exceeded the bound (a signal to review product option topology).</li>
 *   <li>{@code availability.batch.size} — distribution summary of products per batch call.</li>
 * </ul>
 */
@Component
public class AvailabilityMetrics {

    private final MeterRegistry registry;
    private final Timer singleTimer;
    private final Timer batchTimer;

    public AvailabilityMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.singleTimer = Timer.builder("availability.calculation").tag("source", "single")
                .description("Product availability calculation latency").register(registry);
        this.batchTimer = Timer.builder("availability.calculation").tag("source", "batch")
                .description("Batch product availability calculation latency").register(registry);
    }

    public void recordSingleDuration(long nanos) {
        singleTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordBatchDuration(long nanos, int size) {
        batchTimer.record(nanos, TimeUnit.NANOSECONDS);
        registry.summary("availability.batch.size").record(size);
    }

    public void recordFailed() {
        registry.counter("availability.calculation.failed").increment();
    }

    public void recordOrderable(boolean orderable) {
        registry.counter(orderable ? "availability.product.orderable" : "availability.product.unavailable")
                .increment();
    }

    public void recordComplexityLimit() {
        registry.counter("availability.complexity_limit").increment();
    }
}
