package com.smartcoffee.common.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.modules.rbac.persistence.PermissionQueryRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentActorProviderTest {

    private final PermissionQueryRepository rbac = mock(PermissionQueryRepository.class);
    private final CurrentActorProvider provider = new CurrentActorProvider(rbac);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
    }

    @Test
    void pureGlobalAdministratorResolvesGlobalPermissions() {
        UUID user = UUID.randomUUID();
        authenticate(user);
        // The defect this guards against: a global admin with NO store-scoped row
        // previously resolved to zero permissions and was rejected with 403.
        when(rbac.storeIds(eq(user), any(Instant.class))).thenReturn(Set.of());
        when(rbac.global(eq(user), any(Instant.class))).thenReturn(true);
        when(rbac.globalPermissions(eq(user), any(Instant.class)))
                .thenReturn(Set.of(PermissionCodes.BRANCH_MANAGE, PermissionCodes.BRANCH_READ));

        CurrentActor actor = provider.require();

        assertTrue(actor.globalAccess());
        assertTrue(actor.hasPermission(PermissionCodes.BRANCH_MANAGE));
        assertTrue(actor.hasPermission(PermissionCodes.BRANCH_READ));
        assertTrue(actor.canAccessStore(UUID.randomUUID()));
    }

    @Test
    void storeScopedActorPermissionsComeFromItsBranchOnly() {
        UUID user = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        authenticate(user);
        when(rbac.storeIds(eq(user), any(Instant.class))).thenReturn(Set.of(store));
        when(rbac.permissions(eq(user), eq(store), any(Instant.class))).thenReturn(Set.of("pos:access"));
        when(rbac.global(eq(user), any(Instant.class))).thenReturn(false);

        CurrentActor actor = provider.require();

        assertFalse(actor.globalAccess());
        assertTrue(actor.hasPermission("pos:access"));
        assertFalse(actor.hasPermission(PermissionCodes.BRANCH_MANAGE));
        assertTrue(actor.canAccessStore(store));
        assertFalse(actor.canAccessStore(UUID.randomUUID()));
    }

    @Test
    void globalPermissionsAreNotMergedForNonGlobalActor() {
        UUID user = UUID.randomUUID();
        authenticate(user);
        when(rbac.storeIds(eq(user), any(Instant.class))).thenReturn(Set.of());
        when(rbac.global(eq(user), any(Instant.class))).thenReturn(false);

        CurrentActor actor = provider.require();

        assertFalse(actor.globalAccess());
        assertTrue(actor.permissions().isEmpty());
    }
}
