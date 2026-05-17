package com.praksa.controller;

import com.praksa.dto.ApiResponse;
import com.praksa.dto.notification.NotificationResponse;
import com.praksa.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
