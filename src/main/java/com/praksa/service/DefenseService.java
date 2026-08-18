package com.praksa.service;

import com.praksa.dto.defense.DefenseResponse;
import com.praksa.dto.defense.ScheduleDefenseRequest;
import com.praksa.dto.thesis.ThesisResponse;

import java.util.List;
import java.util.UUID;

public interface DefenseService {

    // Student requests a defense AFTER Student Service has verified defense eligibility
    // (Item #8), i.e. when the thesis is already PENDING_DEFENSE_SCHEDULING. It does not
    // change the status; it signals STUDENT_SERVICE (DEFENSE_REQUESTED) that the student
    // is ready. No Defense row is created yet — a request is NOT a scheduled defense.
    ThesisResponse requestDefense(UUID thesisId);

    // STUDENT_SERVICE schedules the defense (room + date/time) after a student request,
    // or reschedules an existing one. Mentors can no longer schedule directly.
    DefenseResponse scheduleDefense(UUID thesisId, ScheduleDefenseRequest request);

    // Student or mentor cancels — workflow does NOT go backwards
    DefenseResponse cancelDefense(UUID thesisId);

    // Returns the currently active (non-cancelled) defense
    DefenseResponse getActiveDefense(UUID thesisId);

    // Returns full history including cancelled ones
    List<DefenseResponse> getAllDefenses(UUID thesisId);
}
