package com.scanops.verify;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 도메인 소유권 인증 레코드. <b>사용자별</b>로 분리된다.
 *
 * <p>키가 (owner_id, domain) 복합이라, 사용자 A가 인증한 도메인을 사용자 B가 넣어도
 * B에게는 인증 레코드가 없고 토큰도 다르다 → 남의 URL을 넣어 자동 통과하는 문제가 사라진다.
 * (스키마는 Flyway V2가 소유. 컬럼 추가/복합 유니크는 V2__ 참고.)
 */
@Entity
@Table(
    name = "domain_verifications",
    uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "domain"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DomainVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 소유자 = 로그인 JWT의 subject(GitHub 숫자 id). */
    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String domain;

    private String verifyToken;

    private boolean verified;

    /** 마지막으로 .well-known 검증에 성공한 시각(스캔 직전 재검증 기록용). */
    private LocalDateTime verifiedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
