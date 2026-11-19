package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 3A integration scenarios A–H (spec §23): the READ-ONLY availability engine
 * driving genuine seeded catalog / recipe / inventory / option / reconciliation state
 * against a real migrated Postgres. Each test arranges DB state, calls the SAME engine
 * entry points a future channel would, and asserts the verdict — never mutating inventory.
 */
class AvailabilityScenarioIT extends AvailabilityITBase {

    @BeforeEach
    void arrange() {
        storeId = seedStore();
    }

    // ------------------------------------------------------- Scenario A: variants

    @Test
    void variantAvailabilityIsIndependentAndProductOrderableWhenAnyVariantIsInStock() {
        UUID productId = seedProduct("LATTE_A_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANS", GRAM);
        UUID milk = seedIngredient("MILK", MILLILITER);
        UUID cup12 = seedIngredient("CUP12", PIECE);
        UUID cup16 = seedIngredient("CUP16", PIECE);
        seedBalance(storeId, beans, "1000");
        seedBalance(storeId, milk, "1000");
        seedBalance(storeId, cup12, "0");  // Regular sold out
        seedBalance(storeId, cup16, "20"); // Large has stock
        assignProductRecipe(productId, "unused product fallback", List.of(item(beans, "18", 0)));

        ProductVariantResponse regular = createVariant(productId, "REG", true);
        ProductVariantResponse large = createVariant(productId, "LARGE", false);
        assignVariantRecipe(regular, productId,
                activeRecipe(productId, "r", List.of(item(beans, "18", 0), item(milk, "180", 1), item(cup12, "1", 2))));
        assignVariantRecipe(large, productId,
                activeRecipe(productId, "l", List.of(item(beans, "18", 0), item(milk, "180", 1), item(cup16, "1", 2))));

        ProductAvailabilityResult r = availability.availabilityForProduct(storeId, productId, 1);

        assertTrue(r.orderable(), "Large is in stock, so the product is orderable");
        assertTrue(r.inStock());
        assertFalse(r.reasonCodes().contains(AvailabilityReason.PRODUCT_OUT_OF_STOCK.code()),
                "product is not entirely sold out: " + r.reasonCodes());

        VariantAvailabilityResult reg = variant(r, regular.variantId());
        VariantAvailabilityResult lg = variant(r, large.variantId());
        assertFalse(reg.orderable(), "Regular's 12oz cup has no stock");
        assertFalse(reg.inStock());
        assertTrue(reg.reasonCodes().contains(AvailabilityReason.VARIANT_OUT_OF_STOCK.code()));
        assertTrue(lg.orderable(), "Large's 16oz cup + others are covered");
        assertEquals(5, lg.availableServings(), "milk is the bottleneck (1000/180)");
    }

    // ------------------------------------------------- Scenario B: milk replacement

    @Test
    void requiredReplaceGroupKeepsProductOrderableViaAvailableOption() {
        UUID productId = seedProduct("LATTE_B_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANS", GRAM);
        UUID fresh = seedIngredient("FRESH_MILK", MILLILITER);
        UUID oat = seedIngredient("OAT_MILK", MILLILITER);
        UUID cup = seedIngredient("CUP", PIECE);
        seedBalance(storeId, beans, "1000");
        seedBalance(storeId, fresh, "0");    // Fresh milk sold out
        seedBalance(storeId, oat, "1000");
        seedBalance(storeId, cup, "1000");
        assignProductRecipe(productId, "b", List.of(item(beans, "18", 0), item(fresh, "180", 1), item(cup, "1", 2)));

        UUID milkGroup = seedGroup(productId, "MILK", "Milk", "SINGLE", 1, 1);
        UUID freshOpt = seedOption(milkGroup, "FRESH", "Fresh Milk", dec("0"), "NONE", null, null, null, null);
        UUID oatOpt = seedOption(milkGroup, "OAT", "Oat Milk", dec("0.5"), "REPLACE", fresh, oat, null, null);

        ProductAvailabilityResult r = availability.availabilityForProduct(storeId, productId, 1);

        assertTrue(r.orderable(), "the required milk group is satisfiable via Oat");
        assertTrue(group(r, milkGroup).required());
        assertTrue(group(r, milkGroup).atLeastOneValidSelection());
        assertFalse(option(r, freshOpt).available(), "Fresh Milk has no stock");
        assertTrue(option(r, oatOpt).available(), "Oat Milk replaces the empty fresh milk");
    }

    // ---------------------------------------------------- Scenario C: sugar options

