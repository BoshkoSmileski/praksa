package com.praksa.integration;

import com.praksa.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3.3 — CORS configuration (BUG-14). Verifies the configuration-driven CORS policy against the
 * REAL Spring Security filter chain: the local dev origin is allowed, an unlisted origin is
 * rejected, the {@code Authorization} header remains usable cross-origin, preflight works, and
 * both auth and protected endpoints still function.
 *
 * <p>The {@code test} profile inherits the default {@code app.cors.allowed-origins=http://localhost:5173}
 * (no override), so this exercises the shipped default.
 */
class CorsConfigurationIntegrationTest extends AbstractWorkflowIntegrationTest {

    private static final String ALLOWED = "http://localhost:5173";
    private static final String DISALLOWED = "http://evil.example.com";

    @Test
    @DisplayName("Preflight from the allowed local origin is approved, echoes the origin, and allows Authorization")
    void preflight_allowedOrigin() throws Exception {
        mockMvc.perform(options("/api/theses/my")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("authorization")));
    }

    @Test
    @DisplayName("Preflight from a disallowed origin is rejected (no Access-Control-Allow-Origin)")
    void preflight_disallowedOrigin() throws Exception {
        mockMvc.perform(options("/api/theses/my")
                        .header("Origin", DISALLOWED)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Authorization header remains usable cross-origin: authenticated GET from the allowed origin succeeds with CORS headers")
    void authorizationHeader_usableCrossOrigin() throws Exception {
        User student = createStudent(240);
        mockMvc.perform(get("/api/theses/my")
                        .header("Origin", ALLOWED)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
    }

    @Test
    @DisplayName("A protected endpoint still enforces auth: no token → 4xx even from the allowed origin, and CORS is not the reason (ACAO present)")
    void protectedEndpoint_stillRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/theses/my")
                        .header("Origin", ALLOWED))
                .andExpect(status().is4xxClientError())
                // ACAO present proves the CORS layer approved the origin — the 4xx is the auth
                // guard, NOT a CORS rejection.
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
    }

    @Test
    @DisplayName("Auth endpoint works cross-origin: preflight + actual register from the allowed origin succeed")
    void authEndpoint_worksCrossOrigin() throws Exception {
        // preflight
        mockMvc.perform(options("/api/auth/register")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));

        // actual public registration (creates a STUDENT) — rolled back by @Transactional
        String s = java.util.UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/auth/register")
                        .header("Origin", ALLOWED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Cors Test " + s,
                                "email", "cors-" + s + "@t.test",
                                "password", "password123",
                                "role", "STUDENT",
                                "indexNumber", "CORS-" + s))))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
    }
}
