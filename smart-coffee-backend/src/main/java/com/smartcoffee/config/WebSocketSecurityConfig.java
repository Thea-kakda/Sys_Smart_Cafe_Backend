package com.smartcoffee.config;

import com.smartcoffee.infrastructure.realtime.WebSocketAuthHandshakeInterceptor;
import com.smartcoffee.infrastructure.realtime.WebSocketAuthorizationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.*;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {
    private final WebSocketAuthorizationInterceptor auth;
    private final WebSocketAuthHandshakeInterceptor handshakeAuth;
    private final String origins;

    public WebSocketSecurityConfig(WebSocketAuthorizationInterceptor auth,
            WebSocketAuthHandshakeInterceptor handshakeAuth,
            @Value("${app.websocket.allowed-origins:http://localhost:*,http://127.0.0.1:*}") String origins) {
        this.auth = auth;
        this.handshakeAuth = handshakeAuth;
        this.origins = origins;
    }

    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(auth);
    }

    public void configureMessageBroker(MessageBrokerRegistry r) {
        r.enableSimpleBroker("/topic", "/queue");
        r.setApplicationDestinationPrefixes("/app");
        r.setUserDestinationPrefix("/user");
    }

    public void registerStompEndpoints(StompEndpointRegistry r) {
        r.addEndpoint("/ws").setAllowedOriginPatterns(origins.split(",")).addInterceptors(handshakeAuth);
    }
}
