package com.scanops.subscription;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "usage_meters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageMeter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "usage_id")
    private UUID usageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(name = "dast_used", nullable = false)
    @Builder.Default
    private int dastUsed = 0;

    @Column(name = "sast_used", nullable = false)
    @Builder.Default
    private int sastUsed = 0;

    @Column(name = "loc_used", nullable = false)
    @Builder.Default
    private long locUsed = 0;
}
