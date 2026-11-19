package com.smartcoffee.common.security;

import com.smartcoffee.common.exception.ForbiddenException;
import org.springframework.stereotype.Component;

@Component
public class PermissionEvaluator {
    public void require(CurrentActor actor, String permission) {
        if (!actor.hasPermission(permission))
            throw new ForbiddenException("Missing required permission");
    }
}

