package com.smartcoffee.infrastructure.realtime;

import com.smartcoffee.common.security.*;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class WebSocketAuthorizationInterceptor implements ChannelInterceptor {
    private final CurrentActorProvider actors;

    public WebSocketAuthorizationInterceptor(CurrentActorProvider actors) {
        this.actors = actors;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (a == null)
            return message;
        // Bind the principal verified at handshake time (see
        // WebSocketAuthHandshakeInterceptor) onto the message-processing thread so
        // CurrentActorProvider.require() resolves the same actor it would for an
        // equivalent HTTP bearer request. Applied for every inbound frame because
        // the thread-local SecurityContext is not otherwise propagated to STOMP.
        Authentication authentication = authenticationFrom(a);
        if (authentication != null) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            if (StompCommand.CONNECT.equals(a.getCommand()) && a.getUser() == null) {
                String name = authentication.getName();
                a.setUser(new StompPrincipal(name));
            }
        }
        if (StompCommand.SUBSCRIBE.equals(a.getCommand())) {
            var actor = actors.require();
            String d = a.getDestination();
            if (d != null && d.startsWith("/topic/stores/")) {
                String store = d.substring("/topic/stores/".length()).split("/")[0];
                if (!actor.canAccessStore(UUID.fromString(store)))
                    throw new org.springframework.security.access.AccessDeniedException("Store subscription denied");
            }
            if (d != null && d.startsWith("/user/") && a.getUser() != null
                    && !a.getUser().getName().equals(actor.userId().toString()))
                throw new org.springframework.security.access.AccessDeniedException("User subscription denied");
        }
        return message;
    }

    private static Authentication authenticationFrom(StompHeaderAccessor a) {
        Map<String, Object> sessionAttributes = a.getSessionAttributes();
        if (sessionAttributes == null)
            return null;
        Object value = sessionAttributes.get(WebSocketAuthHandshakeInterceptor.AUTH_ATTRIBUTE);
        return value instanceof Authentication auth ? auth : null;
    }

    /** Minimal Principal carrying the JWT subject, as STOMP expects per session. */
    private record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
