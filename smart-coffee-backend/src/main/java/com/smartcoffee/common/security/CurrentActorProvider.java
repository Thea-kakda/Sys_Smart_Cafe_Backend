package com.smartcoffee.common.security;

import com.smartcoffee.common.exception.UnauthorizedException;
import com.smartcoffee.modules.rbac.persistence.PermissionQueryRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentActorProvider {
    private final PermissionQueryRepository permissions;

    public CurrentActorProvider(PermissionQueryRepository permissions) {
        this.permissions = permissions;
    }

    public CurrentActor require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("Authentication is required");
        }

        UUID id = UUID.fromString(authentication.getName());
        Instant now = Instant.now();
        var stores = permissions.storeIds(id, now);
        var granted = new HashSet<String>();
        for (UUID store : stores) {
            granted.addAll(permissions.permissions(id, store, now));
        }

        boolean globalAccess = permissions.global(id, now);
        // A store-NULL assignment is a global grant. Without this merge a pure
        // global administrator (SUPER_ADMIN/ADMIN with no store-scoped row)
        // resolves storeIds to an empty set and therefore to zero permissions,
        // causing every permission-guarded endpoint to reject them with 403.
        if (globalAccess) {
            granted.addAll(permissions.globalPermissions(id, now));
        }

        return new CurrentActor(
            id,
            "USER",
            Set.copyOf(granted),
            stores,
            globalAccess
        );
    }
}


