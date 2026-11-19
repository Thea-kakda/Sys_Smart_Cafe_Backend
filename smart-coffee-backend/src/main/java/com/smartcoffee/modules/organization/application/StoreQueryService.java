package com.smartcoffee.modules.organization.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.organization.api.StoreResponse;
import com.smartcoffee.modules.organization.domain.Store;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class StoreQueryService {
    private final StoreRepository stores;

    public StoreQueryService(StoreRepository stores) { this.stores = stores; }

    @Transactional(readOnly = true)
    public Page<StoreResponse> list(String search, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return stores.findByActiveTrueAndNameContainingIgnoreCase(
                search == null ? "" : search.trim(), PageRequest.of(Math.max(page, 0), safeSize))
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public StoreResponse get(UUID storeId) {
        return stores.findByIdAndActiveTrue(storeId).map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
    }

    private StoreResponse toResponse(Store s) {
        return new StoreResponse(s.id(), s.code(), s.name(), s.address(), s.phone(),
                s.timezone(), s.defaultCurrency(), s.defaultLanguage(), true, s.active());
    }
}

