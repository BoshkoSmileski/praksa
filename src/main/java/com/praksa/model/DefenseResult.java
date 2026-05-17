package com.praksa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "defense_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DefenseResult {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "defense_id", nullable = false, unique = true)
    private Defense defense;

    @Column(nullable = false)
    private Integer grade;

    @Column(columnDefinition = "text")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    @Column(name = "recorded_at")
    private OffsetDateTime recordedAt;

    @PrePersist
    public void prePersist() {
        if (recordedAt == null) recordedAt = OffsetDateTime.now();
    }
}
