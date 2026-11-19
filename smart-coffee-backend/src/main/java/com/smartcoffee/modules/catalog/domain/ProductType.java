package com.smartcoffee.modules.catalog.domain;

/**
 * Product classification used by the Dashboard, POS and Customer menu to decide
 * which editor templates and behaviour apply to a product. Values align with
 * migration V039 ck_products_product_type.
 *
 * Adding a category-specific behaviour (e.g. cake message) must NOT add a new
 * value here — the spec's rule is "no category-specific columns on products";
 * behaviour is driven by attached option groups instead.
 */
public enum ProductType {
    STANDARD,
    BEVERAGE,
    BAKERY,
    CAKE,
    FOOD,
    COMBO,
    SERVICE
}
