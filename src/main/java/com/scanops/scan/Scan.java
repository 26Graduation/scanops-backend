package com.scanops.scan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.scanops.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "scans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "scan_id")
    private UUID scanId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_category", nullable = false)
    private ScanCategory scanCategory;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_mode")
    private ScanMode scanMode;

    @Column(name = "vuln_high", nullable = false)
    @Builder.Default
    private int vulnHigh = 0;

    @Column(name = "vuln_medium", nullable = false)
    @Builder.Default
    private int vulnMedium = 0;

    @Column(name = "vuln_low", nullable = false)
    @Builder.Default
    private int vulnLow = 0;

    @Column(name = "max_cvss_score")
    private Double maxCvssScore;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
