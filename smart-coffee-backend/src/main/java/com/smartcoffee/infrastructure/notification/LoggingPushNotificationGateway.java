package com.smartcoffee.infrastructure.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public class LoggingPushNotificationGateway
        implements PushNotificationGateway {

    private static final Logger log =
            LoggerFactory.getLogger(
                    LoggingPushNotificationGateway.class
            );

    @Override
    public DeliveryResult send(
            String protectedToken,
            String title,
            String body
    ) {
        log.info(
                "Development push notification: token={}, title={}",
                maskToken(protectedToken),
                title
        );

        return new DeliveryResult(
                true,
                "logging-push",
                null
        );
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "[empty]";
        }

        if (token.length() <= 8) {
            return "********";
        }

        return token.substring(0, 4)
                + "..."
                + token.substring(token.length() - 4);
    }
}