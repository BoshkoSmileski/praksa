package com.praksa.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-15 — JWT signing-key hardening.
 *
 * <p>Pure unit tests (no Spring context, no Postgres). They pin down the four things the
 * BUG-15 change must guarantee:
 * <ol>
 *   <li>local JWT generation/validation still works end to end (round trip, claims, expiry);</li>
 *   <li>the local development / production key path is unchanged in the default mode;</li>
 *   <li>external-auth mode verifies with its OWN key and does not accept a token signed
 *       with the local key — i.e. it never silently reuses the local/dev secret;</li>
 *   <li>misconfiguration (missing local key, or external enabled without an external key)
 *       fails fast at startup instead of falling back to a known default.</li>
 * </ol>
 */
class JwtUtilBug15Test {

    // Both keys are >= 256 bits as required by HS256 (Keys.hmacShaKeyFor).
    private static final String LOCAL_KEY    = "local-signing-key-for-tests-0123456789-abcdefghijklmnop";
    private static final String EXTERNAL_KEY = "external-provider-key-for-tests-zyxwvutsrqponml-9876543210";

    private JwtUtil newJwtUtil(String localSecret, String externalSecret, boolean externalEnabled) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "localSecret", localSecret);
        ReflectionTestUtils.setField(util, "externalSecret", externalSecret);
        ReflectionTestUtils.setField(util, "externalAuthEnabled", externalEnabled);
        ReflectionTestUtils.setField(util, "expirationMs", 86_400_000L);
        return util;
    }

    // ── 1. Local generation/validation is intact ───────────────────────────────

    @Test
    @DisplayName("local mode: token round-trips and preserves email + role claims")
    void localMode_roundTrip_preservesClaims() {
        JwtUtil util = newJwtUtil(LOCAL_KEY, "", false);
        util.validateKeyConfiguration(); // must not throw

        String token = util.generateToken("student@test.com", "STUDENT");

        assertTrue(util.isTokenValid(token));
        assertEquals("student@test.com", util.extractEmail(token));
        assertEquals("STUDENT", util.extractRole(token));
    }

    @Test
    @DisplayName("existing expiration behavior is preserved: an already-expired token is invalid")
    void localMode_expiredToken_isInvalid() {
        JwtUtil util = newJwtUtil(LOCAL_KEY, "", false);
        ReflectionTestUtils.setField(util, "expirationMs", -1_000L); // already in the past
        String expired = util.generateToken("student@test.com", "STUDENT");
        assertFalse(util.isTokenValid(expired));
    }

    // ── 2/3. External mode uses its OWN key, never the local one ────────────────

    @Test
    @DisplayName("external mode does NOT accept a token signed with the local key (no silent reuse)")
    void externalMode_rejectsLocallySignedToken() {
        // A token minted by the local key path...
        JwtUtil local = newJwtUtil(LOCAL_KEY, "", false);
        String localToken = local.generateToken("student@test.com", "STUDENT");

        // ...must NOT validate once external auth is on with a DISTINCT external key.
        JwtUtil external = newJwtUtil(LOCAL_KEY, EXTERNAL_KEY, true);
        external.validateKeyConfiguration(); // must not throw — external key is configured
        assertFalse(external.isTokenValid(localToken),
                "external mode must verify with the external key, not the local/dev key");
    }

    @Test
    @DisplayName("external mode validates a token signed with the external key")
    void externalMode_acceptsExternallySignedToken() {
        JwtUtil external = newJwtUtil(LOCAL_KEY, EXTERNAL_KEY, true);
        String externalToken = external.generateToken("ext@test.com", "MENTOR");

        assertTrue(external.isTokenValid(externalToken));
        assertEquals("ext@test.com", external.extractEmail(externalToken));
        assertEquals("MENTOR", external.extractRole(externalToken));
    }

    // ── 4. Fail-fast on misconfiguration ────────────────────────────────────────

    @Test
    @DisplayName("missing local key fails fast (does not resolve to a known default)")
    void missingLocalKey_failsFast() {
        JwtUtil util = newJwtUtil("   ", "", false);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, util::validateKeyConfiguration);
        assertTrue(ex.getMessage().contains("jwt.secret"));
    }

    @Test
    @DisplayName("external auth enabled without an external key fails fast (no fallback to local key)")
    void externalEnabledWithoutKey_failsFast() {
        JwtUtil util = newJwtUtil(LOCAL_KEY, "", true);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, util::validateKeyConfiguration);
        assertTrue(ex.getMessage().contains("external"),
                "must explain that external auth needs its own key");
    }
}
