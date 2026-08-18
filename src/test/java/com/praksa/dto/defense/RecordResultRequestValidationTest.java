package com.praksa.dto.defense;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roadmap Item #7 — bean-validation contract on {@link RecordResultRequest}.
 *
 * This mirrors what {@code @Valid} enforces at the controller boundary: a defense grade
 * must be present and strictly between 5 and 10 inclusive. 4 and 11 fail; 5..10 pass;
 * null fails.
 */
class RecordResultRequestValidationTest {

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

    private RecordResultRequest request(Integer grade) {
        RecordResultRequest r = new RecordResultRequest();
        r.setGrade(grade);
        return r;
    }

    @ParameterizedTest(name = "grade {0} is valid")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("grades 5..10 pass validation")
    void validGrades(int grade) {
        Set<?> violations = validator.validate(request(grade));
        assertTrue(violations.isEmpty(), "expected no violations for grade " + grade);
    }

    @ParameterizedTest(name = "grade {0} is invalid")
    @ValueSource(ints = {4, 11, 0, -1, 100})
    @DisplayName("out-of-range grades fail validation")
    void outOfRangeGrades(int grade) {
        Set<?> violations = validator.validate(request(grade));
        assertFalse(violations.isEmpty(), "expected a violation for grade " + grade);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("null grade fails validation (@NotNull)")
    void nullGrade(Integer grade) {
        Set<?> violations = validator.validate(request(grade));
        assertFalse(violations.isEmpty(), "expected a @NotNull violation for null grade");
    }
}
