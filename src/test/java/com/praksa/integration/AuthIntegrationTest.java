package com.praksa.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praksa.dto.auth.LoginRequest;
import com.praksa.dto.auth.RegisterRequest;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the authentication flow.
 *
 * @SpringBootTest — loads the FULL application context (real beans, real security, real DB)
 * @AutoConfigureMockMvc — gives us MockMvc to send HTTP requests without a running server
 * @Transactional — rolls back every test so they don't leave data behind
 *
 * NOTE: These tests require a running PostgreSQL instance with the diploma_system database.
 * For a fully isolated setup, replace PostgreSQL with an H2 in-memory DB or use Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    // -------------------------------------------------------------------------
    // REGISTER
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Student can register with index number")
    void registerStudent_success() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("newstudent@test.com");
        request.setPassword("password123");
        request.setFullName("New Student");
        request.setRole(Role.STUDENT);
        request.setIndexNumber("2024/999");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("STUDENT"))
                .andExpect(jsonPath("$.data.email").value("newstudent@test.com"));
    }

    @Test
    @DisplayName("Registration fails with blank email")
    void registerStudent_blankEmail_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("");               // invalid
        request.setPassword("password123");
        request.setFullName("New Student");
        request.setRole(Role.STUDENT);
        request.setIndexNumber("2024/888");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Student registration fails without index number")
    void registerStudent_missingIndex_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("student2@test.com");
        request.setPassword("password123");
        request.setFullName("Student No Index");
        request.setRole(Role.STUDENT);
        // intentionally no indexNumber

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Duplicate email registration returns 400")
    void registerStudent_duplicateEmail_returns400() throws Exception {
        // First registration
        RegisterRequest first = new RegisterRequest();
        first.setEmail("duplicate@test.com");
        first.setPassword("password123");
        first.setFullName("First User");
        first.setRole(Role.MENTOR);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk());

        // Second registration with same email
        RegisterRequest second = new RegisterRequest();
        second.setEmail("duplicate@test.com");
        second.setPassword("password123");
        second.setFullName("Second User");
        second.setRole(Role.MENTOR);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email already in use"));
    }

    // -------------------------------------------------------------------------
    // LOGIN
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Registered user can log in and get a token")
    void login_success() throws Exception {
        // Register first
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("logintest@test.com");
        reg.setPassword("password123");
        reg.setFullName("Login Test");
        reg.setRole(Role.MENTOR);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        // Now login
        LoginRequest login = new LoginRequest();
        login.setEmail("logintest@test.com");
        login.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    @DisplayName("Wrong password returns 403")
    void login_wrongPassword_returns403() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("wrongpass@test.com");
        reg.setPassword("correctPassword");
        reg.setFullName("Wrong Pass Test");
        reg.setRole(Role.MENTOR);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        LoginRequest login = new LoginRequest();
        login.setEmail("wrongpass@test.com");
        login.setPassword("wrongPassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().is4xxClientError());
    }

    // -------------------------------------------------------------------------
    // AUTHORIZATION
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Protected endpoint returns 403 without token")
    void protectedEndpoint_noToken_returns403() throws Exception {
        mockMvc.perform(get("/api/theses/my"))
                .andExpect(status().isForbidden());
    }
}
