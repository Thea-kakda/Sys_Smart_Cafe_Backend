package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.CreateSupplierRequest;
import com.smartcoffee.modules.inventory.api.SupplierResponse;
import com.smartcoffee.modules.inventory.api.UpdateSupplierRequest;
import com.smartcoffee.modules.inventory.domain.Supplier;
import com.smartcoffee.modules.inventory.persistence.SupplierRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier master write path (create / update / deactivate). Guarded by
 * {@code supplier:manage}. Code uniqueness is checked up front and the
 * {@code ux}/{@code UNIQUE} constraint is the concurrency backstop (mapped to
 * {@code 409 DUPLICATE_CODE}). Updates echo the optimistic-lock {@code version}
 * ({@code 409 VERSION_CONFLICT}). Suppliers are never hard-deleted —
 * {@code active=false} retires them (A3). Store is null in audit: suppliers are a
 * global master, not a per-branch resource.
 */
@Service
public class SupplierCommandService {

    private final SupplierRepository suppliers;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;

    public SupplierCommandService(SupplierRepository suppliers, AuditWriter audit, PermissionEvaluator permissions) {
        this.suppliers = suppliers;
        this.audit = audit;
        this.permissions = permissions;
    }

    @Transactional
    public SupplierResponse create(CurrentActor actor, CreateSupplierRequest req) {
        permissions.require(actor, PermissionCodes.SUPPLIER_MANAGE);
        String code = req.code().trim();
        if (suppliers.existsByCode(code)) {
            throw new ConflictException("DUPLICATE_CODE", "Supplier code already exists: " + code);
        }
        Supplier supplier = Supplier.create(code, req.name(), req.contactName(), req.phone(), req.email(),
                req.address(), req.taxId(), req.activeOrDefault());
        try {
            suppliers.saveAndFlush(supplier);
        } catch (DataIntegrityViolationException concurrent) {
            throw new ConflictException("DUPLICATE_CODE", "Supplier code already exists: " + code);
        }
        SupplierResponse after = toResponse(supplier);
        audit.success(actor.userId(), null, "SUPPLIER_CREATED", "INVENTORY", "SUPPLIER", supplier.id(), null, after,
                "Created supplier " + supplier.code());
        return after;
    }

    @Transactional
    public SupplierResponse update(CurrentActor actor, UUID supplierId, UpdateSupplierRequest req) {
        permissions.require(actor, PermissionCodes.SUPPLIER_MANAGE);
        Supplier supplier = suppliers.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("SUPPLIER_NOT_FOUND", "Supplier not found"));
        if (!Objects.equals(req.version(), supplier.version())) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This supplier was updated by another user. Refresh before editing again.");
        }
        String code = req.code().trim();
        if (suppliers.existsByCodeAndIdNot(code, supplierId)) {
            throw new ConflictException("DUPLICATE_CODE", "Supplier code already exists: " + code);
        }
        SupplierResponse before = toResponse(supplier);
        supplier.updateDetails(code, req.name(), req.contactName(), req.phone(), req.email(), req.address(),
                req.taxId(), req.activeOrDefault());
        try {
            suppliers.saveAndFlush(supplier);
        } catch (DataIntegrityViolationException concurrent) {
            throw new ConflictException("DUPLICATE_CODE", "Supplier code already exists: " + code);
        }
        SupplierResponse after = toResponse(supplier);
        audit.success(actor.userId(), null, "SUPPLIER_UPDATED", "INVENTORY", "SUPPLIER", supplierId, before, after,
                "Updated supplier " + supplier.code());
        return after;
    }

    static SupplierResponse toResponse(Supplier s) {
        return new SupplierResponse(s.id(), s.code(), s.name(), s.contactName(), s.phone(), s.email(), s.address(),
                s.taxId(), s.active(), s.createdAt(), s.updatedAt(), s.version());
    }
}
