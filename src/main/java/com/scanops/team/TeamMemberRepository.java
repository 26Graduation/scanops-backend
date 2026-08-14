package com.scanops.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    /** 이 사용자가 속한 팀 소속 정보. 한 계정은 최대 1개 팀에만 속한다(MVP 단순화). */
    Optional<TeamMember> findByUser_UserId(UUID userId);

    boolean existsByUser_UserId(UUID userId);

    List<TeamMember> findByTeam_TeamIdOrderByJoinedAtAsc(UUID teamId);

    long countByTeam_TeamId(UUID teamId);

    Optional<TeamMember> findByTeam_TeamIdAndUser_UserId(UUID teamId, UUID userId);
}
