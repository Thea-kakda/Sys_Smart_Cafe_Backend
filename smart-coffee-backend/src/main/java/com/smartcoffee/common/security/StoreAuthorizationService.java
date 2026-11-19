package com.smartcoffee.common.security;

import com.smartcoffee.common.exception.ForbiddenException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StoreAuthorizationService {
    public void requireStore(CurrentActor actor, UUID storeId) {
        if (!actor.canAccessStore(storeId))
            throw new ForbiddenException("STORE_ACCESS_DENIED", "Store access denied");
    }
}

