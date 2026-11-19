package com.smartcoffee.config;

import com.smartcoffee.infrastructure.realtime.WebSocketAuthHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthHandshakeInterceptor handshakeAuth;
    private final String origins;

    public WebSocketConfig(WebSocketAuthHandshakeInterceptor handshakeAuth,
            @Value("${app.websocket.allowed-origins:http://localhost:*,http://127.0.0.1:*}") String origins) {
        this.handshakeAuth = handshakeAuth;
        this.origins = origins;
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
