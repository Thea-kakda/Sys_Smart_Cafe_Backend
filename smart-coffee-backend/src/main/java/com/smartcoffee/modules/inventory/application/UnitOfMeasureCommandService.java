package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.CreateUnitRequest;
import com.smartcoffee.modules.inventory.api.UnitManagementResponse;
import com.smartcoffee.modules.inventory.api.UpdateUnitRequest;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.domain.UnitType;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit-of-measure master write path (create / update / deactivate) — the second
 * half of the Dashboard's documented inventory API gap (M2). Guarded by
 * {@code uom:manage}. Code uniqueness is checked up front with the UNIQUE
 * constraint as the concurrency backstop ({@code 409 DUPLICATE_CODE}); scale /
 * conversion-factor validity is enforced in {@link UnitOfMeasure}; updates echo
 * the optimistic-lock {@code version} ({@code 409 VERSION_CONFLICT}). Units are
 * never hard-deleted — {@code active=false} retires them (A3). Global master, so
 * the audit store is null.
 */
@Service
public class UnitOfMeasureCommandService {

    private final UnitOfMeasureRepository units;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;

    public UnitOfMeasureCommandService(UnitOfMeasureRepository units, AuditWriter audit,
            PermissionEvaluator permissions) {
        this.units = units;
        this.audit = audit;
        this.permissions = permissions;
    }

    @Transactional
    public UnitManagementResponse create(CurrentActor actor, CreateUnitRequest req) {
        permissions.require(actor, PermissionCodes.UOM_MANAGE);
        String code = req.code().trim();
        if (units.existsByCode(code)) {
            throw new ConflictException("DUPLICATE_CODE", "Unit code already exists: " + code);
        }
        UnitOfMeasure unit = UnitOfMeasure.create(code, req.name(), UnitType.valueOf(req.type()),
                req.decimalScale(), req.factor(), req.baseUnitOrFalse(), req.activeOrDefault());
        try {
            units.saveAndFlush(unit);
        } catch (DataIntegrityViolationException concurrent) {
            throw new ConflictException("DUPLICATE_CODE", "Unit code already exists: " + code);
        }
        UnitManagementResponse after = toResponse(unit);
        audit.success(actor.userId(), null, "UOM_CREATED", "INVENTORY", "UNIT_OF_MEASURE", unit.id(), null, after,
                "Created unit of measure " + unit.code());
        return after;
    }

    @Transactional
    public UnitManagementResponse update(CurrentActor actor, UUID unitId, UpdateUnitRequest req) {
        permissions.require(actor, PermissionCodes.UOM_MANAGE);
        UnitOfMeasure unit = units.findById(unitId)
                .orElseThrow(() -> new ResourceNotFoundException("UNIT_NOT_FOUND", "Unit of measure not found"));
        if (!Objects.equals(req.version(), unit.version())) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This unit was updated by another user. Refresh before editing again.");
        }
        String code = req.code().trim();
        if (units.existsByCodeAndIdNot(code, unitId)) {
            throw new ConflictException("DUPLICATE_CODE", "Unit code already exists: " + code);
        }
        UnitManagementResponse before = toResponse(unit);
        unit.update(code, req.name(), UnitType.valueOf(req.type()), req.decimalScale(), req.factor(),
                req.baseUnitOrFalse(), req.activeOrDefault());
        try {
            units.saveAndFlush(unit);
        } catch (DataIntegrityViolationException concurrent) {
            throw new ConflictException("DUPLICATE_CODE", "Unit code already exists: " + code);
        }
        UnitManagementResponse after = toResponse(unit);
        audit.success(actor.userId(), null, "UOM_UPDATED", "INVENTORY", "UNIT_OF_MEASURE", unitId, before, after,
                "Updated unit of measure " + unit.code());
        return after;
    }

    public static UnitManagementResponse toResponse(UnitOfMeasure u) {
        return new UnitManagementResponse(u.id(), u.code(), u.name(), u.type().name(), u.decimalScale(), u.factor(),
                u.baseUnit(), u.active(), u.createdAt(), u.updatedAt(), u.version());
    }
}
