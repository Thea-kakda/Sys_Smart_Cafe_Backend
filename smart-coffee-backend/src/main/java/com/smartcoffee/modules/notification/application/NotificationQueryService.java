package com.smartcoffee.modules.notification.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.notification.api.*;
import com.smartcoffee.modules.notification.persistence.NotificationRecipientRepository;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationQueryService {
    private final NotificationRecipientRepository recipients;

    public NotificationQueryService(NotificationRecipientRepository r) {
        recipients = r;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(UUID user, int page, int size) {
        return recipients.findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(user,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))).map(r -> {
                    var n = r.notification();
                    return new NotificationResponse(r.id(), n.id(), n.type(), n.category(), n.messageKey(),
                            n.templateArguments(), n.actionUrl(), n.priority(), n.createdAt(), r.readAt());
                });
    }

    @Transactional(readOnly = true)
    public long unread(UUID user) {
        return recipients.unread(user);
    }

    @Transactional
    public void read(UUID user, UUID id) {
        var r = recipients.findByIdAndUserId(id, user)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        r.read();
    }

    @Transactional
    public void readAll(UUID user) {
        recipients.markAllRead(user);
    }
}

