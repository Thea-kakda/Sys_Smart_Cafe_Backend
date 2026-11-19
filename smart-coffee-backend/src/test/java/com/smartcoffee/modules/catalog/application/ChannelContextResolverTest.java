package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.catalog.domain.Channel;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link ChannelContextResolver} (Slice 3B, decision §2). The public
 * catalog is a single shared endpoint, so the channel is derived ONLY from trusted
 * server-side context (authentication state + effective RBAC), never a caller query
 * parameter. These prove anonymous and plain customers resolve to CUSTOMER_WEB, staff
 * resolve to POS, a token that no longer maps to a live actor de-escalates to the
 * public surface, and CUSTOMER_APP is never auto-resolved (it has no trusted signal
 * yet and is reached only via the same web projection).
 */
class ChannelContextResolverTest {

    private static final UUID USER = UUID.fromString("11111111-0000-0000-0000-000000000001");

    private CurrentActorProvider actors;
    private ChannelContextResolver resolver;

    @BeforeEach
    void setUp() {
        actors = mock(CurrentActorProvider.class);
        resolver = new ChannelContextResolver(actors);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void anonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    }

    private void authenticatedUser() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(USER.toString(),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private CurrentActor actor(boolean global, String... perms) {
        return new CurrentActor(USER, "USER", Set.of(perms), Set.of(), global);
    }

    @Test
    void noAuthenticationResolvesCustomerWeb() {
        assertEquals(Channel.CUSTOMER_WEB, resolver.resolve());
    }

    @Test
    void anonymousTokenResolvesCustomerWeb() {
        anonymous();
        assertEquals(Channel.CUSTOMER_WEB, resolver.resolve());
    }

    @Test
    void staffWithPosAccessResolvesPos() {
        authenticatedUser();
        when(actors.require()).thenReturn(actor(false, PermissionCodes.POS_ACCESS));
        assertEquals(Channel.POS, resolver.resolve());
    }

    @Test
    void globalAccessResolvesPos() {
        authenticatedUser();
        when(actors.require()).thenReturn(actor(true));
        assertEquals(Channel.POS, resolver.resolve());
    }

    @Test
    void branchProductReaderResolvesPos() {
        authenticatedUser();
        when(actors.require()).thenReturn(actor(false, PermissionCodes.BRANCH_PRODUCT_READ));
        assertEquals(Channel.POS, resolver.resolve());
    }

    @Test
    void plainCustomerResolvesCustomerWeb() {
        authenticatedUser();
        when(actors.require()).thenReturn(actor(false, "cart:read"));
        assertEquals(Channel.CUSTOMER_WEB, resolver.resolve());
    }

    @Test
    void unresolvableActorDeescalatesToCustomerWeb() {
        authenticatedUser();
        when(actors.require()).thenThrow(new IllegalStateException("token no longer maps to a live actor"));
        assertEquals(Channel.CUSTOMER_WEB, resolver.resolve(),
                "a stale token must never be granted an elevated POS view");
    }
}
