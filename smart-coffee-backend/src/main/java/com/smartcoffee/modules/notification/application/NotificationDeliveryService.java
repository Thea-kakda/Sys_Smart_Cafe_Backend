package com.smartcoffee.modules.notification.application;

import com.smartcoffee.infrastructure.notification.*;
import com.smartcoffee.infrastructure.realtime.RealtimeEventPublisher;
// import com.smartcoffee.modules.notification.domain.*;
import com.smartcoffee.modules.notification.persistence.*;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryService {
    private final NotificationDeliveryRepository deliveries;
    private final RealtimeEventPublisher realtime;
    private final PushNotificationGateway push;
    private final EmailGateway email;

    public NotificationDeliveryService(NotificationDeliveryRepository d, RealtimeEventPublisher r,
            PushNotificationGateway p, EmailGateway e) {
        deliveries = d;
        realtime = r;
        push = p;
        email = e;
    }

    @Scheduled(fixedDelayString = "${app.notification.poll-ms:2000}")
    public void run() {
        for (var id : deliveries.pending(Instant.now(), PageRequest.of(0, 100)))
            deliver(id);
    }

    @Transactional
    public void deliver(java.util.UUID id) {
        var d = deliveries.lock(id).orElseThrow();
        try {
            var r = d.recipient();
            var n = r.notification();
            switch (d.channel()) {
                case WEBSOCKET -> {
                    realtime.toUser(r.userId().toString(), "/queue/notifications",
                            java.util.Map.of("notificationId", n.id(), "type", n.type()));
                    d.delivered("websocket", null);
                }
                case PUSH -> {
                    var x = push.send("resolved-device-token", n.messageKey(), n.type());
                    if (x.delivered())
                        d.delivered("push", x.providerReference());
                    else
                        d.failed(x.errorCode());
                }
                case EMAIL -> {
                    var x = email.send("resolved-email", n.messageKey(), n.templateArguments());
                    if (x.delivered())
                        d.delivered("email", x.providerReference());
                    else
                        d.failed(x.errorCode());
                }
                default -> d.delivered("in-app", null);
            }
        } catch (RuntimeException ex) {
            d.failed("DELIVERY_FAILED");
        }
    }
}

