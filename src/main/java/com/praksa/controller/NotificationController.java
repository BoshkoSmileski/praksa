package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.notification.NotificationResponse;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // GET /api/notifications/my
    // Returns the notification history for the logged-in user
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getMyNotifications() {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getMyNotifications()));
    }

    // GET /api/notifications/unsent
    // Admin endpoint — returns notifications that failed to send (is_sent=false)
    // These can be inspected or retried
    @GetMapping("/unsent")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getUnsent() {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getUnsentNotifications()));
    }

    // GET /api/notifications/unread-count  (P3.6)
    // Returns the COUNT of the authenticated user's own unread notifications.
    // User-scoped server-side — never a global count and never another user's count.
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount() {
        long count = notificationService.getUnreadCount();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("unreadCount", count)));
    }

    // PATCH /api/notifications/{id}/read  (P3.6)
    // Marks ONE notification (owned by the authenticated user) as read. Ownership is
    // verified server-side: unknown id → 404, another user's notification → 403.
    // Idempotent — marking an already-read notification succeeds. Does not send email.
    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.markAsRead(id)));
    }

    // PATCH /api/notifications/read-all  (P3.6)
    // Marks ALL of the authenticated user's unread notifications as read (user-scoped).
    // Returns the number of notifications that were marked read.
    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllAsRead() {
        int updated = notificationService.markAllAsRead();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("markedRead", updated)));
    }
}
