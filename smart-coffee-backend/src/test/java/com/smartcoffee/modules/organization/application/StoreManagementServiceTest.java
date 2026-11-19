package com.smartcoffee.modules.organization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.api.ManagementStoreResponse;
import com.smartcoffee.modules.organization.api.StoreStatusRequest;
import com.smartcoffee.modules.organization.api.UpdateStoreRequest;
import com.smartcoffee.modules.organization.domain.Store;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoreManagementServiceTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private final StoreManagementService service = new StoreManagementService(stores, audit);

    private final UUID admin = UUID.randomUUID();
    private final CurrentActor globalActor = new CurrentActor(admin, "USER", Set.of("branch:manage", "branch:read"),
            Set.of(), true);

    private CreateStoreRequest validCreate() {
        return new CreateStoreRequest("BKK2", "Smart Coffee BKK2", "Phnom Penh", "+855 12 345 678", "Asia/Phnom_Penh",
                "USD", "km-KH", null);
    }

    @Test
    void createPersistsAndAuditsBranch() {
        when(stores.existsByCode("BKK2")).thenReturn(false);
        when(stores.save(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));

        ManagementStoreResponse response = service.create(globalActor, validCreate());

        assertEquals("BKK2", response.storeCode());
        assertTrue(response.active());
        assertEquals(0L, response.version());
        verify(stores).save(any(Store.class));
        verify(audit).success(eq(admin), any(), eq("BRANCH_CREATED"), eq("ORGANIZATION"), eq("STORE"), any(), isNull(),
                any(), isNull());
    }

    @Test
    void createRejectsDuplicateCode() {
        when(stores.existsByCode("BKK2")).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () -> service.create(globalActor, validCreate()));
        assertEquals("STORE_CODE_TAKEN", ex.code());
    }

    @Test
    void createRejectsInvalidTimezone() {
        when(stores.existsByCode("BKK2")).thenReturn(false);
        CreateStoreRequest req = new CreateStoreRequest("BKK2", "Name", "Addr", null, "Not/AZone", "USD", "km-KH", null);

        ConflictOr("INVALID_TIMEZONE", () -> service.create(globalActor, req));
    }

    @Test
    void createRejectsUnsupportedCurrency() {
        when(stores.existsByCode("BKK2")).thenReturn(false);
        CreateStoreRequest req = new CreateStoreRequest("BKK2", "Name", "Addr", null, "Asia/Phnom_Penh", "EUR",
                "km-KH", null);

        ConflictOr("UNSUPPORTED_CURRENCY", () -> service.create(globalActor, req));
    }

    @Test
    void updateRejectsStaleVersion() {
        UUID id = UUID.randomUUID();
        Store existing = Store.create("BKK2", "Name", "Addr", null, "Asia/Phnom_Penh", "USD", "km-KH", true);
        when(stores.findById(id)).thenReturn(Optional.of(existing));
        UpdateStoreRequest req = new UpdateStoreRequest("New Name", "New Addr", null, "Asia/Phnom_Penh", "USD",
                "km-KH", 99L);

        ConflictException ex = assertThrows(ConflictException.class, () -> service.update(globalActor, id, req));
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    @Test
    void updateRejectsWhenActorCannotAccessBranch() {
        UUID id = UUID.randomUUID();
        CurrentActor scoped = new CurrentActor(admin, "USER", Set.of("branch:manage"), Set.of(), false);
        Store existing = Store.create("BKK2", "Name", "Addr", null, "Asia/Phnom_Penh", "USD", "km-KH", true);
        when(stores.findById(id)).thenReturn(Optional.of(existing));
        UpdateStoreRequest req = new UpdateStoreRequest("New", "Addr", null, "Asia/Phnom_Penh", "USD", "km-KH", 0L);

        assertThrows(ForbiddenException.class, () -> service.update(scoped, id, req));
    }

    @Test
    void changeStatusAuditsDeactivation() {
        Store existing = Store.create("BKK2", "Name", "Addr", null, "Asia/Phnom_Penh", "USD", "km-KH", true);
        UUID realId = existing.id();
        when(stores.findById(realId)).thenReturn(Optional.of(existing));
        when(stores.saveAndFlush(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));

        ManagementStoreResponse response = service.changeStatus(globalActor, realId, new StoreStatusRequest(false, 0L));

        assertFalse(response.active());
        verify(audit).success(eq(admin), eq(realId), eq("BRANCH_DEACTIVATED"), eq("ORGANIZATION"), eq("STORE"),
                eq(realId), any(), any(), any());
    }

    private void ConflictOr(String expectedCode, Runnable action) {
        ValidationException ex = assertThrows(ValidationException.class, action::run);
        assertEquals(expectedCode, ex.code());
    }
}
