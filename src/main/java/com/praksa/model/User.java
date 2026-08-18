package com.praksa.model;

import com.praksa.model.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "index_number", unique = true)
    private String indexNumber;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /**
     * Academic credits earned by the student. Relevant only for STUDENT users.
     * Nullable on purpose: existing rows (and non-student roles) may have no value.
     * A null or below-threshold value means the student is NOT eligible to submit a
     * thesis application (see ThesisServiceImpl.createThesis, 200-credit gate).
     * Only STUDENT_SERVICE may set/update this value (see UserServiceImpl.updateCredits).
     */
    @Column(name = "credits")
    private Integer credits;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
