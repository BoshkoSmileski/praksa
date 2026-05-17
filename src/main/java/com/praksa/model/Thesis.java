package com.praksa.model;

import com.praksa.model.enums.ThesisStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "theses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Thesis {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mentor_id")
    private User mentor;

    @Column(nullable = false)
    private String title;

    @Column(name = "student_comment", columnDefinition = "text")
    private String studentComment;

    @Column(name = "mentor_comment", columnDefinition = "text")
    private String mentorComment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ThesisStatus status;

    @Column(name = "submission_deadline")
    private OffsetDateTime submissionDeadline;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = ThesisStatus.PENDING_ELIGIBILITY_CHECK;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
