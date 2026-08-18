package com.praksa.service;

import com.praksa.dto.defense.DefenseResultResponse;
import com.praksa.dto.defense.RecordResultRequest;

import java.util.UUID;

public interface DefenseResultService {
    DefenseResultResponse recordResult(UUID thesisId, UUID defenseId, RecordResultRequest request);
    DefenseResultResponse getResult(UUID thesisId, UUID defenseId);

    /**
     * Renders the official defense record ("записник за одбрана") for a graded defense
     * and returns the PDF bytes. Available only once a result has been recorded; access
     * is restricted to parties related to the thesis (see the implementation).
     */
    byte[] generateRecordPdf(UUID thesisId, UUID defenseId);
}
