package com.praksa.dto.thesis;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bean-validation contract on {@link DeadlineExtensionCreateRequest} — the deadline-extension
 * feature's request DTO. Mirrors {@code RecordResultRequestValidationTest}'s style: a reason
 * must be present, non-blank, and bounded; requestedDays must be present and within 1-15.
 */
class DeadlineExtensionCreateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    private DeadlineExtensionCreateRequest request(String reason, Integer days) {
        DeadlineExtensionCreateRequest r = new DeadlineExtensionCreateRequest();
        r.setReason(reason);
        r.setRequestedDays(days);
        return r;
    }

    @Test
    @DisplayName("a valid request (non-blank reason, 1-15 days) passes validation")
    void validRequest_passes() {
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request("Медицинска причина за одложување.", 10));
        assertTrue(violations.isEmpty());
    }

    @ParameterizedTest(name = "blank reason [{0}] fails validation (@NotBlank)")
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("7: a blank or whitespace-only reason fails validation")
    void blankReason_fails(String reason) {
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request(reason, 5));
        assertFalse(violations.isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("7: a null reason fails validation (@NotBlank)")
    void nullReason_fails(String reason) {
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request(reason, 5));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("8: a reason exceeding 2000 characters fails validation (@Size)")
    void overlongReason_fails() {
        String tooLong = "a".repeat(2001);
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request(tooLong, 5));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("a reason of exactly 2000 characters passes validation (boundary)")
    void exactly2000Reason_passes() {
        String exact = "a".repeat(2000);
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request(exact, 5));
        assertTrue(violations.isEmpty());
    }

    @ParameterizedTest(name = "requestedDays {0} is valid")
    @ValueSource(ints = {1, 5, 10, 15})
    @DisplayName("requestedDays 1..15 pass validation")
    void validDays_pass(int days) {
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request("X", days));
        assertTrue(violations.isEmpty());
    }

    @ParameterizedTest(name = "9/10: requestedDays {0} is invalid")
    @ValueSource(ints = {0, -1, -5, 16, 30, 100})
    @DisplayName("9/10: out-of-range requestedDays fail validation")
    void invalidDays_fail(int days) {
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request("X", days));
        assertFalse(violations.isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("9: a null requestedDays fails validation (@NotNull)")
    void nullDays_fails(Integer days) {
        Set<ConstraintViolation<DeadlineExtensionCreateRequest>> violations =
                validator.validate(request("X", days));
        assertFalse(violations.isEmpty());
    }
}
