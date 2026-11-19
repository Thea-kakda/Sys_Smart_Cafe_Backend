package com.smartcoffee.modules.catalog.api;
import java.util.List;
import java.util.UUID;
public record CustomizationGroupResponse(UUID groupId,String code,String name,String selectionType,boolean required,int minimumSelections,Integer maximumSelections,List<CustomizationOptionResponse> options,
        /* Slice 4B additive: per-product default Option id (null = no declared default; POS/Customer must never infer one). */
        UUID defaultOptionId) {}

