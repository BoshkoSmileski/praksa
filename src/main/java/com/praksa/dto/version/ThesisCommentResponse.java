package com.praksa.dto.version;

import com.praksa.model.ThesisComment;
import com.praksa.model.enums.Role;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class ThesisCommentResponse {

    private final UUID id;
    private final UUID versionId;
    private final UUID authorId;
    private final String authorName;
    private final Role authorRole;
    private final String content;
    private final OffsetDateTime createdAt;

    public static ThesisCommentResponse from(ThesisComment c) {
        return new ThesisCommentResponse(c);
    }

    private ThesisCommentResponse(ThesisComment c) {
        this.id = c.getId();
        this.versionId = c.getVersion().getId();
        this.authorId = c.getAuthor().getId();
        this.authorName = c.getAuthor().getFullName();
        this.authorRole = c.getAuthor().getRole();
        this.content = c.getContent();
        this.createdAt = c.getCreatedAt();
    }
}
