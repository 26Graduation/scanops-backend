package com.scanops.scan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    List<ScanJob> findByOwnerEmailOrderByCreatedAtDesc(String ownerEmail);

    // 스캔 기록 페이지 조회(정렬은 Pageable이 담당). 빈 검색어는 LIKE %% 로 전체 매칭.
    Page<ScanJob> findByTargetUrlContainingIgnoreCase(String q, Pageable pageable);

    Page<ScanJob> findByScanModeAndTargetUrlContainingIgnoreCase(ScanMode mode, String q, Pageable pageable);
}
