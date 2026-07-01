package com.scanops.scan;

import com.scanops.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ScanRepository extends JpaRepository<Scan, UUID> {
    Page<Scan> findByUser(User user, Pageable pageable);
    Page<Scan> findByUser_UserId(UUID userId, Pageable pageable);
    Page<Scan> findByScanCategory(ScanCategory scanCategory, Pageable pageable);

    // 기록 검색/필터 (target 부분검색, scanMode 필터)
    Page<Scan> findByTargetContainingIgnoreCase(String target, Pageable pageable);
    Page<Scan> findByScanModeAndTargetContainingIgnoreCase(ScanMode scanMode, String target, Pageable pageable);
}
