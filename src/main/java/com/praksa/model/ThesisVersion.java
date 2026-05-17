package com.praksa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "thesis_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"thesis_id", "version_number"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThesisVersion {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thesis_id", nullable = false)
    private Thesis thesis;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "pdf_url", nullable = false)
    private String pdfUrl;

    @Column(name = "is_final", nullable = false)
    private boolean isFinal;

    @Column(name = "uploaded_at")
    private OffsetDateTime uploadedAt;

    @PrePersist
    public void prePersist() {
        if (uploadedAt == null) uploadedAt = OffsetDateTime.now();
    }
}
