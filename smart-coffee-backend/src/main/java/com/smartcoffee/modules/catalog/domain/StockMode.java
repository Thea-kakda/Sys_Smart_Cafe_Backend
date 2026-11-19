package com.smartcoffee.modules.catalog.domain;

/**
 * How a product's stock is tracked. Aligns with migration V039
 * ck_products_stock_mode.
 *   MADE_TO_ORDER   — beverages etc., no finished-goods stock; ingredients
 *                     deducted on completion.
 *   RECIPE_BASED    — same as MADE_TO_ORDER but with a variant-specific recipe.
 *   FINISHED_GOOD   — bottled water, packaged merchandise; simple unit stock.
 *   BATCH_PRODUCTION— croissants, cakes; produced via a production order.
 *   NOT_TRACKED     — service charges, intangibles.
 */
public enum StockMode {
    MADE_TO_ORDER,
    RECIPE_BASED,
    FINISHED_GOOD,
    BATCH_PRODUCTION,
    NOT_TRACKED
}
