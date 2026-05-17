package com.praksa.service;

import com.praksa.dto.defense.DefenseResultResponse;
import com.praksa.dto.defense.RecordResultRequest;

import java.util.UUID;

public interface DefenseResultService {
    DefenseResultResponse recordResult(UUID thesisId, UUID defenseId, RecordResultRequest request);
    DefenseResultResponse getResult(UUID thesisId, UUID defenseId);
}
