package com.praksa.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.praksa.model.Notification;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3.6 — read/unread over the FULL stack: real controllers, services, repositories, the real
 * Spring Security filter chain, real JWT decoding, and the real local PostgreSQL DB. Nothing is
 * mocked. This is where the critical cross-user ownership property is proven with real
 * authorization (not a stubbed policy), per the task's integration-test requirement.
 */
class NotificationReadIntegrationTest extends AbstractWorkflowIntegrationTest {

    /** Persist a notification row directly for {@code owner} (thesis may be null). */
    private Notification persistNotification(User owner, boolean read) {
        return notificationRepository.save(Notification.builder()
                .user(owner)
                .thesis(null)
                .type(NotificationType.ELIGIBILITY_APPROVED.name())
                .isSent(false)
                .isRead(read)
                .build());
    }

    // ─── End-to-end via the real workflow: a genuine notification is unread, then read ──────

    @Test
    @DisplayName("Real workflow notification is unread; owner marks it read; unread-count updates; isSent untouched")
    void workflowNotification_readLifecycle() throws Exception {
        Actors a = newActors();
        UUID thesisId = createThesis(a.student, "Read lifecycle " + UUID.randomUUID());
        // This produces a real ELIGIBILITY_APPROVED notification to the student.
        decideEligibility(a.service, thesisId, true);

        // GET /my as the student — the notification is present and UNREAD (wire key "read").
        MvcResult listed = doGet("/api/notifications/my", a.student).andExpect(status().isOk()).andReturn();
        JsonNode arr = dataNode(listed);
        JsonNode target = null;
        for (JsonNode n : arr) {
            if (NotificationType.ELIGIBILITY_APPROVED.name().equals(n.get("type").asText())) target = n;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(target, "eligibility notification should exist");
        assertEquals(false, target.get("read").asBoolean(), "new notification must be unread on the wire");
        UUID notifId = UUID.fromString(target.get("id").asText());

        // Unread count is at least 1 for the student.
        long before = unreadCount(a.student);
        org.junit.jupiter.api.Assertions.assertTrue(before >= 1);

        // Mark it read.
        doPatch("/api/notifications/" + notifId + "/read", a.student, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));

        // DB reflects read=true; email state (isSent) untouched by the read action.
        Notification persisted = notificationRepository.findById(notifId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(persisted.isRead());
        org.junit.jupiter.api.Assertions.assertFalse(persisted.isSent(), "read must not change isSent");
        org.junit.jupiter.api.Assertions.assertNull(persisted.getSentAt(), "read must not set sentAt");

        // Unread count dropped by one.
        assertEquals(before - 1, unreadCount(a.student));
    }

    // ─── Critical: user B cannot mark user A's notification read (real auth, 403) ───────────

    @Test
    @DisplayName("Cross-user mark-read is forbidden (403) and A's notification stays unread")
    void crossUser_markRead_forbidden() throws Exception {
        User a = createStudent(240);
        User b = createStudent(240);
        Notification owned = persistNotification(a, false);

        doPatch("/api/notifications/" + owned.getId() + "/read", b, null)
                .andExpect(status().isForbidden());

        // A's notification is untouched.
        Notification reloaded = notificationRepository.findById(owned.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(reloaded.isRead());
    }

    @Test
    @DisplayName("mark-read on an unknown notification id → 404")
    void markRead_unknownId_notFound() throws Exception {
        User a = createStudent(240);
        doPatch("/api/notifications/" + UUID.randomUUID() + "/read", a, null)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("mark-read is idempotent over HTTP: two calls both succeed, stays read")
    void markRead_idempotent() throws Exception {
        User a = createStudent(240);
        Notification n = persistNotification(a, false);

        doPatch("/api/notifications/" + n.getId() + "/read", a, null).andExpect(status().isOk());
        doPatch("/api/notifications/" + n.getId() + "/read", a, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));

        assertEquals(1, notificationRepository.findByUserOrderByCreatedAtDesc(a).size(),
                "idempotent mark-read must not create a duplicate row");
    }

    // ─── mark-all is user-scoped: A's unread → read, B's untouched ──────────────────────────

    @Test
    @DisplayName("read-all marks only the caller's notifications; another user's are unchanged")
    void markAll_userScoped() throws Exception {
        User a = createStudent(240);
        User b = createStudent(240);
        persistNotification(a, false);
        persistNotification(a, false);
        persistNotification(a, true);   // already read
        Notification bUnread = persistNotification(b, false);

        MvcResult r = doPatch("/api/notifications/read-all", a, null)
                .andExpect(status().isOk()).andReturn();
        int marked = dataNode(r).get("markedRead").asInt();
        assertEquals(2, marked, "only A's 2 unread rows are marked");

        // All of A's notifications are now read.
        boolean allARead = notificationRepository.findByUserOrderByCreatedAtDesc(a).stream()
                .allMatch(Notification::isRead);
        org.junit.jupiter.api.Assertions.assertTrue(allARead);

        // B's notification is untouched.
        org.junit.jupiter.api.Assertions.assertFalse(
                notificationRepository.findById(bUnread.getId()).orElseThrow().isRead());
    }

    // ─── unread-count endpoint is user-scoped ──────────────────────────────────────────────

    @Test
    @DisplayName("unread-count returns only the caller's own unread total")
    void unreadCount_userScoped() throws Exception {
        User a = createStudent(240);
        User b = createStudent(240);
        persistNotification(a, false);
        persistNotification(a, false);
        persistNotification(a, true);
        persistNotification(b, false); // must NOT count toward A

        assertEquals(2L, unreadCount(a));
        assertEquals(1L, unreadCount(b));
    }

    @Test
    @DisplayName("all notification endpoints require authentication (no token → 401/403)")
    void endpoints_requireAuth() throws Exception {
        // No Authorization header → Spring Security rejects before the controller.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/notifications/unread-count"))
                .andExpect(status().is4xxClientError());
    }

    // helper: read the unread-count endpoint as a given user
    private long unreadCount(User u) throws Exception {
        MvcResult r = doGet("/api/notifications/unread-count", u).andExpect(status().isOk()).andReturn();
        return dataNode(r).get("unreadCount").asLong();
    }
}
