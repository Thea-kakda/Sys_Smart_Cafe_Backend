package com.smartcoffee.infrastructure.realtime;

import org.springframework.messaging.*;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

@Component
public class WebSocketPrincipalInterceptor implements ChannelInterceptor {
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return message;
    }
}