    @Test
    void multiplyOptionsYieldPerOptionAvailabilityAndKeepProductOrderable() {
        UUID productId = seedProduct("LATTE_C_" + suffix());
        offerProduct(storeId, productId);
        UUID milk = seedIngredient("MILK", MILLILITER);
        UUID syrup = seedIngredient("SUGAR", MILLILITER);
        seedBalance(storeId, milk, "1000");
        seedBalance(storeId, syrup, "15"); // enough for 50% (10) but not 100% (20)
        assignProductRecipe(productId, "c", List.of(item(milk, "180", 0), item(syrup, "20", 1)));

        UUID sugarGroup = seedGroup(productId, "SUGAR", "Sugar", "SINGLE", 1, 1);
        UUID none = seedOption(sugarGroup, "S0", "0%", dec("0"), "MULTIPLY", syrup, null, null, dec("0"));
        UUID half = seedOption(sugarGroup, "S50", "50%", dec("0"), "MULTIPLY", syrup, null, null, dec("0.5"));
        UUID full = seedOption(sugarGroup, "S100", "100%", dec("0"), "MULTIPLY", syrup, null, null, dec("1.0"));
        UUID extra = seedOption(sugarGroup, "S150", "150%", dec("0"), "MULTIPLY", syrup, null, null, dec("1.5"));

        ProductAvailabilityResult r = availability.availabilityForProduct(storeId, productId, 1);

        assertTrue(option(r, none).available(), "0% needs no syrup");
        assertTrue(option(r, half).available(), "50% needs 10ml <= 15ml");
        assertFalse(option(r, full).available(), "100% needs 20ml > 15ml");
        assertFalse(option(r, extra).available(), "150% needs 30ml > 15ml");
        assertTrue(r.orderable(), "product remains orderable through an available Sugar option");
        assertTrue(group(r, sugarGroup).atLeastOneValidSelection());
    }

    // ------------------------------------------------- Scenario D: reservation impact

    @Test
    void availabilityReflectsReservedThenConsumedStock() {
        UUID productId = seedProduct("CUP_D_" + suffix());
        offerProduct(storeId, productId);
        UUID base = seedIngredient("BASE", PIECE);
        seedBalance(storeId, base, "10");
        assignProductRecipe(productId, "d", List.of(item(base, "1", 0)));

        assertEquals(10, servings(availability.availabilityForProduct(storeId, productId, 1)),
                "10 units on hand, none reserved");

        // A reservation of 3 units (as checkout would create) lowers availability to 7.
        setBalance(storeId, base, "10", "3");
        assertEquals(7, servings(availability.availabilityForProduct(storeId, productId, 1)),
                "reserved stock reduces available servings; the engine only reads it");

        // Releasing the reservation restores the full 10.
        setBalance(storeId, base, "10", "0");
        assertEquals(10, servings(availability.availabilityForProduct(storeId, productId, 1)));

        // Settlement consumption lowers on-hand stock (and its version), yielding 7.
        setBalance(storeId, base, "7", "0");
        ProductAvailabilityResult after = availability.availabilityForProduct(storeId, productId, 1);
        assertEquals(7, servings(after));
        assertTrue(after.orderable());
    }

    // --------------------------------------------------- Scenario E: manual sold out

    @Test
    void manualSoldOutIsOrderableFalseButStillListedAndInStock() {
        UUID productId = seedProduct("LATTE_E_" + suffix());
        offerProduct(storeId, productId, false); // operator marked sold out
        UUID beans = seedIngredient("BEANS", GRAM);
        seedBalance(storeId, beans, "1000");
        assignProductRecipe(productId, "e", List.of(item(beans, "18", 0)));

        ProductAvailabilityResult r = availability.availabilityForProduct(storeId, productId, 1);

        assertTrue(r.active());
        assertTrue(r.listed(), "still offered by the store");
        assertEquals(ManualAvailabilityState.MANUAL_SOLD_OUT, r.manualState());
        assertFalse(r.orderable(), "manual sold-out blocks ordering");
        assertTrue(r.inStock(), "stock is present; the block is purely manual");
        assertTrue(r.reasonCodes().contains(AvailabilityReason.PRODUCT_MANUALLY_SOLD_OUT.code()));
    }

    // ------------------------------------------------------ Scenario F: missing recipe

    @Test
    void recipeBasedProductWithoutRecipeIsUnorderableWithReasonAndNoWrites() {
        UUID productId = seedProduct("RB_F_" + suffix(), "RECIPE_BASED", true);
        offerProduct(storeId, productId);
        // No recipe assigned.

        long balancesBefore = count("select count(*) from inventory.inventory_balances");
        ProductAvailabilityResult r = availability.availabilityForProduct(storeId, productId, 1);

        assertFalse(r.orderable());
        assertTrue(variant(r, null).reasonCodes().contains(AvailabilityReason.ACTIVE_RECIPE_REQUIRED.code()),
                "missing active recipe for a RECIPE_BASED product: " + variant(r, null).reasonCodes());
        assertEquals(balancesBefore, count("select count(*) from inventory.inventory_balances"),
                "availability never writes inventory");
    }

    // ------------------------------------------ Scenario G: reconciliation critical

