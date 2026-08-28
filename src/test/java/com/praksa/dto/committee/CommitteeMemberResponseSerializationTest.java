package com.praksa.dto.committee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praksa.model.CommitteeMember;
import com.praksa.model.Thesis;
import com.praksa.model.User;
import com.praksa.model.enums.MemberRole;
import com.praksa.model.enums.Role;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirically confirms the actual JSON wire key for the new external-non-voting-member flag —
 * never guessed, per this project's documented precedent that a Lombok boolean {@code isX}
 * getter is serialized by Jackson with the "is" prefix stripped (e.g. {@code Notification.isSent}
 * -> wire key {@code "sent"}, {@code Notification.isRead} -> {@code "read"}).
 *
 * <p>{@link CommitteeMemberResponse} deliberately names its field {@code externalNonVoting}
 * (no "is" prefix) so Lombok's generated getter is unambiguously {@code isExternalNonVoting()},
 * and this test proves the resulting wire key is exactly {@code "externalNonVoting"} — matching
 * what {@code praksa-frontend/src/types/api.ts} must declare.
 */
class CommitteeMemberResponseSerializationTest {

    private User user(Role role) {
        return User.builder().id(UUID.randomUUID()).email(role + "-" + UUID.randomUUID() + "@t.com")
                .fullName(role + " User").role(role).build();
    }

    private CommitteeMember member(boolean external) {
        Thesis thesis = Thesis.builder().id(UUID.randomUUID()).title("T")
                .student(user(Role.STUDENT)).build();
        return CommitteeMember.builder()
                .id(UUID.randomUUID())
                .thesis(thesis)
                .professor(user(Role.MENTOR))
                .memberRole(MemberRole.FORMAL_MEMBER)
                .isExternalNonVoting(external)
                .build();
    }

    @Test
    void externalMember_serializesAsExternalNonVotingTrue_noIsPrefix() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(CommitteeMemberResponse.from(member(true))));

        assertTrue(json.has("externalNonVoting"),
                "expected wire key 'externalNonVoting', actual JSON: " + json);
        assertTrue(json.get("externalNonVoting").asBoolean());
        assertFalse(json.has("isExternalNonVoting"), "must NOT double the 'is' prefix in the wire key");
    }

    @Test
    void votingMember_serializesAsExternalNonVotingFalse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(CommitteeMemberResponse.from(member(false))));

        assertTrue(json.has("externalNonVoting"));
        assertFalse(json.get("externalNonVoting").asBoolean());
    }
}
