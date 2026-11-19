package com.smartcoffee.common.security;

import java.util.Set;
import java.util.UUID;

public record CurrentActor(UUID userId, String actorType, Set<String> permissions, Set<UUID> storeIds,
        boolean globalAccess) {
    public boolean hasPermission(String p) {
        return permissions.contains(p);
    }

    public boolean canAccessStore(UUID s) {
        return globalAccess || storeIds.contains(s);
    }
}

