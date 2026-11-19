package com.smartcoffee.modules.recipe.domain;

/**
 * Recipe version lifecycle (Slice 2A). Evolved in place from the historical
 * DRAFT / ACTIVE / RETIRED triple: RETIRED is no longer used (V042 normalizes
 * any legacy RETIRED row to ARCHIVED).
 *
 * <ul>
 *   <li>{@code DRAFT} — editable working version.</li>
 *   <li>{@code ACTIVE} — the single live production plan per product; immutable,
 *     readable, and the checkout source.</li>
 *   <li>{@code SUPERSEDED} — a former ACTIVE version replaced by a newer ACTIVE
 *     one; immutable but still readable for history/audit.</li>
 *   <li>{@code ARCHIVED} — retired from all use; immutable and cannot be
 *     assigned to a product or variant.</li>
 * </ul>
 */
public enum RecipeStatus {
    DRAFT, ACTIVE, SUPERSEDED, ARCHIVED
}
