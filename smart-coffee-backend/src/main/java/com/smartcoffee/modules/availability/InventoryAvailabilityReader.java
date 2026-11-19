package com.smartcoffee.modules.availability;

import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * READ-ONLY batch loader of a Store's inventory balances for the availability engine
 * (Slice 3A, spec §15/§16). Loads the balances for exactly the ingredients a
 * Product/Variant touches in ONE query, deterministically ordered by ingredient id,
 * and exposes per-item current / reserved / available quantities plus the balance
 * {@code version} (used only for the availability fingerprint — never returned to a
 * public client).
 *
 * <p>It performs no reservation / release / consumption and writes nothing. A
 * requested ingredient with no balance row is simply absent from the snapshot; the
 * engine treats a missing required item as zero available (never fabricated).
 */
@Service
public class InventoryAvailabilityReader {

    private final InventoryBalanceRepository balances;

    public InventoryAvailabilityReader(InventoryBalanceRepository balances) {
        this.balances = balances;
    }

    /** Immutable, per-ingredient stock view. */
    public record StockSnapshot(UUID ingredientId, BigDecimal currentStock, BigDecimal reservedStock,
            BigDecimal availableStock, long version) {
    }

    /**
     * Batch snapshot for one store across a set of ingredients. The returned map is
     * unmodifiable and keyed in ascending ingredient-id order (deterministic
     * bottleneck tie-breaking upstream).
     */
    @Transactional(readOnly = true)
    public Map<UUID, StockSnapshot> read(UUID storeId, Collection<UUID> ingredientIds) {
        if (ingredientIds == null || ingredientIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, StockSnapshot> byId = new TreeMap<>();
        for (InventoryBalance b : balances.byStoreAndIngredients(storeId, ingredientIds)) {
            UUID ingredientId = b.id().ingredientId();
            byId.put(ingredientId, new StockSnapshot(ingredientId, b.currentStock(), b.reservedStock(),
                    b.availableStock(), b.version()));
        }
        return Collections.unmodifiableMap(byId);
    }

    /** Zero-available snapshot for an ingredient the store has no balance row for. */
    public static StockSnapshot absent(UUID ingredientId) {
        return new StockSnapshot(ingredientId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L);
    }
}