    @Test
    void criticalReconciliationFindingMakesConfigurationConservativelyUnavailable() {
        UUID productId = seedProduct("LATTE_G_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANS", GRAM);
        seedBalance(storeId, beans, "1000");
        assignProductRecipe(productId, "g", List.of(item(beans, "18", 0)));

        // Health gate passes with no anomaly.
        assertTrue(availability.availabilityForProduct(storeId, productId, 1).orderable());

        seedCriticalFinding(storeId, beans);

        ProductAvailabilityResult r = availability.availabilityForProduct(storeId, productId, 1);
        assertFalse(r.orderable(), "a required item under a CRITICAL finding is conservatively withheld");
        assertTrue(variant(r, null).reasonCodes().contains(AvailabilityReason.RECONCILIATION_CRITICAL.code()));
        // No internal anomaly detail is exposed as a reason (safe code only).
        assertEquals(List.of(AvailabilityReason.RECONCILIATION_CRITICAL.code()),
                variant(r, null).reasonCodes().stream().filter(x -> x.startsWith("RECON")).toList());
    }

    // ------------------------------------------------------ Scenario H: batch API

    @Test
    void batchIsDeterministicBoundedAndRepresentsMissingProducts() {
        UUID p1 = seedProduct("BATCH_1_" + suffix());
        offerProduct(storeId, p1);
        UUID ing1 = seedIngredient("X", GRAM);
        seedBalance(storeId, ing1, "1000");
        assignProductRecipe(p1, "h1", List.of(item(ing1, "18", 0)));

        UUID p2 = seedProduct("BATCH_2_" + suffix());
        offerProduct(storeId, p2);
        UUID ing2 = seedIngredient("Y", GRAM);
        seedBalance(storeId, ing2, "0");
        assignProductRecipe(p2, "h2", List.of(item(ing2, "18", 0)));

        UUID missing = UUID.randomUUID();
        List<UUID> input = List.of(p2, p1, missing, p1); // unsorted + duplicate p1

        List<ProductAvailabilityResult> results = availability.availabilityForProducts(storeId, input, 1);

        // Deterministic across calls and duplicates collapse; missing id retained as a member.
        List<UUID> actual = results.stream().map(ProductAvailabilityResult::productId).toList();
        List<UUID> again = availability.availabilityForProducts(storeId, List.of(p1, p2, missing), 1)
                .stream().map(ProductAvailabilityResult::productId).toList();
        assertEquals(again, actual, "batch order is deterministic (de-duplicated, ascending)");
        assertEquals(List.of(p1, p2, missing).stream().sorted().toList(), actual);
        assertEquals(3, results.size(), "duplicates collapse; missing is a represented member");

        ProductAvailabilityResult rm = results.stream().filter(x -> x.productId().equals(missing)).findFirst().orElseThrow();
        assertFalse(rm.orderable());
        assertTrue(rm.reasonCodes().contains(AvailabilityReason.PRODUCT_NOT_FOUND.code()));

        assertTrue(results.stream().anyMatch(x -> x.productId().equals(p1) && x.orderable()));
        assertTrue(results.stream().anyMatch(x -> x.productId().equals(p2) && !x.orderable()),
                "p2's only item has zero stock");

        // Empty batch is a cheap, safe empty result.
        assertEquals(0, availability.availabilityForProducts(storeId, List.of(), 1).size());
    }


    // ------------------------------------------------------------------ helpers

    private static VariantAvailabilityResult variant(ProductAvailabilityResult r, UUID variantId) {
        if (variantId == null) {
            // Variant-less products fold the single configuration's reasons into the product.
            return new VariantAvailabilityResult(null, null, r.active(), r.orderable(), r.inStock(),
                    r.availableBaseServings(), "PRODUCT", null, r.reasonCodes());
        }
        return r.variants().stream().filter(v -> variantId.equals(v.variantId())).findFirst()
                .orElseThrow(() -> new AssertionError("variant " + variantId + " missing from " + r.variants()));
    }

    private static com.smartcoffee.modules.availability.OptionGroupAvailabilityResult group(
            ProductAvailabilityResult r, UUID groupId) {
        return r.optionGroups().stream().filter(g -> groupId.equals(g.groupId())).findFirst()
                .orElseThrow(() -> new AssertionError("group " + groupId + " missing from " + r.optionGroups()));
    }

    private static com.smartcoffee.modules.availability.OptionAvailabilityResult option(
            ProductAvailabilityResult r, UUID optionId) {
        return r.optionGroups().stream().flatMap(g -> g.values().stream())
                .filter(o -> optionId.equals(o.optionId())).findFirst()
                .orElseThrow(() -> new AssertionError("option " + optionId + " missing"));
    }

    private static int servings(ProductAvailabilityResult r) {
        assertNotNull(r.availableBaseServings(), "expected a computable serving count");
        return r.availableBaseServings();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
