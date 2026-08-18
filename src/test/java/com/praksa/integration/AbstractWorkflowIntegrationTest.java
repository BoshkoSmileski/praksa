package com.praksa.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praksa.model.User;
import com.praksa.model.enums.MentorDecision;
import com.praksa.model.enums.Role;
import com.praksa.repository.CommitteeMemberRepository;
import com.praksa.repository.DefenseRepository;
import com.praksa.repository.DefenseResultRepository;
import com.praksa.repository.NotificationRepository;
import com.praksa.repository.ThesisRepository;
import com.praksa.repository.ThesisStatusHistoryRepository;
import com.praksa.repository.UserRepository;
import com.praksa.security.JwtUtil;
import com.praksa.service.ScheduledTasksService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared plumbing for the P2.4 workflow integration tests.
 *
 * <p>These are REAL integration tests, not Mockito unit tests. Each concrete subclass runs
 * against:
 * <ul>
 *   <li>the FULL Spring application context ({@code @SpringBootTest}) — real controllers,
 *       services, repositories, security filter chain, JWT decoding and the
 *       {@link com.praksa.security.ThesisReadAccessPolicy} authorization layer;</li>
 *   <li>the real local PostgreSQL {@code diploma_system} database (the same one the app uses)
 *       — nothing is mocked, and every assertion about persisted state is a genuine DB read;</li>
 *   <li>real workflow state transitions and the real notification-persistence path.</li>
 * </ul>
 *
 * <p><b>Email boundary.</b> The only external boundary avoided is SMTP: the {@code test}
 * profile inherits {@code app.mail.enabled=false}, so {@code EmailService.sendAsync} skips the
 * network send and leaves each {@code Notification} row {@code is_sent=false}. The
 * NotificationService / workflow services themselves are NOT mocked — notification ROWS are
 * still written synchronously in the caller's transaction and are asserted directly from the
 * {@link NotificationRepository}.
 *
 * <p><b>Isolation.</b> {@code @Transactional} rolls every test back, so nothing created here
 * survives the test. All fixture users get process-unique emails / index numbers so they never
 * collide with the {@code DataInitializer} seed rows or with each other. Tests never rely on
 * pre-existing seed data — every actor is created explicitly.
 *
 * <p><b>Authentication.</b> Tests use the project's real JWT mechanism. A fixture user is
 * persisted first, then a token is minted with {@link JwtUtil#generateToken} exactly as
 * {@code AuthServiceImpl} does at login; the request carries it as {@code Authorization: Bearer}.
 * The real {@code JwtAuthFilter} decodes it and {@code SecurityUtils.getCurrentUser()} resolves
 * the row from the DB, so every workflow call executes as the correct authenticated principal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class AbstractWorkflowIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JwtUtil jwtUtil;
    @Autowired protected PasswordEncoder passwordEncoder;

    @Autowired protected UserRepository userRepository;
    @Autowired protected ThesisRepository thesisRepository;
    @Autowired protected CommitteeMemberRepository committeeRepository;
    @Autowired protected DefenseRepository defenseRepository;
    @Autowired protected DefenseResultRepository defenseResultRepository;
    @Autowired protected NotificationRepository notificationRepository;
    @Autowired protected ThesisStatusHistoryRepository statusHistoryRepository;
    @Autowired protected ScheduledTasksService scheduledTasksService;

    // -------------------------------------------------------------------------
    // Fixture users
    // -------------------------------------------------------------------------

    /** The full cast of role-holders a complete lifecycle needs. */
    protected static class Actors {
        User student;
        User mentor;          // becomes the assigned mentor + MENTOR_MEMBER committee seat
        User professorA;      // FORMAL_MEMBER of the committee
        User professorB;      // FORMAL_MEMBER of the committee
        User service;         // STUDENT_SERVICE
        User archive;         // ARCHIVE
        User committee;       // a bare COMMITTEE-role user (no seat) — for negative tests
    }

    protected Actors newActors() {
        Actors a = new Actors();
        a.student = createStudent(240);
        a.mentor = createUser(Role.MENTOR);
        a.professorA = createUser(Role.MENTOR);
        a.professorB = createUser(Role.MENTOR);
        a.service = createUser(Role.STUDENT_SERVICE);
        a.archive = createUser(Role.ARCHIVE);
        a.committee = createUser(Role.COMMITTEE);
        return a;
    }

    protected User createStudent(Integer credits) {
        String s = shortId();
        return userRepository.save(User.builder()
                .fullName("Student " + s)
                .email("student-" + s + "@wf.test")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.STUDENT)
                .indexNumber("WF-" + s)
                .credits(credits)
                .build());
    }

    protected User createUser(Role role) {
        String s = shortId();
        return userRepository.save(User.builder()
                .fullName(role.name() + " " + s)
                .email(role.name().toLowerCase() + "-" + s + "@wf.test")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .indexNumber(null)
                .credits(null)
                .build());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected String bearer(User u) {
        return "Bearer " + jwtUtil.generateToken(u.getEmail(), u.getRole().name());
    }

    // -------------------------------------------------------------------------
    // Low-level authenticated request helpers
    // -------------------------------------------------------------------------

    protected ResultActions doPost(String url, User as, Object body) throws Exception {
        return mockMvc.perform(auth(post(url), as, body));
    }

    protected ResultActions doPatch(String url, User as, Object body) throws Exception {
        return mockMvc.perform(auth(patch(url), as, body));
    }

    protected ResultActions doGet(String url, User as) throws Exception {
        return mockMvc.perform(auth(get(url), as, null));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, User as, Object body) throws Exception {
        if (as != null) {
            b.header("Authorization", bearer(as));
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return b;
    }

    /** Extract the {@code data} node from an {@code ApiResponse} JSON body. */
    protected JsonNode dataNode(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    protected UUID dataId(MvcResult r) throws Exception {
        return UUID.fromString(dataNode(r).get("id").asText());
    }

    // -------------------------------------------------------------------------
    // Workflow step helpers — each drives ONE real business operation over HTTP and
    // asserts a 200, so tests read as a sequence of legitimate actions. Callers add
    // repository assertions between steps.
    // -------------------------------------------------------------------------

    protected UUID createThesis(User student, String title) throws Exception {
        MvcResult r = doPost("/api/theses", student, Map.of("title", title))
                .andExpect(status().isOk()).andReturn();
        return dataId(r);
    }

    protected void decideEligibility(User service, UUID thesisId, boolean approved) throws Exception {
        doPatch("/api/theses/" + thesisId + "/eligibility", service, Map.of("approved", approved))
                .andExpect(status().isOk());
    }

    protected void submitMentorRequest(User student, UUID thesisId, User mentor) throws Exception {
        doPatch("/api/theses/" + thesisId + "/mentor-request", student,
                Map.of("mentorId", mentor.getId().toString()))
                .andExpect(status().isOk());
    }

    protected void decideMentor(User mentor, UUID thesisId, MentorDecision decision, String comment) throws Exception {
        Map<String, Object> body = comment == null
                ? Map.of("decision", decision.name())
                : Map.of("decision", decision.name(), "mentorComment", comment);
        doPatch("/api/theses/" + thesisId + "/mentor-decision", mentor, body)
                .andExpect(status().isOk());
    }

    protected void reviseProposal(User student, UUID thesisId, String title) throws Exception {
        doPatch("/api/theses/" + thesisId + "/revise-proposal", student, Map.of("title", title))
                .andExpect(status().isOk());
    }

    protected void submitApplication(User student, UUID thesisId) throws Exception {
        doPatch("/api/theses/" + thesisId + "/submit-application", student, null)
                .andExpect(status().isOk());
    }

    protected void archiveValidate(User archive, UUID thesisId, boolean approved, String comment) throws Exception {
        Map<String, Object> body = comment == null
                ? Map.of("approved", approved)
                : Map.of("approved", approved, "comment", comment);
        doPatch("/api/theses/" + thesisId + "/archive-validate", archive, body)
                .andExpect(status().isOk());
    }

    protected void serviceValidate(User service, UUID thesisId, boolean approved, String comment) throws Exception {
        Map<String, Object> body = comment == null
                ? Map.of("approved", approved)
                : Map.of("approved", approved, "comment", comment);
        doPatch("/api/theses/" + thesisId + "/service-validate", service, body)
                .andExpect(status().isOk());
    }

    protected UUID uploadVersion(User student, UUID thesisId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "thesis.pdf", "application/pdf", "%PDF-1.4 integration-test".getBytes());
        MvcResult r = mockMvc.perform(multipart("/api/theses/" + thesisId + "/versions")
                        .file(file)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andReturn();
        return dataId(r);
    }

    protected void markFinal(User student, UUID thesisId, UUID versionId) throws Exception {
        doPatch("/api/theses/" + thesisId + "/versions/" + versionId + "/mark-final", student, null)
                .andExpect(status().isOk());
    }

    protected void approveFinal(User mentor, UUID thesisId) throws Exception {
        doPatch("/api/theses/" + thesisId + "/approve-final", mentor, null)
                .andExpect(status().isOk());
    }

    /** Returns the committee JSON array node (3 members). */
    protected JsonNode proposeCommittee(User mentor, UUID thesisId, User p1, User p2) throws Exception {
        MvcResult r = doPost("/api/theses/" + thesisId + "/committee/propose", mentor,
                Map.of("professorIds", List.of(p1.getId().toString(), p2.getId().toString())))
                .andExpect(status().isOk()).andReturn();
        return dataNode(r);
    }

    protected void approveCommittee(User service, UUID thesisId) throws Exception {
        doPost("/api/theses/" + thesisId + "/committee/approve", service, null)
                .andExpect(status().isOk());
    }

    protected void submitReview(User professor, UUID thesisId, UUID memberId, String notes) throws Exception {
        doPatch("/api/theses/" + thesisId + "/committee/" + memberId + "/review", professor,
                Map.of("notes", notes))
                .andExpect(status().isOk());
    }

    protected void acceptReview(User service, UUID thesisId) throws Exception {
        doPost("/api/theses/" + thesisId + "/committee/accept-review", service, null)
                .andExpect(status().isOk());
    }

    protected void verifyDefenseEligibility(User service, UUID thesisId, boolean exams, boolean docs) throws Exception {
        doPatch("/api/theses/" + thesisId + "/defense-eligibility", service,
                Map.of("examsCompleted", exams, "documentationComplete", docs))
                .andExpect(status().isOk());
    }

    protected void requestDefense(User student, UUID thesisId) throws Exception {
        doPost("/api/theses/" + thesisId + "/defenses/request", student, null)
                .andExpect(status().isOk());
    }

    protected UUID scheduleDefense(User service, UUID thesisId, String room, OffsetDateTime at) throws Exception {
        MvcResult r = doPost("/api/theses/" + thesisId + "/defenses", service,
                Map.of("room", room, "scheduledAt", at))
                .andExpect(status().isOk()).andReturn();
        return dataId(r);
    }

    protected UUID recordResult(User professor, UUID thesisId, UUID defenseId, int grade) throws Exception {
        MvcResult r = doPost("/api/theses/" + thesisId + "/defenses/" + defenseId + "/result", professor,
                Map.of("grade", grade))
                .andExpect(status().isOk()).andReturn();
        return dataId(r);
    }

    // -------------------------------------------------------------------------
    // Compound "advance to state" helpers for tests that only need to reach a state
    // -------------------------------------------------------------------------

    /** Drives a fresh thesis all the way to IN_PROGRESS and returns its id. */
    protected UUID advanceToInProgress(Actors a) throws Exception {
        UUID thesisId = createThesis(a.student, "Integration thesis " + shortId());
        decideEligibility(a.service, thesisId, true);
        submitMentorRequest(a.student, thesisId, a.mentor);
        decideMentor(a.mentor, thesisId, MentorDecision.ACCEPT, null);
        submitApplication(a.student, thesisId);
        archiveValidate(a.archive, thesisId, true, null);
        serviceValidate(a.service, thesisId, true, null);
        return thesisId;
    }

    /** Drives a fresh thesis to MENTOR_APPROVED (final version uploaded + approved). */
    protected UUID advanceToMentorApproved(Actors a) throws Exception {
        UUID thesisId = advanceToInProgress(a);
        UUID versionId = uploadVersion(a.student, thesisId);
        markFinal(a.student, thesisId, versionId);
        approveFinal(a.mentor, thesisId);
        return thesisId;
    }

    /** Drives a fresh thesis into COMMITTEE_REVIEW with a fully approved 3-member committee. */
    protected UUID advanceToCommitteeReview(Actors a) throws Exception {
        UUID thesisId = advanceToMentorApproved(a);
        proposeCommittee(a.mentor, thesisId, a.professorA, a.professorB);
        approveCommittee(a.service, thesisId);
        return thesisId;
    }

    /** Drives a fresh thesis to DEFENSE_SCHEDULED and returns the defense id. */
    protected UUID advanceToDefenseScheduled(Actors a, String room, OffsetDateTime at) throws Exception {
        UUID thesisId = advanceToCommitteeReview(a);
        acceptReview(a.service, thesisId);
        verifyDefenseEligibility(a.service, thesisId, true, true);
        requestDefense(a.student, thesisId);
        scheduleDefense(a.service, thesisId, room, at);
        return thesisId;
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    protected long notificationCount(User user, com.praksa.model.enums.NotificationType type, UUID thesisId) {
        return notificationRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .filter(n -> n.getType().equals(type.name()))
                .filter(n -> n.getThesis() != null && n.getThesis().getId().equals(thesisId))
                .count();
    }

    protected long totalNotifications(UUID thesisId) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getThesis() != null && n.getThesis().getId().equals(thesisId))
                .count();
    }
}
