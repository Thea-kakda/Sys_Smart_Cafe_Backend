package com.smartcoffee.modules.identity.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.identity.api.AccessContextResponse;
import com.smartcoffee.modules.identity.persistence.UserRepository;
import com.smartcoffee.modules.organization.domain.Store;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import com.smartcoffee.modules.rbac.persistence.PermissionQueryRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the authenticated user's effective access context. Roles and
 * permission codes are resolved by the same store-scope-aware RBAC queries used
 * across the platform (revoked/expired assignments excluded), and accessible
 * branches are derived server-side — never from a client-supplied storeId.
 */
@Service
public class AccessContextQueryService {

    private final UserRepository users;
    private final PermissionQueryRepository rbac;
    private final StoreRepository stores;

    public AccessContextQueryService(UserRepository users, PermissionQueryRepository rbac, StoreRepository stores) {
        this.users = users;
        this.rbac = rbac;
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public AccessContextResponse build(CurrentActor actor) {
        var user = users.findById(actor.userId())
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));

        var now = Instant.now();
        var roles = rbac.activeRoles(actor.userId(), now);

        List<AccessContextResponse.BranchAccess> branches;
        if (actor.globalAccess()) {
            branches = stores.findByActiveTrueOrderByCreatedAtAsc().stream().map(this::toBranch).toList();
        } else {
            branches = stores.findAllById(actor.storeIds()).stream().map(this::toBranch).toList();
        }

        return new AccessContextResponse(
                user.id(),
                user.fullName(),
                user.email(),
                user.preferredLanguage(),
                user.status().name(),
                roles,
                actor.permissions(),
                actor.globalAccess(),
                branches);
    }

    private AccessContextResponse.BranchAccess toBranch(Store s) {
        return new AccessContextResponse.BranchAccess(
                s.id(), s.code(), s.name(), s.timezone(), s.defaultCurrency(), s.defaultLanguage(), s.active());
    }
}
