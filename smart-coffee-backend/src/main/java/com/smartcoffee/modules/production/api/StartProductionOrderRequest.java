package com.smartcoffee.modules.production.api;

/** Moves a PLANNED order to IN_PROGRESS (Slice 5A §23). No inventory movement occurs. */
public record StartProductionOrderRequest(long expectedVersion) {
}
