package com.smartcoffee.modules.availability;

/**
 * The controlled manual availability a Store operator sets on a StoreProduct
 * (Slice 3A, spec §12). This is the ENGINE-FACING view of the existing
 * {@code catalog.store_products} boolean model; no new column is introduced.
 *
 * <p>Mapping from the legacy {@code StoreProduct.available} boolean +
 * {@code sold_out_until} window (documented in the delivery report, §12/§14):
 * <ul>
 *   <li>{@code available == false} → {@link #MANUAL_SOLD_OUT}: an operator has
 *       explicitly disabled ordering, regardless of stock.</li>
 *   <li>{@code available == true} and no future {@code sold_out_until} →
 *       {@link #AUTO}: availability is computed from stock + configuration.</li>
 *   <li>{@code available == true} but a future {@code sold_out_until} → treated
 *       as {@link #MANUAL_SOLD_OUT} until that instant passes (a temporary
 *       sold-out window), then reverts to AUTO.</li>
 * </ul>
 *
 * <p>{@link #MANUAL_AVAILABLE} is declared for contract completeness but the
 * current StoreProduct model has no distinct "force available despite zero
 * stock" flag, so it is never emitted. Per §12 we do NOT introduce an oversell
 * override silently: {@code available == true} means "enabled for calculation",
 * which is exactly {@link #AUTO}.
 */
public enum ManualAvailabilityState {
    AUTO,
    MANUAL_AVAILABLE,
    MANUAL_SOLD_OUT;

    public boolean soldOut() {
        return this == MANUAL_SOLD_OUT;
    }
}
