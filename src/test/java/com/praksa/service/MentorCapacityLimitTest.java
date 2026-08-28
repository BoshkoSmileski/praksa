package com.praksa.service;

import com.praksa.dto.thesis.SubmitMentorRequestDto;
import com.praksa.dto.thesis.ThesisResponse;
import com.praksa.exception.BadRequestException;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.NotificationType;
import com.praksa.model.enums.Role;
import com.praksa.model.enums.ThesisStatus;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.SecurityUtils;
import com.praksa.service.impl.ThesisServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the mentor active-thesis capacity rule enforced in
 * {@link ThesisServiceImpl#submitMentorRequest}. The faculty procedure allows a mentor to
 * supervise at most 15 active theses at once ("active" = {@link ThesisRepository#countActiveMentorTheses}
 * i.e. any thesis whose status is not ARCHIVED — the same definition already used elsewhere
 * in the app; not redefined here). Pure Mockito, no Spring context, mirrors the style of
 * {@link ThesisCreditsDeadlineTest}.
 */
@ExtendWith(MockitoExtension.class)
class MentorCapacityLimitTest {

    @Mock private ThesisRepository thesisRepository;
    @Mock private ThesisStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private NotificationService notificationService;

    @InjectMocks private ThesisServiceImpl thesisService;

    private User student() {
        return User.builder().id(UUID.randomUUID()).email("student@t.com")
                .fullName("Student").role(Role.STUDENT).build();
    }

    private User mentor() {
        return User.builder().id(UUID.randomUUID()).email("mentor@t.com")
                .fullName("Mentor").role(Role.MENTOR).build();
    }

    private Thesis thesis(User owner) {
        return Thesis.builder().id(UUID.randomUUID()).title("A valid thesis title")
                .student(owner).status(ThesisStatus.TOPIC_SELECTION).build();
    }

    private SubmitMentorRequestDto request(UUID mentorId) {
        SubmitMentorRequestDto dto = new SubmitMentorRequestDto();
        dto.setMentorId(mentorId);
        return dto;
    }

    @Test
    @DisplayName("Mentor with 14 active theses CAN accept another thesis")
    void mentor_with14ActiveTheses_canAcceptAnother() {
        User s = student();
        User m = mentor();
        Thesis t = thesis(s);

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(userRepository.findById(m.getId())).thenReturn(Optional.of(m));
        when(thesisRepository.countActiveMentorTheses(m)).thenReturn(14L);

        ThesisResponse res = thesisService.submitMentorRequest(t.getId(), request(m.getId()));

        assertNotNull(res);
        assertEquals(ThesisStatus.PENDING_MENTOR_APPROVAL, res.getStatus());
        assertEquals(m.getId(), t.getMentor().getId());
        verify(thesisRepository).save(t);
        verify(notificationService).notify(m, t, NotificationType.MENTOR_REQUEST_RECEIVED);
    }

    @Test
    @DisplayName("Mentor with 15 active theses CANNOT accept another thesis")
    void mentor_with15ActiveTheses_cannotAcceptAnother() {
        User s = student();
        User m = mentor();
        Thesis t = thesis(s);

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(userRepository.findById(m.getId())).thenReturn(Optional.of(m));
        when(thesisRepository.countActiveMentorTheses(m)).thenReturn(15L);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> thesisService.submitMentorRequest(t.getId(), request(m.getId())));

        assertEquals("Овој ментор веќе има 15 активни дипломски работи и не може да прифати нови.", ex.getMessage());
        assertNull(t.getMentor());
        assertEquals(ThesisStatus.TOPIC_SELECTION, t.getStatus());
        verify(thesisRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), eq(NotificationType.MENTOR_REQUEST_RECEIVED));
    }

    @Test
    @DisplayName("Mentor with more than 15 active theses (16) is also rejected")
    void mentor_withMoreThan15ActiveTheses_rejected() {
        User s = student();
        User m = mentor();
        Thesis t = thesis(s);

        when(securityUtils.getCurrentUser()).thenReturn(s);
        when(thesisRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(userRepository.findById(m.getId())).thenReturn(Optional.of(m));
        when(thesisRepository.countActiveMentorTheses(m)).thenReturn(16L);

        assertThrows(BadRequestException.class,
                () -> thesisService.submitMentorRequest(t.getId(), request(m.getId())));
        verify(thesisRepository, never()).save(any());
    }
}
