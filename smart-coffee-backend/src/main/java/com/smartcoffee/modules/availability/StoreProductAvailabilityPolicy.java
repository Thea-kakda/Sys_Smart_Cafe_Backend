package com.smartcoffee.modules.availability;

import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.StoreProduct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Product / Store master + policy rules (Slice 3A, spec §3/§12/§15). This is the ONLY
 * place the engine interprets the StoreProduct availability controls, and it maps the
 * EXISTING model (a boolean {@code available} flag plus an optional {@code sold_out_until}
 * window) onto the controlled {@link ManualAvailabilityState} without adding a column.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code listed} — the product is offered by the store (a StoreProduct row exists
 *       with {@code available == true}). A missing row is resolved upstream to
 *       PRODUCT_NOT_LISTED; here a disabled row is the manual sold-out lever.</li>
 *   <li>{@code active} — the master {@link Product} is enabled.</li>
 *   <li>No oversell override is introduced: {@code available == true} means "enabled for
 *       calculation" (AUTO), never "sellable with zero stock" (§12).</li>
 *   <li>A future {@code sold_out_until} is a temporary manual sold-out; once it passes
 *       (per the injected {@link Clock}) the item reverts to AUTO.</li>
 * </ul>
 *
 * <p>Read-only and pure over the supplied entities and clock instant.
 */
@Service
public class StoreProductAvailabilityPolicy {

    /** The store/master gate for one offered product (never the stock verdict). */
    public record PolicyGate(boolean active, boolean listed, ManualAvailabilityState manualState,
            List<String> reasonCodes) {
        public boolean orderableIgnoringStock() {
            return active && listed && !manualState.soldOut();
        }
    }

    public PolicyGate gate(Product product, StoreProduct storeProduct) {
        List<String> reasons = new ArrayList<>();

        boolean active = product != null && product.active();
        if (!active) {
            reasons.add(AvailabilityReason.PRODUCT_INACTIVE.code());
        }

        boolean listed = storeProduct != null;
        if (!listed) {
            reasons.add(AvailabilityReason.PRODUCT_NOT_LISTED.code());
        }

        // StoreProduct.available() is the EFFECTIVE boolean: it is false both when the
        // operator cleared the standing lever and while a sold_out_until window is open
        // (§12). Both causes map to MANUAL_SOLD_OUT — the engine exposes no distinct
        // "temporary vs standing" flag because the ordering outcome is identical.
        ManualAvailabilityState manual = storeProduct != null && !storeProduct.available()
                ? ManualAvailabilityState.MANUAL_SOLD_OUT
                : ManualAvailabilityState.AUTO;
        if (manual == ManualAvailabilityState.MANUAL_SOLD_OUT) {
            reasons.add(AvailabilityReason.PRODUCT_MANUALLY_SOLD_OUT.code());
        }

        return new PolicyGate(active, listed, manual, reasons);
    }
}
