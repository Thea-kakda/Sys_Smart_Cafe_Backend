package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.catalog.domain.Channel;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves the TRUSTED catalog channel for an incoming request (Slice 3B, decision
 * §2). The public catalog endpoint is shared by POS and the Customer surfaces, so
 * channel context is derived server-side from the authentication state and the
 * caller's effective RBAC permissions — NEVER from a caller-supplied
 * {@code ?channel=} parameter, which could otherwise leak POS-only products.
 *
 * <p>Resolution rules (spec §19 / decision "Existing catalog endpoint"):
 * <ul>
 *   <li><b>Anonymous</b> request &rarr; {@link Channel#CUSTOMER_WEB} (public web menu).</li>
 *   <li><b>Authenticated staff</b> (global access, or holding an operational
 *       branch/cashier permission such as {@code pos:access},
 *       {@code branch-product:read} or {@code order:read}) &rarr; {@link Channel#POS}.</li>
 *   <li><b>Authenticated customer</b> (no staff permission) &rarr;
 *       {@link Channel#CUSTOMER_WEB}.</li>
 * </ul>
 *
 * <p>{@link Channel#CUSTOMER_APP} has no distinct trusted signal in this repository
 * yet (the access token carries no client/app claim — see {@code JwtTokenService}),
 * so per the locked decision it is routed through the same safe customer projection
 * as {@link Channel#CUSTOMER_WEB}. It remains independently configurable and
 * previewable through the authenticated management surface, which accepts an
 * explicit, validated {@link Channel} and therefore bypasses this resolver.
 */
@Service
public class ChannelContextResolver {

    private final CurrentActorProvider actors;

    public ChannelContextResolver(CurrentActorProvider actors) {
        this.actors = actors;
    }

    /** Resolves the channel for the current security context. Never throws. */
    public Channel resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return Channel.CUSTOMER_WEB;
        }
        CurrentActor actor;
        try {
            actor = actors.require();
        } catch (RuntimeException unresolvable) {
            // A token that no longer maps to a live actor is treated as the public
            // customer surface rather than leaking an elevated (POS) view.
            return Channel.CUSTOMER_WEB;
        }
        return isStaff(actor) ? Channel.POS : Channel.CUSTOMER_WEB;
    }

    private boolean isStaff(CurrentActor actor) {
        if (actor.globalAccess()) {
            return true;
        }
        return actor.hasPermission(PermissionCodes.POS_ACCESS)
                || actor.hasPermission(PermissionCodes.BRANCH_PRODUCT_READ)
                || actor.hasPermission(PermissionCodes.BRANCH_PRODUCT_MANAGE)
                || actor.hasPermission(PermissionCodes.ORDER_READ);
    }

    /**
     * The TRUSTED CUSTOMER_APP surface. The shared {@code /stores/{storeId}/catalog}
     * endpoint cannot distinguish the native app from the web (the access token carries
     * no client/app claim), so CUSTOMER_APP is reached ONLY through the dedicated,
     * server-owned {@code /api/v1/app/...} route, which is hard-wired to this value.
     * It is not a caller-selectable channel: a public client cannot point this at POS,
     * DELIVERY or any other channel, and it still runs the identical
     * {@link ChannelCatalogService} projection, so CUSTOMER_APP products the operator
     * has not published stay hidden exactly as on the web surface.
     */
    public Channel resolveApp() {
        return Channel.CUSTOMER_APP;
    }
}
