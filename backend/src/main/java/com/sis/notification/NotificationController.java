package com.sis.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    private final com.sis.academic.AccessGuard guard;

    public NotificationController(NotificationService service, com.sis.academic.AccessGuard guard) {
        this.guard=guard;
        this.service = service;
    }

    public record CreateRequest(@NotBlank String studentId, String type, @NotBlank String title,
                                @NotBlank String message) {
    }

    public record BroadcastRequest(@NotEmpty List<String> studentIds, String type, @NotBlank String title,
                                   @NotBlank String message) {
    }

    @PostMapping
    public ResponseEntity<Notification> create(@Valid @RequestBody CreateRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(r.studentId(), r.type(), r.title(), r.message()));
    }

    @PostMapping("/broadcast")
    public Map<String, Object> broadcast(@Valid @RequestBody BroadcastRequest r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", service.broadcast(r.studentIds(), r.type(), r.title(), r.message()));
        return out;
    }

    @GetMapping
    public List<Notification> list(@RequestParam String studentId,
                                   @RequestParam(defaultValue = "false") boolean unreadOnly,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        guard.selfOrAdmin(null,studentId);
        return service.list(studentId, unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unread(@RequestParam String studentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        guard.selfOrAdmin(null,studentId);
        out.put("studentId", studentId);
        out.put("unread", service.unreadCount(studentId));
        return out;
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable String id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(@RequestParam String studentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        guard.selfOrAdmin(null,studentId);
        out.put("updated", service.markAllRead(studentId));
        return out;
    }
}
