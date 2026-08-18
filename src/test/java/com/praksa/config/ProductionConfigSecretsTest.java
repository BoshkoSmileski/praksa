package com.praksa.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-15 — static inspection of the tracked production config (application.properties).
 *
 * <p>These assertions guard against a regression re-introducing an inline secret default.
 * They read the raw resource so they see exactly what is committed, independent of any
 * environment resolution.
 */
class ProductionConfigSecretsTest {

    private static String raw;          // exact committed text
    private static Properties props;    // parsed key=value view

    /** The retired local-dev-only JWT default that must never reappear. */
    private static final String OLD_JWT_DEFAULT =
            "4a8f3b2e1d9c7f5a3b2e1d9c7f5a4a8f3b2e1d9c7f5a3b2e1d9c7f5a4a8f3b";

    @BeforeAll
    static void load() throws IOException {
        ClassPathResource resource = new ClassPathResource("application.properties");
        try (InputStream in = resource.getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        props = new Properties();
        try (InputStream in = resource.getInputStream()) {
            props.load(in);
        }
    }

    // 1. Production configuration does not contain an inline DB password.
    @Test
    @DisplayName("DB password is a required placeholder with no inline default")
    void dbPassword_hasNoInlineDefault() {
        String value = props.getProperty("spring.datasource.password");
        assertEquals("${SPRING_DATASOURCE_PASSWORD}", value,
                "DB password must be a bare required placeholder (no ${...:default})");
        assertFalse(raw.contains("${SPRING_DATASOURCE_PASSWORD:"),
                "no ':default' fallback may follow the DB password placeholder");
        // The old inline dev default (":123") must be gone.
        assertFalse(raw.contains("${SPRING_DATASOURCE_PASSWORD:123}"),
                "the old inline dev DB password default must be removed");
    }

    // 2. Production configuration does not contain an inline JWT secret.
    // 3. Missing required JWT secret does not silently resolve to a known default.
    @Test
    @DisplayName("JWT secret is a required placeholder with no inline default, and the old default is gone")
    void jwtSecret_hasNoInlineDefault() {
        String value = props.getProperty("jwt.secret");
        assertEquals("${JWT_SECRET}", value,
                "jwt.secret must be a bare required placeholder (no ${...:default})");
        // Precise: the LOCAL key placeholder must have no ':default'. (Note: the external
        // key line ${EXTERNAL_JWT_SECRET:} legitimately contains an empty default and is
        // asserted separately, so match the full "${JWT_SECRET:" prefix here.)
        assertFalse(raw.contains("${JWT_SECRET:"),
                "no ':default' fallback may follow the local JWT secret placeholder");
        assertFalse(raw.contains(OLD_JWT_DEFAULT),
                "the old hard-coded JWT secret must not appear anywhere in the tracked config");
    }

    // 5. External-auth key exists, is separate, and carries no dev fallback.
    @Test
    @DisplayName("external JWT key is a distinct property that defaults to empty, never to the local key")
    void externalJwtSecret_isSeparateAndEmptyByDefault() {
        String value = props.getProperty("auth.external-jwt-secret");
        assertEquals("${EXTERNAL_JWT_SECRET:}", value,
                "external key must be its own placeholder with an empty (not local/dev) default");
        assertTrue(raw.contains("auth.external-jwt-secret"),
                "the external signing key must be configured separately from jwt.secret");
        // It must NOT be wired to fall back to the local secret.
        assertFalse(raw.contains("EXTERNAL_JWT_SECRET:${jwt.secret")
                        || raw.contains("auth.external-jwt-secret=${jwt.secret"),
                "external key must never fall back to the local jwt.secret");
    }
}
