package com.scanops.scan;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "scan_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status;

    private String ownerEmail;

    @Column(nullable = false)
    private boolean verified;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
