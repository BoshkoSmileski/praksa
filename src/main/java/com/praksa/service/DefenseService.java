package com.praksa.service;

import com.praksa.dto.defense.DefenseResponse;
import com.praksa.dto.defense.ScheduleDefenseRequest;

import java.util.List;
import java.util.UUID;

public interface DefenseService {

    // Mentor schedules a new defense (first time or after cancellation)
    DefenseResponse scheduleDefense(UUID thesisId, ScheduleDefenseRequest request);

    // Student or mentor cancels — workflow does NOT go backwards
    DefenseResponse cancelDefense(UUID thesisId);

    // Returns the currently active (non-cancelled) defense
    DefenseResponse getActiveDefense(UUID thesisId);

    // Returns full history including cancelled ones
    List<DefenseResponse> getAllDefenses(UUID thesisId);
}
