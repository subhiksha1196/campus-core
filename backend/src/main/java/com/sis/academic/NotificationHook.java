package com.sis.academic;

import java.util.List;

import com.sis.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Sends Person 4's notifications for academic events. Best effort: a failure never fails the main action. */
@Component
public class NotificationHook {
    private static final Logger log = LoggerFactory.getLogger(NotificationHook.class);

    private final NotificationService notifications;
    private final boolean enabled;

    public NotificationHook(NotificationService notifications,
                            @Value("${sis.academic.notifications:true}") boolean enabled) {
        this.notifications = notifications;
        this.enabled = enabled;
    }

    public void one(String studentId, String type, String title, String message) {
        if (!enabled) {
            return;
        }
        try {
            notifications.create(studentId, type, title, message);
        } catch (RuntimeException e) {
            log.warn("Notification to {} not sent: {}", studentId, e.getMessage());
        }
    }

    public void many(List<String> studentIds, String type, String title, String message) {
        if (!enabled || studentIds.isEmpty()) {
            return;
        }
        try {
            notifications.broadcast(studentIds, type, title, message);
        } catch (RuntimeException e) {
            log.warn("Notification broadcast not sent: {}", e.getMessage());
        }
    }
}
