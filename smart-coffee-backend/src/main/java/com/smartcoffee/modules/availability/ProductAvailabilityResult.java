package com.smartcoffee.modules.availability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The authoritative, READ-ONLY availability verdict for one Product at one Store
 * (Slice 3A, spec §4). Immutable. This is the single object every future channel
 * (Dashboard preview, POS, Customer Menu, QR, Delivery) will read; it is NOT a
 * collapsed boolean.
 *
 * <p>The distinct concepts are kept separate (spec §3):
 * <ul>
 *   <li>{@code active} — master data enabled ({@code Product.active}).</li>
 *   <li>{@code listed} — offered by this Store (a StoreProduct row exists and is
 *       not disabled).</li>
 *   <li>{@code manualState} — the Store operator's AUTO / MANUAL_SOLD_OUT control
 *       (§12), kept out of {@code active}/{@code listed}.</li>
 *   <li>{@code inStock} — the required inventory exists for the requested quantity
 *       for at least one valid configuration.</li>
 *   <li>{@code orderable} — may actually be ordered now: active AND listed AND not
 *       manually sold out AND in stock AND a valid configuration exists.</li>
 * </ul>
 *
 * <p>{@code availableBaseServings} is the base/default serving estimate (spec §7)
 * or {@code null} when the product is intentionally not inventory-tracked. It is
 * deliberately SEPARATE from {@code orderable}: a product can be orderable through
 * a non-default option (e.g. Sugar 0%) even when its 100% default has no stock.
 *
 * <p>{@code availabilityFingerprint} is an opaque, stable digest of the inputs that
 * produced this verdict (store, recipe version ids, manual state, and the affected
 * inventory balance versions). It lets a caller detect that a re-read reflects
 * different inputs; it is not a stock quantity and exposes no cost/supplier detail.
 */
public record ProductAvailabilityResult(
        UUID productId,
        UUID storeId,
        boolean active,
        boolean listed,
        boolean orderable,
        boolean inStock,
        ManualAvailabilityState manualState,
        Integer availableBaseServings,
        List<String> reasonCodes,
        List<VariantAvailabilityResult> variants,
        List<OptionGroupAvailabilityResult> optionGroups,
        Instant calculatedAt,
        String availabilityFingerprint) {

    public ProductAvailabilityResult {
        reasonCodes = List.copyOf(reasonCodes);
        variants = List.copyOf(variants);
        optionGroups = List.copyOf(optionGroups);
    }
}
