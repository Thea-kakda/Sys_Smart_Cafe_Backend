package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.inventory.api.SupplierResponse;
import com.smartcoffee.modules.inventory.persistence.SupplierRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier reads (list active, get one, paginated search), guarded by
 * {@code supplier:manage}. Mapping mirrors {@link SupplierCommandService}.
 */
@Service
public class SupplierQueryService {

    private final SupplierRepository suppliers;
    private final PermissionEvaluator permissions;

    public SupplierQueryService(SupplierRepository suppliers, PermissionEvaluator permissions) {
        this.suppliers = suppliers;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<SupplierResponse> listActive(CurrentActor actor) {
        permissions.require(actor, PermissionCodes.SUPPLIER_MANAGE);
        return suppliers.findByActiveTrueOrderByCodeAsc().stream().map(SupplierCommandService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<SupplierResponse> search(CurrentActor actor, String query, int page, int size) {
        permissions.require(actor, PermissionCodes.SUPPLIER_MANAGE);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String q = query == null ? "" : query.trim();
        return suppliers.search(q, PageRequest.of(safePage, safeSize)).map(SupplierCommandService::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(CurrentActor actor, UUID supplierId) {
        permissions.require(actor, PermissionCodes.SUPPLIER_MANAGE);
        return suppliers.findById(supplierId).map(SupplierCommandService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("SUPPLIER_NOT_FOUND", "Supplier not found"));
    }
}
